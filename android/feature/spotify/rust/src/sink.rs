//! librespot's audio output and volume, both handed to Kotlin.
//!
//! - [`VesperSink`] converts the player's f64 samples to s16 (librespot's own converter and
//!   ditherer) into the [`PcmRing`]; Kotlin's audio thread plays it on an AudioTrack.
//! - [`VesperMixer`] doesn't scale the audio. The volume the Spotify app sets is the Echo's
//!   speaker volume (like AirPlay and Bluetooth absolute volume), so it is reported to Kotlin,
//!   which sets STREAM_MUSIC; the Echo's own keys come back through [`VesperMixer::set_local`].

use std::sync::Arc;
use std::sync::atomic::{AtomicU16, Ordering};

use librespot_core::Error;
use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::{Mixer, MixerConfig};

use crate::events::{Event, Events};
use crate::ring::PcmRing;

pub struct VesperSink {
    ring: Arc<PcmRing>,
    events: Events,
    running: bool,
}

impl VesperSink {
    pub fn new(ring: Arc<PcmRing>, events: Events) -> Self {
        Self { ring, events, running: false }
    }
}

impl Sink for VesperSink {
    fn start(&mut self) -> SinkResult<()> {
        if !self.running {
            self.running = true;
            self.events.send(Event::Sink(true));
        }
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        // Pause / stop: drop what's queued so the Echo goes quiet at once (the AudioTrack is
        // paused and flushed on the Kotlin side when it sees Sink(false)).
        self.ring.flush();
        if self.running {
            self.running = false;
            self.events.send(Event::Sink(false));
        }
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        if let AudioPacket::Samples(samples) = packet {
            let s16 = converter.f64_to_s16(&samples);
            // Blocks while the ring is full: this paces the decoder. False = shutting down.
            self.ring.push(&s16);
        }
        Ok(())
    }
}

pub struct VesperMixer {
    volume: AtomicU16,
    events: Option<Events>,
}

impl VesperMixer {
    pub fn new(initial: u16, events: Events) -> Self {
        Self { volume: AtomicU16::new(initial), events: Some(events) }
    }

    /// The Echo's own volume changed (keys, Control Center): remember it without reporting it
    /// back to Kotlin, so Spirc sends the right level when it next syncs.
    pub fn set_local(&self, volume: u16) {
        self.volume.store(volume, Ordering::Release);
    }
}

impl Mixer for VesperMixer {
    fn open(_config: MixerConfig) -> Result<Self, Error> {
        Ok(Self { volume: AtomicU16::new(u16::MAX / 2), events: None })
    }

    fn volume(&self) -> u16 {
        self.volume.load(Ordering::Acquire)
    }

    fn set_volume(&self, volume: u16) {
        let old = self.volume.swap(volume, Ordering::AcqRel);
        if old != volume {
            if let Some(e) = &self.events {
                e.send(Event::Volume(volume));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    #[test]
    fn remote_volume_is_reported_once_and_local_changes_are_silent() {
        let (tx, rx) = mpsc::channel();
        let m = VesperMixer::new(1000, Events::new(tx));
        m.set_volume(1000);
        assert!(rx.try_recv().is_err(), "same level: nothing to report");
        m.set_volume(40_000);
        assert!(matches!(rx.try_recv(), Ok(Event::Volume(40_000))));
        m.set_local(20_000);
        assert_eq!(m.volume(), 20_000);
        assert!(rx.try_recv().is_err());
        m.set_volume(20_000);
        assert!(rx.try_recv().is_err(), "Spirc echoing the local level back reports nothing");
    }

    #[test]
    fn stop_flushes_and_reports_once() {
        let (tx, rx) = mpsc::channel();
        let ring = Arc::new(PcmRing::new(16));
        let mut sink = VesperSink::new(ring.clone(), Events::new(tx));
        sink.start().unwrap();
        sink.start().unwrap();
        ring.push(&[1, 2, 3]);
        sink.stop().unwrap();
        sink.stop().unwrap();
        assert!(ring.is_empty());
        let got: Vec<_> = rx.try_iter().collect();
        assert!(matches!(got.as_slice(), [Event::Sink(true), Event::Sink(false)]));
    }
}
