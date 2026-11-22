//! JNI bridge between `:feature:airplay` (Kotlin) and the `shairplay` AirPlay receiver.
//!
//! Audio only. The receiver delivers f32 PCM; we convert it to s16 and park it in a bounded
//! ring ([`PcmRing`]). A Kotlin thread pulls from the ring with `readPcm` and writes it to an
//! `AudioTrack`, so the library's threads never block on Android audio. Everything else
//! (metadata, artwork, volume, progress, connect / disconnect) is pushed to a Kotlin listener.
//!
//! mDNS: by default the library's own responder (`mdns-sd`) is turned off and Kotlin registers
//! the services with Android's `NsdManager`, using the TXT records from `serviceInfo`.

mod logcat;
mod pairing;

use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use jni::objects::{GlobalRef, JByteArray, JObject, JShortArray, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use shairplay::{
    AirPlayMode, AudioFormat, AudioHandler, AudioSession, RaopServer, RemoteCommand,
    RemoteControl, ShairplayError, TrackMetadata,
};

/// ~1.5 s of 44.1 kHz stereo. The sender paces AP1; AP2 is paced by the library's playout thread.
/// Anything beyond this means the AudioTrack is stalled: drop the oldest audio.
const RING_CAPACITY_SAMPLES: usize = 44_100 * 2 * 3 / 2;

// ---------------------------------------------------------------------------------------------
// PCM ring
// ---------------------------------------------------------------------------------------------

struct PcmState {
    queue: VecDeque<i16>,
    /// Incremented on every new audio session; an old session dropping late doesn't close the new one.
    generation: u64,
    /// The current session ended: `readPcm` returns -1 once the queue is drained.
    closed: bool,
    dropped: u64,
}

struct PcmRing {
    state: Mutex<PcmState>,
    cv: Condvar,
}

impl PcmRing {
    fn new() -> Self {
        Self {
            state: Mutex::new(PcmState {
                queue: VecDeque::with_capacity(RING_CAPACITY_SAMPLES),
                generation: 0,
                closed: true,
                dropped: 0,
            }),
            cv: Condvar::new(),
        }
    }

    fn open(&self) -> u64 {
        let mut s = self.state.lock().unwrap();
        s.generation += 1;
        s.queue.clear();
        s.closed = false;
        self.cv.notify_all();
        s.generation
    }

    fn close(&self, generation: u64) -> bool {
        let mut s = self.state.lock().unwrap();
        if s.generation != generation {
            return false;
        }
        s.closed = true;
        self.cv.notify_all();
        true
    }

    fn close_all(&self) {
        let mut s = self.state.lock().unwrap();
        s.closed = true;
        s.queue.clear();
        self.cv.notify_all();
    }

    fn flush(&self, generation: u64) {
        let mut s = self.state.lock().unwrap();
        if s.generation == generation {
            s.queue.clear();
        }
    }

    fn push(&self, generation: u64, samples: &[f32]) {
        let mut s = self.state.lock().unwrap();
        if s.generation != generation || s.closed {
            return;
        }
        // Keep channel alignment: always drop an even number of samples.
        let mut samples = samples;
        if samples.len() > RING_CAPACITY_SAMPLES {
            let skip = (samples.len() - RING_CAPACITY_SAMPLES + 1) & !1;
            s.dropped += skip as u64;
            samples = &samples[skip..];
        }
        let overflow = (s.queue.len() + samples.len()).saturating_sub(RING_CAPACITY_SAMPLES);
        if overflow > 0 {
            let n = ((overflow + 1) & !1).min(s.queue.len());
            s.queue.drain(..n);
            s.dropped += n as u64;
        }
        s.queue.extend(samples.iter().map(|&f| f32_to_i16(f)));
        self.cv.notify_one();
    }
}

#[inline]
fn f32_to_i16(f: f32) -> i16 {
    (f.clamp(-1.0, 1.0) * 32767.0) as i16
}

// ---------------------------------------------------------------------------------------------
// Callbacks to Kotlin
// ---------------------------------------------------------------------------------------------

struct Shared {
    vm: JavaVM,
    listener: GlobalRef,
    ring: PcmRing,
    remote: Mutex<Option<Arc<dyn RemoteControl>>>,
}

impl Shared {
    /// Calls `listener.<name><sig>`. Runs on whatever library thread fired the event; the
    /// Kotlin side only enqueues work, so this is quick.
    fn call(&self, name: &str, sig: &str, build: impl for<'e> FnOnce(&mut JNIEnv<'e>) -> Vec<JValueOwned<'e>>) {
        let Ok(mut env) = self.vm.attach_current_thread_as_daemon() else {
            return;
        };
        // Daemon threads never return to Java, so free local refs per call.
        let _ = env.with_local_frame(16, |env| -> jni::errors::Result<()> {
            let args = build(env);
            let jargs: Vec<JValue> = args.iter().map(JValueOwned::borrow).collect();
            if env.call_method(&self.listener, name, sig, &jargs).is_err() {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_describe();
                    let _ = env.exception_clear();
                }
            }
            Ok(())
        });
    }
}

