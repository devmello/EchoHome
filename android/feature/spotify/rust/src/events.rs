//! What the receiver tells Kotlin. Events go through a channel to one dispatcher thread
//! (`lib.rs`), the only thread that calls into the JVM.

use std::sync::mpsc;

/// The receiver's phase, `NativeBridge.PHASE_*` on the Kotlin side.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Phase {
    /// Advertised on the network, nobody signed in yet.
    Idle = 0,
    /// Signing in to Spotify with credentials from the app (or cached ones).
    Connecting = 1,
    /// Signed in: the Echo is in the account's device list.
    Connected = 2,
    /// Lost the connection to Spotify; retrying on its own.
    Offline = 3,
    /// The account isn't Premium. Cached credentials are dropped; waits for another login.
    PremiumRequired = 4,
    /// Spotify refused the credentials. Cached credentials are dropped.
    LoginFailed = 5,
    /// librespot's player thread died; the receiver has stopped. Kotlin restarts it.
    Failed = 6,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum PlayState {
    Paused = 0,
    Playing = 1,
    Loading = 2,
}

#[derive(Debug)]
pub enum Event {
    State { phase: Phase, detail: String },
    /// The Spotify app that controls the Echo, e.g. "Maya's iPhone".
    Client(String),
    Track { title: String, artist: String, album: String, duration_ms: u32, cover_url: String },
    Playback { state: PlayState, position_ms: u32 },
    /// Position jumped (seek, correction) without a play-state change.
    Position(u32),
    /// Nothing loaded any more (another device took over, or playback was stopped).
    Stopped,
    /// The Spotify app's volume, 0..=65535.
    Volume(u16),
    /// librespot opened (true) or closed (false) its audio output.
    Sink(bool),
}

#[derive(Clone)]
pub struct Events(mpsc::Sender<Event>);

impl Events {
    pub fn new(tx: mpsc::Sender<Event>) -> Self {
        Self(tx)
    }

    pub fn send(&self, e: Event) {
        let _ = self.0.send(e);
    }

    pub fn state(&self, phase: Phase, detail: impl Into<String>) {
        self.send(Event::State { phase, detail: detail.into() });
    }
}
