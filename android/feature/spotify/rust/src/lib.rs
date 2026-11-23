//! JNI bridge between `:feature:spotify` (Kotlin) and librespot, a Spotify Connect receiver.
//!
//! - The receiver ([`receiver::run`]) runs on a small tokio runtime (2 workers) owned by the
//!   handle. librespot's player decodes on its own thread into [`ring::PcmRing`]; Kotlin pulls PCM
//!   with `nativeReadPcm` and writes it to an AudioTrack, so librespot never blocks on Android audio.
//! - Everything else is an [`events::Event`], delivered to the Kotlin listener by one dispatcher
//!   thread (the only thread that calls into the JVM).
//! - mDNS: by default nothing here advertises; Kotlin registers `_spotify-connect._tcp` with
//!   Android's NsdManager on `nativePort`. `libMdns = true` uses librespot's libmdns responder.
//! - Panics never cross into the JVM: each entry point catches them.

mod events;
mod logcat;
mod receiver;
mod ring;
mod sink;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc as std_mpsc;
use std::time::Duration;

use jni::objects::{GlobalRef, JObject, JShortArray, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use log::{info, warn};
use tokio::sync::{mpsc, oneshot};

use crate::events::{Event, Events};
use crate::receiver::{Command, Config};
use crate::ring::PcmRing;

struct Handle {
    runtime: Option<tokio::runtime::Runtime>,
    commands: mpsc::UnboundedSender<Command>,
    shutdown: Option<oneshot::Sender<()>>,
    main: Option<tokio::task::JoinHandle<()>>,
    ring: Arc<PcmRing>,
    port: u16,
    dispatcher: Option<std::thread::JoinHandle<()>>,
    stop_dispatch: Arc<AtomicBool>,
    /// Threads inside nativeReadPcm right now (the handle isn't freed under them).
    readers: AtomicUsize,
}

fn get_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
}

/// # Safety
/// `handle` must come from `nativeStart` and not have been passed to `nativeStop`.
unsafe fn handle_ref<'a>(handle: jlong) -> Option<&'a Handle> {
    if handle == 0 { None } else { Some(unsafe { &*(handle as *const Handle) }) }
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_vesper_feature_spotify_NativeBridge_nativeStart<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    name: JString<'l>,
    device_id: JString<'l>,
    port: jint,
    bitrate: jint,
    lib_mdns: jboolean,
    credentials_dir: JString<'l>,
    tmp_dir: JString<'l>,
    initial_volume: jint,
    listener: JObject<'l>,
) -> jlong {
    logcat::init();
    let cfg = Config {
        name: get_string(&mut env, &name),
        device_id: get_string(&mut env, &device_id),
        bitrate: bitrate.max(0) as u32,
        credentials_dir: PathBuf::from(get_string(&mut env, &credentials_dir)),
        tmp_dir: PathBuf::from(get_string(&mut env, &tmp_dir)),
        initial_volume: initial_volume.clamp(0, u16::MAX as jint) as u16,
        lib_mdns: lib_mdns == JNI_TRUE,
    };
    let (vm, listener) = match (env.get_java_vm(), env.new_global_ref(listener)) {
        (Ok(vm), Ok(l)) => (vm, l),
        _ => {
            throw(&mut env, "JNI setup failed");
            return 0;
        }
    };
    let result = catch_unwind(AssertUnwindSafe(|| start(cfg, port.clamp(0, 65535) as u16, vm, listener)));
    match result {
        Ok(Ok(h)) => Box::into_raw(Box::new(h)) as jlong,
        Ok(Err(msg)) => {
            throw(&mut env, &msg);
            0
        }
        Err(_) => {
            throw(&mut env, "receiver panicked while starting");
            0
        }
    }
}

