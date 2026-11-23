//! The Spotify Connect receiver: zeroconf discovery → session → Spirc, kept alive on its own.
//!
//! Modelled on librespot's own `main.rs` loop, with what an always-on appliance needs:
//! - Never exits the process. Failures become [`Phase`] events for Kotlin to show.
//! - Reconnects after network loss with a growing back-off (2 s … 5 min), reset once a session
//!   has stayed up for a minute, so a dead network never turns into a busy loop.
//! - A non-Premium account or refused credentials stop the retries and drop the cached
//!   credentials, so the next start doesn't sign in again; the receiver waits for a new login
//!   from the Spotify app.

use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use librespot_connect::{ConnectConfig, Spirc};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::DeviceType;
use librespot_core::{Session, SessionConfig};
use librespot_discovery::Discovery;
use librespot_metadata::audio::{AudioItem, UniqueFields};
use librespot_playback::config::{Bitrate, PlayerConfig};
use librespot_playback::mixer::NoOpVolume;
use librespot_playback::player::{Player, PlayerEvent};
use log::{error, info, warn};
use tokio::sync::{mpsc, oneshot};

use crate::events::{Event, Events, Phase, PlayState};
use crate::ring::PcmRing;
use crate::sink::{VesperMixer, VesperSink};

pub struct Config {
    pub name: String,
    pub device_id: String,
    pub bitrate: u32,
    /// Holds `credentials.json` (the reusable login blob). The app's noBackupFilesDir.
    pub credentials_dir: PathBuf,
    /// librespot downloads tracks into temp files here (Android has no writable /tmp).
    pub tmp_dir: PathBuf,
    /// 0..=65535, the Echo's speaker volume at start.
    pub initial_volume: u16,
    /// Use librespot's own mDNS responder (libmdns) instead of Android's NsdManager.
    pub lib_mdns: bool,
}

#[derive(Debug)]
pub enum Command {
    Play,
    Pause,
    Next,
    Previous,
    Seek(u32),
    /// The Echo's own volume (keys, Control Center), 0..=65535.
    LocalVolume(u16),
    /// Leave the Spotify session (the app shows no device playing).
    Disconnect,
    /// The network came back: drop the (likely dead) connection and sign in again now.
    Reconnect,
    /// Sign out and delete the cached credentials.
    Forget,
}

pub fn bitrate(kbps: u32) -> Bitrate {
    match kbps {
        0..=127 => Bitrate::Bitrate96,
        128..=239 => Bitrate::Bitrate160,
        _ => Bitrate::Bitrate320,
    }
}

/// Why a sign-in failed, from librespot's error text (its error types aren't public).
#[derive(Debug, PartialEq, Eq)]
pub enum Failure {
    PremiumRequired,
    BadCredentials,
    /// Anything else (network, Spotify unavailable): retry later.
    Transient,
}

pub fn classify(message: &str) -> Failure {
    let m = message.to_ascii_lowercase();
    if m.contains("premium account required") || m.contains("does not support") {
        Failure::PremiumRequired
    } else if m.contains("bad credentials") || m.contains("could not validate credentials") {
        Failure::BadCredentials
    } else {
        Failure::Transient
    }
}

/// Retry back-off after a failed or dropped session.
pub struct Backoff {
    next: Duration,
}

impl Backoff {
    pub const FIRST: Duration = Duration::from_secs(2);
    pub const MAX: Duration = Duration::from_secs(300);
    /// A session that lasted this long resets the back-off.
    pub const STABLE: Duration = Duration::from_secs(60);

    pub fn new() -> Self {
        Self { next: Self::FIRST }
    }

    /// The wait before the next attempt; each call doubles the following one.
    pub fn bump(&mut self) -> Duration {
        let d = self.next;
        self.next = (self.next * 2).min(Self::MAX);
        d
    }

    pub fn reset(&mut self) {
        self.next = Self::FIRST;
    }
}

impl Default for Backoff {
    fn default() -> Self {
        Self::new()
    }
}