/// JValue that owns its local reference for the duration of the call.
enum JValueOwned<'a> {
    Int(i32),
    Long(i64),
    Float(f32),
    Obj(JObject<'a>),
}

impl<'a> JValueOwned<'a> {
    fn borrow(&self) -> JValue<'_, '_> {
        match self {
            JValueOwned::Int(v) => JValue::Int(*v),
            JValueOwned::Long(v) => JValue::Long(*v),
            JValueOwned::Float(v) => JValue::Float(*v),
            JValueOwned::Obj(o) => JValue::Object(o),
        }
    }
}

fn jstr<'a>(env: &mut JNIEnv<'a>, s: Option<&str>) -> JValueOwned<'a> {
    match s {
        Some(s) => JValueOwned::Obj(env.new_string(s).map(JObject::from).unwrap_or_default()),
        None => JValueOwned::Obj(JObject::null()),
    }
}

struct Handler {
    shared: Arc<Shared>,
}

struct Session {
    shared: Arc<Shared>,
    generation: u64,
}

impl AudioSession for Session {
    fn audio_process(&mut self, samples: &[f32]) {
        self.shared.ring.push(self.generation, samples);
    }

    fn audio_flush(&mut self) {
        self.shared.ring.flush(self.generation);
        self.shared.call("onFlush", "()V", |_| vec![]);
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        if self.shared.ring.close(self.generation) {
            self.shared.call("onAudioStop", "()V", |_| vec![]);
        }
    }
}

impl AudioHandler for Handler {
    fn audio_init(&self, format: AudioFormat) -> Box<dyn AudioSession> {
        tracing::info!(?format, "audio session");
        let generation = self.shared.ring.open();
        let (rate, ch) = (format.sample_rate as i32, format.channels as i32);
        self.shared
            .call("onAudioStart", "(II)V", |_| vec![JValueOwned::Int(rate), JValueOwned::Int(ch)]);
        Box::new(Session { shared: self.shared.clone(), generation })
    }

    fn on_volume(&self, volume: f32) {
        self.shared.call("onVolume", "(F)V", |_| vec![JValueOwned::Float(volume)]);
    }

    fn on_metadata(&self, m: &TrackMetadata) {
        let duration = m.duration_ms.map(i64::from).unwrap_or(-1);
        self.shared.call(
            "onMetadata",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V",
            |env| {
                vec![
                    jstr(env, m.title.as_deref()),
                    jstr(env, m.artist.as_deref()),
                    jstr(env, m.album.as_deref()),
                    jstr(env, m.genre.as_deref()),
                    JValueOwned::Long(duration),
                ]
            },
        );
    }

    fn on_coverart(&self, coverart: &[u8]) {
        self.shared.call("onCoverArt", "([B)V", |env| {
            let arr = env.byte_array_from_slice(coverart).map(JObject::from).unwrap_or_default();
            vec![JValueOwned::Obj(arr)]
        });
    }

    fn on_progress(&self, start: u32, current: u32, end: u32) {
        self.shared.call("onProgress", "(JJJ)V", |_| {
            vec![
                JValueOwned::Long(i64::from(start)),
                JValueOwned::Long(i64::from(current)),
                JValueOwned::Long(i64::from(end)),
            ]
        });
    }

    fn on_remote_control(&self, remote: Arc<dyn RemoteControl>) {
        *self.shared.remote.lock().unwrap() = Some(remote);
        self.shared.call("onRemoteControl", "()V", |_| vec![]);
    }

    fn on_client_connected(&self, addr: &str) {
        self.shared.call("onClientConnected", "(Ljava/lang/String;)V", |env| vec![jstr(env, Some(addr))]);
    }

    fn on_client_disconnected(&self, addr: &str) {
        self.shared
            .call("onClientDisconnected", "(Ljava/lang/String;)V", |env| vec![jstr(env, Some(addr))]);
    }

    fn on_error(&self, error: &ShairplayError) {
        tracing::warn!(%error, "AirPlay error");
        let msg = error.to_string();
        self.shared.call("onError", "(Ljava/lang/String;)V", |env| vec![jstr(env, Some(&msg))]);
    }
}

// ---------------------------------------------------------------------------------------------
// Receiver handle
// ---------------------------------------------------------------------------------------------

struct Receiver {
    runtime: tokio::runtime::Runtime,
    server: Mutex<Option<RaopServer>>,
    shared: Arc<Shared>,
    info: String,
}