fn start(cfg: Config, port: u16, vm: JavaVM, listener: GlobalRef) -> Result<Handle, String> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("spotify-rt")
        .thread_stack_size(512 * 1024)
        .enable_all()
        .build()
        .map_err(|e| format!("runtime: {e}"))?;
    let client_id = librespot_core::SessionConfig::default().client_id;
    let discovery = {
        let _guard = runtime.enter();
        receiver::launch(&cfg, &client_id, port).map_err(|e| format!("zeroconf server on port {port}: {e}"))?
    };
    let port = discovery.port();

    let (event_tx, event_rx) = std_mpsc::channel();
    let events = Events::new(event_tx);
    let stop_dispatch = Arc::new(AtomicBool::new(false));
    let dispatcher = {
        let stop = stop_dispatch.clone();
        std::thread::Builder::new()
            .name("spotify-events".into())
            .stack_size(256 * 1024)
            .spawn(move || dispatch(vm, listener, event_rx, stop))
            .map_err(|e| format!("dispatcher thread: {e}"))?
    };

    let ring = Arc::new(PcmRing::default());
    let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    let main = runtime.spawn(receiver::run(cfg, Some(discovery), port, cmd_rx, shutdown_rx, events, ring.clone()));
    Ok(Handle {
        runtime: Some(runtime),
        commands: cmd_tx,
        shutdown: Some(shutdown_tx),
        main: Some(main),
        ring,
        port,
        dispatcher: Some(dispatcher),
        stop_dispatch,
        readers: AtomicUsize::new(0),
    })
}