fn non_premium(session: &Session) -> bool {
    matches!(session.get_user_attribute("type"), Some(t) if t != "premium")
}

/// The cover closest to 300 px wide (Now Playing draws it at ~324 px), else the largest.
pub fn pick_cover(covers: &[(i32, String)]) -> Option<String> {
    let mut sorted: Vec<&(i32, String)> = covers.iter().collect();
    sorted.sort_by_key(|(w, _)| *w);
    sorted.iter().find(|(w, _)| *w >= 300).or(sorted.last()).map(|(_, u)| u.clone())
}

fn track_event(item: &AudioItem) -> Event {
    let (artist, album) = match &item.unique_fields {
        UniqueFields::Track { artists, album, .. } => {
            (artists.0.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", "), album.clone())
        }
        UniqueFields::Local { artists, album, .. } => (artists.clone().unwrap_or_default(), album.clone().unwrap_or_default()),
        UniqueFields::Episode { show_name, .. } => (show_name.clone(), String::new()),
    };
    let covers: Vec<(i32, String)> = item.covers.iter().map(|c| (c.width, c.url.clone())).collect();
    Event::Track {
        title: item.name.clone(),
        artist,
        album,
        duration_ms: item.duration_ms,
        cover_url: pick_cover(&covers).unwrap_or_default(),
    }
}

/// PlayerEvent → Event (None = nothing for Kotlin).
fn map_player_event(e: PlayerEvent) -> Option<Event> {
    Some(match e {
        PlayerEvent::TrackChanged { audio_item } => track_event(&audio_item),
        PlayerEvent::Playing { position_ms, .. } => Event::Playback { state: PlayState::Playing, position_ms },
        PlayerEvent::Paused { position_ms, .. } => Event::Playback { state: PlayState::Paused, position_ms },
        PlayerEvent::Loading { position_ms, .. } => Event::Playback { state: PlayState::Loading, position_ms },
        PlayerEvent::Seeked { position_ms, .. } | PlayerEvent::PositionCorrection { position_ms, .. } => Event::Position(position_ms),
        PlayerEvent::Stopped { .. } => Event::Stopped,
        PlayerEvent::SessionClientChanged { client_name, .. } => Event::Client(client_name),
        PlayerEvent::Unavailable { .. } => {
            warn!("track unavailable (not playable in this market, or the audio key was refused)");
            return None;
        }
        _ => return None,
    })
}

type SpircTask = Pin<Box<dyn std::future::Future<Output = ()> + Send>>;