/// `key=value` lines; TXT records as `raop.<k>=v` / `airplay.<k>=v` (parsed by `ServiceInfo.parse`).
fn service_info_text(server: &RaopServer, ap2: bool) -> String {
    let info = server.service_info();
    let mut out = String::new();
    out.push_str(&format!("port={}\n", info.port));
    out.push_str(&format!("raop_name={}\n", info.raop_name));
    out.push_str(&format!("airplay_name={}\n", info.airplay_name));
    out.push_str(&format!("ap2={}\n", ap2));
    for (k, v) in &info.raop_txt {
        out.push_str(&format!("raop.{k}={v}\n"));
    }
    for (k, v) in &info.airplay_txt {
        out.push_str(&format!("airplay.{k}={v}\n"));
    }
    out
}

fn start(
    env: &mut JNIEnv,
    name: String,
    hwaddr: Vec<u8>,
    port: u16,
    ap2: bool,
    lib_mdns: bool,
    state_dir: String,
    listener: &JObject,
) -> Result<Receiver, String> {
    logcat::init();
    // The library skips its own mDNS responder when `CI` is set; we advertise through
    // NsdManager instead (see NsdAdvertiser.kt).
    // SAFETY: called before the runtime starts; no other thread in this library reads the env.
    unsafe {
        if lib_mdns {
            std::env::remove_var("CI");
        } else {
            std::env::set_var("CI", "1");
        }
    }

    let shared = Arc::new(Shared {
        vm: env.get_java_vm().map_err(|e| e.to_string())?,
        listener: env.new_global_ref(listener).map_err(|e| e.to_string())?,
        ring: PcmRing::new(),
        remote: Mutex::new(None),
    });
    let handler = Arc::new(Handler { shared: shared.clone() });

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .max_blocking_threads(4)
        .thread_name("airplay-rt")
        .thread_stack_size(512 * 1024)
        .enable_all()
        .build()
        .map_err(|e| e.to_string())?;

    let mut builder = RaopServer::builder()
        .name(name)
        .hwaddr(hwaddr)
        .port(port)
        .max_clients(4)
        .output_max_channels(2)
        .mode(if ap2 { AirPlayMode::AirPlay2 } else { AirPlayMode::AirPlay1 });
    if ap2 {
        builder = builder.pairing_store(Arc::new(pairing::FilePairingStore::open(&state_dir)));
    }
    let mut server = builder.build(handler).map_err(|e| e.to_string())?;
    runtime.block_on(server.start()).map_err(|e| e.to_string())?;
    let info = service_info_text(&server, ap2);
    tracing::info!(port = server.service_info().port, ap2, lib_mdns, "AirPlay receiver started");
    Ok(Receiver { runtime, server: Mutex::new(Some(server)), shared, info })
}

fn receiver<'a>(handle: jlong) -> Option<&'a Receiver> {
    if handle == 0 {
        None
    } else {
        // SAFETY: `handle` comes from Box::into_raw in nativeStart and stays valid until nativeStop.
        Some(unsafe { &*(handle as *const Receiver) })
    }
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
}

// ---------------------------------------------------------------------------------------------
// JNI entry points: com.echobedside.feature.airplay.NativeBridge (a Kotlin object)
// ---------------------------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_echobedside_feature_airplay_NativeBridge_nativeStart<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    name: JString<'a>,
    hwaddr: JByteArray<'a>,
    port: jint,
    ap2: jboolean,
    lib_mdns: jboolean,
    state_dir: JString<'a>,
    listener: JObject<'a>,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let name: String = env.get_string(&name).map(Into::into).map_err(|e| e.to_string())?;
        let state_dir: String = env.get_string(&state_dir).map(Into::into).map_err(|e| e.to_string())?;
        let hwaddr = env.convert_byte_array(&hwaddr).map_err(|e| e.to_string())?;
        start(&mut env, name, hwaddr, port as u16, ap2 != JNI_FALSE, lib_mdns != JNI_FALSE, state_dir, &listener)
    }));
    match result {
        Ok(Ok(receiver)) => Box::into_raw(Box::new(receiver)) as jlong,
        Ok(Err(msg)) => {
            tracing::error!(%msg, "AirPlay receiver failed to start");
            throw(&mut env, &msg);
            0
        }
        Err(_) => {
            throw(&mut env, "panic in nativeStart");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_echobedside_feature_airplay_NativeBridge_nativeServiceInfo<'a>(
    env: JNIEnv<'a>,
    _this: JObject<'a>,
    handle: jlong,
) -> JString<'a> {
    let text = receiver(handle).map(|r| r.info.clone()).unwrap_or_default();
    env.new_string(text).unwrap_or_default()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_echobedside_feature_airplay_NativeBridge_nativeStop<'a>(
    _env: JNIEnv<'a>,
    _this: JObject<'a>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: see `receiver`; the Kotlin side drops its handle before calling this.
    let receiver = unsafe { Box::from_raw(handle as *mut Receiver) };
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(mut server) = receiver.server.lock().unwrap().take() {
            receiver.runtime.block_on(server.stop());
        }
        receiver.shared.ring.close_all();
        *receiver.shared.remote.lock().unwrap() = None;
    }));
    let Receiver { runtime, .. } = *receiver;
    runtime.shutdown_timeout(Duration::from_secs(2));
    tracing::info!("AirPlay receiver stopped");
}