/// Delivers events to the Kotlin listener. Exits when the receiver is stopped.
fn dispatch(vm: JavaVM, listener: GlobalRef, rx: std_mpsc::Receiver<Event>, stop: Arc<AtomicBool>) {
    let mut env = match vm.attach_current_thread_permanently() {
        Ok(env) => env,
        Err(e) => {
            warn!("dispatcher couldn't attach to the JVM: {e}");
            return;
        }
    };
    loop {
        let ev = match rx.recv_timeout(Duration::from_millis(250)) {
            Ok(ev) => ev,
            Err(std_mpsc::RecvTimeoutError::Timeout) => {
                if stop.load(Ordering::Acquire) { break } else { continue }
            }
            Err(std_mpsc::RecvTimeoutError::Disconnected) => break,
        };
        let r = env.with_local_frame(8, |env| -> jni::errors::Result<()> {
            let l = listener.as_obj();
            match ev {
                Event::State { phase, detail } => {
                    let d = env.new_string(detail)?;
                    env.call_method(l, "onState", "(ILjava/lang/String;)V", &[JValue::Int(phase as i32), JValue::Object(&d)])?;
                }
                Event::Client(name) => {
                    let n = env.new_string(name)?;
                    env.call_method(l, "onClient", "(Ljava/lang/String;)V", &[JValue::Object(&n)])?;
                }
                Event::Track { title, artist, album, duration_ms, cover_url } => {
                    let t = env.new_string(title)?;
                    let a = env.new_string(artist)?;
                    let al = env.new_string(album)?;
                    let c = env.new_string(cover_url)?;
                    env.call_method(
                        l,
                        "onTrack",
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V",
                        &[JValue::Object(&t), JValue::Object(&a), JValue::Object(&al), JValue::Long(duration_ms as i64), JValue::Object(&c)],
                    )?;
                }
                Event::Playback { state, position_ms } => {
                    env.call_method(l, "onPlayback", "(IJ)V", &[JValue::Int(state as i32), JValue::Long(position_ms as i64)])?;
                }
                Event::Position(ms) => {
                    env.call_method(l, "onPosition", "(J)V", &[JValue::Long(ms as i64)])?;
                }
                Event::Stopped => {
                    env.call_method(l, "onStopped", "()V", &[])?;
                }
                Event::Volume(v) => {
                    env.call_method(l, "onVolume", "(I)V", &[JValue::Int(v as i32)])?;
                }
                Event::Sink(open) => {
                    env.call_method(l, "onSink", "(Z)V", &[JValue::Bool(if open { JNI_TRUE } else { JNI_FALSE })])?;
                }
            }
            Ok(())
        });
        if r.is_err() && env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }
    info!("event dispatcher ended");
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_vesper_feature_spotify_NativeBridge_nativePort(_env: JNIEnv, _this: JObject, handle: jlong) -> jint {
    // SAFETY: Kotlin passes a live handle (see handle_ref).
    unsafe { handle_ref(handle) }.map_or(0, |h| h.port as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_vesper_feature_spotify_NativeBridge_nativeStop(_env: JNIEnv, _this: JObject, handle: jlong) {
    if handle == 0 {
        return;
    }
    // SAFETY: from nativeStart; Kotlin zeroes its copy before calling this, so it's freed once.
    let mut h = unsafe { Box::from_raw(handle as *mut Handle) };
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // First: nothing plays any more, and librespot's player can't block on a full ring.
        h.ring.close();
        if let Some(tx) = h.shutdown.take() {
            let _ = tx.send(());
        }
        if let (Some(rt), Some(main)) = (h.runtime.as_ref(), h.main.take()) {
            rt.block_on(async {
                if tokio::time::timeout(Duration::from_secs(4), main).await.is_err() {
                    warn!("receiver didn't stop within 4 s");
                }
            });
        }
        if let Some(rt) = h.runtime.take() {
            rt.shutdown_timeout(Duration::from_secs(2));
        }
        h.stop_dispatch.store(true, Ordering::Release);
        if let Some(d) = h.dispatcher.take() {
            let _ = d.join();
        }
        info!("receiver stopped");
    }));
    // Kotlin joins its reader first; if one is somehow still inside nativeReadPcm (it returns -1
    // within moments now that the ring is closed), wait for it, and leak the small struct rather
    // than free it under a reader.
    for _ in 0..100 {
        if h.readers.load(Ordering::Acquire) == 0 {
            return;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    warn!("a PCM reader is still active; leaking the receiver handle");
    std::mem::forget(h);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_vesper_feature_spotify_NativeBridge_nativeReadPcm(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    buf: JShortArray,
    timeout_ms: jint,
) -> jint {
    // SAFETY: Kotlin passes a live handle (see handle_ref).
    let Some(h) = (unsafe { handle_ref(handle) }) else { return -1 };
    h.readers.fetch_add(1, Ordering::AcqRel);
    let n = catch_unwind(AssertUnwindSafe(|| {
        let len = env.get_array_length(&buf).unwrap_or(0).max(0) as usize;
        let mut tmp = vec![0i16; len];
        let n = h.ring.read(&mut tmp, Duration::from_millis(timeout_ms.max(0) as u64));
        if n > 0 && env.set_short_array_region(&buf, 0, &tmp[..n as usize]).is_err() {
            return -1;
        }
        n
    }))
    .unwrap_or(-1);
    h.readers.fetch_sub(1, Ordering::AcqRel);
    n
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_vesper_feature_spotify_NativeBridge_nativeWakeReader(_env: JNIEnv, _this: JObject, handle: jlong) {
    // SAFETY: Kotlin passes a live handle (see handle_ref).
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.ring.wake();
    }
}

/// `cmd`: NativeBridge.CMD_* (play 0, pause 1, next 2, previous 3, seek 4 (arg = ms),
/// volume 5 (arg = 0..65535), disconnect 6, reconnect 7, forget 8).
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_vesper_feature_spotify_NativeBridge_nativeCommand(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    cmd: jint,
    arg: jlong,
) -> jboolean {
    // SAFETY: Kotlin passes a live handle (see handle_ref).
    let Some(h) = (unsafe { handle_ref(handle) }) else { return JNI_FALSE };
    let c = match cmd {
        0 => Command::Play,
        1 => Command::Pause,
        2 => Command::Next,
        3 => Command::Previous,
        4 => Command::Seek(arg.clamp(0, u32::MAX as jlong) as u32),
        5 => Command::LocalVolume(arg.clamp(0, u16::MAX as jlong) as u16),
        6 => Command::Disconnect,
        7 => Command::Reconnect,
        8 => Command::Forget,
        _ => return JNI_FALSE,
    };
    if h.commands.send(c).is_ok() { JNI_TRUE } else { JNI_FALSE }
}