/// Runs until `shutdown` fires. `discovery` was launched by the caller (so its port is known
/// before Kotlin advertises it).
pub async fn run(
    cfg: Config,
    mut discovery: Option<Discovery>,
    discovery_port: u16,
    mut commands: mpsc::UnboundedReceiver<Command>,
    mut shutdown: oneshot::Receiver<()>,
    events: Events,
    ring: Arc<PcmRing>,
) {
    let cache = match Cache::new(Some(&cfg.credentials_dir), None, None, None) {
        Ok(c) => Some(c),
        Err(e) => {
            warn!("no credentials cache: {e}");
            None
        }
    };
    let credentials_file = cfg.credentials_dir.join("credentials.json");
    let session_config = SessionConfig {
        device_id: cfg.device_id.clone(),
        tmp_dir: cfg.tmp_dir.clone(),
        ..SessionConfig::default()
    };
    let connect_config = ConnectConfig {
        name: cfg.name.clone(),
        device_type: DeviceType::Speaker,
        initial_volume: cfg.initial_volume,
        ..ConnectConfig::default()
    };
    let player_config = PlayerConfig {
        bitrate: bitrate(cfg.bitrate),
        ..PlayerConfig::default()
    };
    let mixer = Arc::new(VesperMixer::new(cfg.initial_volume, events.clone()));

    let mut session = Session::new(session_config.clone(), cache.clone());
    let sink_ring = ring.clone();
    let sink_events = events.clone();
    let player = Player::new(player_config, session.clone(), Box::new(NoOpVolume), move || {
        Box::new(VesperSink::new(sink_ring, sink_events))
    });
    {
        let mut rx = player.get_player_event_channel();
        let events = events.clone();
        tokio::spawn(async move {
            while let Some(e) = rx.recv().await {
                if let Some(ev) = map_player_event(e) {
                    events.send(ev);
                }
            }
        });
    }

    let mut credentials: Option<Credentials> = cache.as_ref().and_then(|c| c.credentials());
    let mut connecting = credentials.is_some();
    events.state(if connecting { Phase::Connecting } else { Phase::Idle }, "");
    info!("receiver \"{}\" ready (zeroconf port {discovery_port}, {} kbps{})", cfg.name, cfg.bitrate,
        if connecting { ", signing in with saved credentials" } else { "" });

    let mut spirc: Option<Spirc> = None;
    let mut spirc_task: Option<SpircTask> = None;
    let mut connected_at: Option<Instant> = None;
    let mut backoff = Backoff::new();
    let mut retry_at: Option<tokio::time::Instant> = None;
    let mut discovery_retry_at: Option<tokio::time::Instant> = None;
    let mut watchdog = tokio::time::interval(Duration::from_secs(15));
    watchdog.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    // Ends the current Spotify session (used before a new login and on sign-in failures).
    fn end_session(spirc: &mut Option<Spirc>, spirc_task: &mut Option<SpircTask>, session: &Session) {
        if let Some(s) = spirc.take() {
            let _ = s.shutdown();
        }
        if let Some(t) = spirc_task.take() {
            tokio::spawn(t); // let it finish shutting down on its own
        }
        if !session.is_invalid() {
            session.shutdown();
        }
    }

    let forget = |credentials: &mut Option<Credentials>| {
        *credentials = None;
        match std::fs::remove_file(&credentials_file) {
            Ok(()) => info!("saved credentials deleted"),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => warn!("couldn't delete saved credentials: {e}"),
        }
    };

    loop {
        tokio::select! {
            _ = &mut shutdown => break,

            _ = watchdog.tick() => if player.is_invalid() {
                error!("player thread ended unexpectedly; stopping the receiver");
                events.state(Phase::Failed, "player stopped");
                break;
            },

            creds = async {
                match discovery.as_mut() {
                    Some(d) => d.next().await,
                    None => None,
                }
            }, if discovery.is_some() => match creds {
                Some(c) => {
                    info!("login from the Spotify app");
                    end_session(&mut spirc, &mut spirc_task, &session);
                    credentials = Some(c);
                    backoff.reset();
                    retry_at = None;
                    connecting = true;
                }
                None => {
                    error!("zeroconf server stopped; restarting it in 10 s");
                    discovery = None;
                    discovery_retry_at = Some(tokio::time::Instant::now() + Duration::from_secs(10));
                }
            },

            _ = async {
                match discovery_retry_at {
                    Some(t) => tokio::time::sleep_until(t).await,
                    None => std::future::pending().await,
                }
            }, if discovery_retry_at.is_some() => {
                discovery_retry_at = None;
                match launch(&cfg, &session_config.client_id, discovery_port) {
                    Ok(d) => { info!("zeroconf server back on port {}", d.port()); discovery = Some(d); }
                    Err(e) => {
                        warn!("zeroconf server: {e}; retrying in 30 s");
                        discovery_retry_at = Some(tokio::time::Instant::now() + Duration::from_secs(30));
                    }
                }
            },

            _ = async {
                match retry_at {
                    Some(t) => tokio::time::sleep_until(t).await,
                    None => std::future::pending().await,
                }
            }, if retry_at.is_some() => {
                retry_at = None;
                connecting = credentials.is_some();
            },

            _ = async {}, if connecting && credentials.is_some() => {
                connecting = false;
                if session.is_invalid() {
                    session = Session::new(session_config.clone(), cache.clone());
                    player.set_session(session.clone());
                }
                events.state(Phase::Connecting, "");
                let creds = credentials.clone().unwrap_or_default();
                match Spirc::new(connect_config.clone(), session.clone(), creds, player.clone(), mixer.clone()).await {
                    Ok((s, task)) => {
                        if non_premium(&session) {
                            let _ = s.shutdown();
                            tokio::spawn(task);
                            warn!("account isn't Premium: Spotify Connect needs Premium");
                            forget(&mut credentials);
                            events.state(Phase::PremiumRequired, "");
                        } else {
                            info!("signed in; the Echo is in the account's Spotify Connect devices");
                            spirc = Some(s);
                            spirc_task = Some(Box::pin(task));
                            connected_at = Some(Instant::now());
                            events.state(Phase::Connected, "");
                        }
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        let failure = if non_premium(&session) { Failure::PremiumRequired } else { classify(&msg) };
                        if !session.is_invalid() { session.shutdown(); }
                        match failure {
                            Failure::PremiumRequired => {
                                warn!("sign-in refused: Spotify Connect needs Premium");
                                forget(&mut credentials);
                                events.state(Phase::PremiumRequired, "");
                            }
                            Failure::BadCredentials => {
                                warn!("sign-in refused: credentials no longer valid");
                                forget(&mut credentials);
                                events.state(Phase::LoginFailed, "");
                            }
                            Failure::Transient => {
                                let wait = backoff.bump();
                                warn!("sign-in failed ({msg}); retrying in {} s", wait.as_secs());
                                retry_at = Some(tokio::time::Instant::now() + wait);
                                events.state(Phase::Offline, crate::logcat::redact(&msg));
                            }
                        }
                    }
                }
            },

            _ = async {
                if let Some(t) = spirc_task.as_mut() { t.await }
            }, if spirc_task.is_some() => {
                spirc_task = None;
                spirc = None;
                events.send(Event::Stopped);
                if non_premium(&session) {
                    warn!("session ended: the account isn't Premium");
                    forget(&mut credentials);
                    events.state(Phase::PremiumRequired, "");
                } else if credentials.is_some() {
                    if connected_at.is_some_and(|t| t.elapsed() >= Backoff::STABLE) { backoff.reset(); }
                    let wait = backoff.bump();
                    warn!("Spotify connection lost; reconnecting in {} s", wait.as_secs());
                    if !session.is_invalid() { session.shutdown(); }
                    retry_at = Some(tokio::time::Instant::now() + wait);
                    events.state(Phase::Offline, "connection lost");
                } else {
                    events.state(Phase::Idle, "");
                }
                connected_at = None;
            },

            cmd = commands.recv() => match cmd {
                None => break,
                Some(cmd) => {
                    let s = spirc.as_ref();
                    let r = match cmd {
                        Command::Play => s.map(|s| s.play()),
                        Command::Pause => s.map(|s| s.pause()),
                        Command::Next => s.map(|s| s.next()),
                        Command::Previous => s.map(|s| s.prev()),
                        Command::Seek(ms) => s.map(|s| s.set_position_ms(ms)),
                        Command::LocalVolume(v) => {
                            mixer.set_local(v);
                            s.map(|s| s.set_volume(v))
                        }
                        Command::Disconnect => s.map(|s| s.disconnect(true)),
                        Command::Reconnect => {
                            if credentials.is_some() && retry_at.is_none() {
                                info!("network changed: reconnecting");
                                backoff.reset();
                                if spirc.is_some() {
                                    // Ends the Spirc task, which reconnects after FIRST (2 s).
                                    session.shutdown();
                                } else {
                                    connecting = true;
                                }
                            } else if retry_at.is_some() {
                                retry_at = Some(tokio::time::Instant::now());
                            }
                            None
                        }
                        Command::Forget => {
                            end_session(&mut spirc, &mut spirc_task, &session);
                            forget(&mut credentials);
                            retry_at = None;
                            events.send(Event::Stopped);
                            events.state(Phase::Idle, "");
                            None
                        }
                    };
                    if let Some(Err(e)) = r { warn!("command failed: {e}"); }
                }
            },
        }
    }

    info!("receiver shutting down");
    ring.close(); // releases the player thread if it's blocked in the sink
    if let Some(s) = spirc.take() {
        let _ = s.shutdown();
    }
    if let Some(t) = spirc_task.take() {
        let _ = tokio::time::timeout(Duration::from_secs(2), t).await;
    }
    if !session.is_invalid() {
        session.shutdown();
    }
    if let Some(d) = discovery.take() {
        let _ = tokio::time::timeout(Duration::from_secs(1), d.shutdown()).await;
    }
    drop(player); // joins the player thread
}