/// Blocks up to `timeout_ms` for PCM. Returns the number of samples copied into `buf`,
/// 0 on timeout (paused / waiting), -1 when the audio session ended and the ring is empty.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_echobedside_feature_airplay_NativeBridge_nativeReadPcm<'a>(
    env: JNIEnv<'a>,
    _this: JObject<'a>,
    handle: jlong,
    buf: JShortArray<'a>,
    timeout_ms: jint,
) -> jint {
    let Some(r) = receiver(handle) else { return -1 };
    let capacity = env.get_array_length(&buf).unwrap_or(0) as usize & !1;
    let ring = &r.shared.ring;
    let guard = ring.state.lock().unwrap();
    let (mut s, _) = ring
        .cv
        .wait_timeout_while(guard, Duration::from_millis(timeout_ms.max(0) as u64), |s| {
            s.queue.is_empty() && !s.closed
        })
        .unwrap();
    if s.queue.is_empty() {
        return if s.closed { -1 } else { 0 };
    }
    let n = s.queue.len().min(capacity);
    let (a, b) = s.queue.as_slices();
    let first = a.len().min(n);
    if env.set_short_array_region(&buf, 0, &a[..first]).is_err() {
        return -1;
    }
    if n > first && env.set_short_array_region(&buf, first as i32, &b[..n - first]).is_err() {
        return -1;
    }
    s.queue.drain(..n);
    n as jint
}

/// Samples dropped because the AudioTrack fell behind (diagnostics).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_echobedside_feature_airplay_NativeBridge_nativeDroppedSamples<'a>(
    _env: JNIEnv<'a>,
    _this: JObject<'a>,
    handle: jlong,
) -> jlong {
    receiver(handle).map(|r| r.shared.ring.state.lock().unwrap().dropped as jlong).unwrap_or(0)
}

/// DACP command back to the sender (AirPlay 1 only). Blocking (up to ~2 s): call off the main thread.
/// cmd: 0 play/pause toggle, 1 next, 2 previous, 3 stop, 4 volume (arg 0..100).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_echobedside_feature_airplay_NativeBridge_nativeRemote<'a>(
    _env: JNIEnv<'a>,
    _this: JObject<'a>,
    handle: jlong,
    cmd: jint,
    arg: jint,
) -> jboolean {
    let Some(r) = receiver(handle) else { return JNI_FALSE };
    let remote = r.shared.remote.lock().unwrap().clone();
    let Some(remote) = remote else { return JNI_FALSE };
    let command = match cmd {
        0 => RemoteCommand::Play,
        1 => RemoteCommand::NextTrack,
        2 => RemoteCommand::PreviousTrack,
        3 => RemoteCommand::Stop,
        4 => RemoteCommand::SetVolume(arg.clamp(0, 100) as u8),
        _ => return JNI_FALSE,
    };
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| remote.send_command(command))) {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(e)) => {
            tracing::warn!(%e, "DACP command failed");
            JNI_FALSE
        }
        Err(_) => JNI_FALSE,
    }
}

/// Forget the sender's DACP remote (it disconnected).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_echobedside_feature_airplay_NativeBridge_nativeClearRemote<'a>(
    _env: JNIEnv<'a>,
    _this: JObject<'a>,
    handle: jlong,
) {
    if let Some(r) = receiver(handle) {
        *r.shared.remote.lock().unwrap() = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_drops_oldest_and_closes_by_generation() {
        let ring = PcmRing::new();
        let g1 = ring.open();
        ring.push(g1, &vec![0.5; RING_CAPACITY_SAMPLES + 10]);
        {
            let s = ring.state.lock().unwrap();
            assert_eq!(s.queue.len(), RING_CAPACITY_SAMPLES);
            assert!(s.dropped >= 10);
        }
        let g2 = ring.open();
        assert!(!ring.close(g1), "an old session must not close the new one");
        assert!(!ring.state.lock().unwrap().closed);
        assert!(ring.close(g2));
        assert!(ring.state.lock().unwrap().closed);
    }

    #[test]
    fn f32_conversion_clamps() {
        assert_eq!(f32_to_i16(2.0), 32767);
        assert_eq!(f32_to_i16(-2.0), -32767);
        assert_eq!(f32_to_i16(0.0), 0);
    }
}