/// The zeroconf server on [port] (0 = any), advertised by Kotlin or by libmdns.
pub fn launch(cfg: &Config, client_id: &str, port: u16) -> Result<Discovery, librespot_core::Error> {
    if cfg.lib_mdns {
        launch_discovery_libmdns(&cfg.name, &cfg.device_id, client_id, port)
    } else {
        launch_discovery(&cfg.name, &cfg.device_id, client_id, port)
    }
}

pub fn launch_discovery(name: &str, device_id: &str, client_id: &str, port: u16) -> Result<Discovery, librespot_core::Error> {
    Discovery::builder(device_id.to_owned(), client_id.to_owned())
        .name(name.to_owned())
        .device_type(DeviceType::Speaker)
        .port(port)
        .zeroconf_backend(librespot_discovery::launch_external)
        .launch()
}

pub fn launch_discovery_libmdns(name: &str, device_id: &str, client_id: &str, port: u16) -> Result<Discovery, librespot_core::Error> {
    let backend = librespot_discovery::find(Some("libmdns"))?;
    Discovery::builder(device_id.to_owned(), client_id.to_owned())
        .name(name.to_owned())
        .device_type(DeviceType::Speaker)
        .port(port)
        .zeroconf_backend(backend)
        .launch()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failures_are_classified_from_librespot_messages() {
        assert_eq!(classify("Permission denied { Login failed with reason: Premium account required }"), Failure::PremiumRequired);
        assert_eq!(classify("Permission denied { Login failed with reason: Bad credentials }"), Failure::BadCredentials);
        assert_eq!(classify("Permission denied { Login failed with reason: Could not validate credentials }"), Failure::BadCredentials);
        assert_eq!(classify("Service unavailable { failed to lookup address information }"), Failure::Transient);
        assert_eq!(classify("Deadline expired before operation could complete { deadline has elapsed }"), Failure::Transient);
    }

    #[test]
    fn backoff_doubles_up_to_five_minutes_and_resets() {
        let mut b = Backoff::new();
        let waits: Vec<u64> = (0..10).map(|_| b.bump().as_secs()).collect();
        assert_eq!(waits, vec![2, 4, 8, 16, 32, 64, 128, 256, 300, 300]);
        b.reset();
        assert_eq!(b.bump().as_secs(), 2);
    }

    #[test]
    fn bitrate_setting_maps_to_librespot() {
        assert!(matches!(bitrate(96), Bitrate::Bitrate96));
        assert!(matches!(bitrate(160), Bitrate::Bitrate160));
        assert!(matches!(bitrate(320), Bitrate::Bitrate320));
        assert!(matches!(bitrate(0), Bitrate::Bitrate96));
    }

    #[test]
    fn cover_closest_to_300_wide() {
        let covers = vec![(640, "l".to_owned()), (64, "s".to_owned()), (300, "m".to_owned())];
        assert_eq!(pick_cover(&covers).as_deref(), Some("m"));
        assert_eq!(pick_cover(&[(64, "s".to_owned())]).as_deref(), Some("s"));
        assert_eq!(pick_cover(&[]), None);
    }

    /// Live check against Spotify (network; `cargo test -- --ignored live_`): the access point
    /// resolves, the handshake completes, and a login with made-up credentials is refused with
    /// a reason `classify` understands, instead of a protocol or transport error.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn live_access_point_refuses_bad_credentials() {
        crate::logcat::init();
        let session = Session::new(SessionConfig::default(), None);
        let creds = Credentials::with_password("echohome-probe-nonexistent", "not-a-password");
        let err = tokio::time::timeout(Duration::from_secs(30), session.connect(creds, false))
            .await
            .expect("AP answered within 30 s")
            .expect_err("made-up credentials must be refused");
        let msg = err.to_string();
        eprintln!("AP said: {msg}");
        assert_eq!(classify(&msg), Failure::BadCredentials, "{msg}");
    }

    /// The whole receiver without credentials: it reports Idle, serves zeroconf, and shuts down
    /// (player thread joined, ring closed) well within the time `nativeStop` allows.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn receiver_starts_idle_and_stops_cleanly() {
        let dir = std::env::temp_dir().join(format!("vesper-spotify-test-{}", std::process::id()));
        let cfg = Config {
            name: "Echo Show".into(),
            device_id: "0123456789abcdef0123456789abcdef01234567".into(),
            bitrate: 160,
            credentials_dir: dir.join("cred"),
            tmp_dir: dir.join("tmp"),
            initial_volume: 30_000,
            lib_mdns: false,
        };
        let client_id = SessionConfig::default().client_id;
        let d = launch(&cfg, &client_id, 0).unwrap();
        let port = d.port();
        let (tx, rx) = std::sync::mpsc::channel();
        let (_cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let (stop_tx, stop_rx) = oneshot::channel();
        let ring = Arc::new(PcmRing::default());
        let task = tokio::spawn(run(cfg, Some(d), port, cmd_rx, stop_rx, Events::new(tx), ring.clone()));
        let first = tokio::task::spawn_blocking(move || rx.recv_timeout(Duration::from_secs(5))).await.unwrap();
        assert!(matches!(first, Ok(Event::State { phase: Phase::Idle, .. })), "{first:?}");
        assert!(tokio::net::TcpStream::connect(("127.0.0.1", port)).await.is_ok());
        stop_tx.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(4), task).await.expect("stops within 4 s").unwrap();
        let mut out = [0i16; 4];
        assert_eq!(ring.read(&mut out, Duration::from_millis(10)), -1, "ring closed");
        let _ = std::fs::remove_dir_all(dir);
    }

    /// The patched zeroconf server answers getInfo with version 2.12.0 (what current Spotify apps
    /// ask for) on the port it reports, with no mDNS of its own.
    #[tokio::test(flavor = "multi_thread", worker_threads = 1)]
    async fn zeroconf_get_info_reports_2_12_0() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let d = launch_discovery("Echo Show", "0123456789abcdef0123456789abcdef01234567", "65b708073fc0480ea92a077233ca87bd", 0).unwrap();
        let port = d.port();
        assert_ne!(port, 0);
        let mut s = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        s.write_all(b"GET /?action=getInfo&version=2.12.0 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").await.unwrap();
        let mut body = String::new();
        s.read_to_string(&mut body).await.unwrap();
        assert!(body.contains("\"version\":\"2.12.0\""), "{body}");
        assert!(body.contains("\"remoteName\":\"Echo Show\""), "{body}");
        assert!(body.contains("\"deviceType\":\"Speaker\""), "{body}");
        assert!(body.contains("\"brandDisplayName\":\"EchoHome\""), "{body}");
        assert!(body.contains("\"modelDisplayName\":\"Echo Show\""), "{body}");
        // A malformed login answers with an error instead of panicking the server.
        let mut s = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        let form = "action=addUser&userName=x&blob=QUJD&clientKey=QUJD";
        s.write_all(format!("POST / HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{form}", form.len()).as_bytes()).await.unwrap();
        let mut resp = String::new();
        let _ = s.read_to_string(&mut resp).await;
        let mut s = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        s.write_all(b"GET /?action=getInfo HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").await.unwrap();
        let mut again = String::new();
        s.read_to_string(&mut again).await.unwrap();
        assert!(again.contains("2.12.0"), "server still up after a bad login");
        d.shutdown().await;
    }
}
