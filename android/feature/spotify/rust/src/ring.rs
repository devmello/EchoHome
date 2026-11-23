//! A small bounded s16 queue between librespot's player thread (writer) and Kotlin's audio
//! thread (reader, `nativeReadPcm` → AudioTrack).
//!
//! Unlike AirPlay's ring (the sender paces the stream, so it drops the oldest audio), librespot
//! decodes as fast as the sink takes samples, so [`PcmRing::push`] blocks while the queue is full:
//! that back-pressure is what paces playback. Keep it short (a quarter second) so pause and seek
//! feel immediate and RAM stays tiny (44 KB).

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

/// 0.25 s of 44.1 kHz stereo.
pub const CAPACITY_SAMPLES: usize = 44_100 * 2 / 4;

struct State {
    queue: VecDeque<i16>,
    /// The receiver is shutting down: writers return at once, readers get -1 once drained.
    closed: bool,
    /// Bumped by [`PcmRing::wake`] to release a blocked reader.
    wake: u64,
    /// Bumped by [`PcmRing::flush`]; a writer blocked across a flush drops the rest of its packet.
    flushes: u64,
}

pub struct PcmRing {
    state: Mutex<State>,
    cv: Condvar,
    capacity: usize,
}

impl Default for PcmRing {
    fn default() -> Self {
        Self::new(CAPACITY_SAMPLES)
    }
}

impl PcmRing {
    pub fn new(capacity: usize) -> Self {
        Self {
            state: Mutex::new(State { queue: VecDeque::with_capacity(capacity), closed: false, wake: 0, flushes: 0 }),
            cv: Condvar::new(),
            capacity,
        }
    }

    /// Appends all of `samples`, blocking while the queue is full. Returns false if the ring was
    /// closed (the rest is dropped). A [`flush`](Self::flush) meanwhile drops the rest too.
    pub fn push(&self, samples: &[i16]) -> bool {
        let mut s = self.state.lock().unwrap();
        let flushes = s.flushes;
        let mut off = 0;
        while off < samples.len() {
            if s.closed {
                return false;
            }
            if s.flushes != flushes {
                return true;
            }
            let space = self.capacity - s.queue.len();
            if space == 0 {
                // Re-checks closed / flush at least every 100 ms.
                s = self.cv.wait_timeout(s, Duration::from_millis(100)).unwrap().0;
                continue;
            }
            let n = space.min(samples.len() - off);
            s.queue.extend(&samples[off..off + n]);
            off += n;
            self.cv.notify_all();
        }
        true
    }

    /// Copies up to `out.len()` samples, waiting up to `timeout` for some. Returns the number
    /// copied, 0 on timeout or [`wake`](Self::wake), -1 when closed and drained.
    pub fn read(&self, out: &mut [i16], timeout: Duration) -> i32 {
        let deadline = Instant::now() + timeout;
        let mut s = self.state.lock().unwrap();
        let wake = s.wake;
        loop {
            if !s.queue.is_empty() {
                let n = out.len().min(s.queue.len());
                for (dst, src) in out.iter_mut().zip(s.queue.drain(..n)) {
                    *dst = src;
                }
                self.cv.notify_all();
                return n as i32;
            }
            if s.closed {
                return -1;
            }
            if s.wake != wake {
                return 0;
            }
            let now = Instant::now();
            if now >= deadline {
                return 0;
            }
            s = self.cv.wait_timeout(s, deadline - now).unwrap().0;
        }
    }

    /// Drops everything queued (pause, stop): the writer's blocked packet is dropped too.
    pub fn flush(&self) {
        let mut s = self.state.lock().unwrap();
        s.queue.clear();
        s.flushes += 1;
        self.cv.notify_all();
    }

    pub fn wake(&self) {
        let mut s = self.state.lock().unwrap();
        s.wake += 1;
        self.cv.notify_all();
    }

    pub fn close(&self) {
        let mut s = self.state.lock().unwrap();
        s.closed = true;
        s.queue.clear();
        self.cv.notify_all();
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.state.lock().unwrap().queue.len()
    }

    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn push_then_read_in_order() {
        let r = PcmRing::new(8);
        assert!(r.push(&[1, 2, 3]));
        let mut out = [0i16; 2];
        assert_eq!(r.read(&mut out, Duration::from_millis(10)), 2);
        assert_eq!(out, [1, 2]);
        assert_eq!(r.read(&mut out, Duration::from_millis(10)), 1);
        assert_eq!(out[0], 3);
        assert_eq!(r.read(&mut out, Duration::from_millis(10)), 0);
    }

    #[test]
    fn a_full_ring_blocks_the_writer_until_read() {
        let r = Arc::new(PcmRing::new(4));
        let w = {
            let r = r.clone();
            thread::spawn(move || r.push(&[1, 2, 3, 4, 5, 6]))
        };
        thread::sleep(Duration::from_millis(50));
        assert_eq!(r.len(), 4, "writer waits with the ring full");
        let mut out = [0i16; 4];
        assert_eq!(r.read(&mut out, Duration::from_millis(100)), 4);
        assert!(w.join().unwrap());
        assert_eq!(r.read(&mut out, Duration::from_millis(100)), 2);
        assert_eq!(&out[..2], &[5, 6]);
    }

    #[test]
    fn flush_drops_the_queue_and_releases_the_writer() {
        let r = Arc::new(PcmRing::new(2));
        let w = {
            let r = r.clone();
            thread::spawn(move || r.push(&[1, 2, 3, 4]))
        };
        thread::sleep(Duration::from_millis(30));
        r.flush();
        assert!(w.join().unwrap());
        assert!(r.is_empty());
    }

    #[test]
    fn close_ends_writer_and_reader() {
        let r = Arc::new(PcmRing::new(2));
        let w = {
            let r = r.clone();
            thread::spawn(move || r.push(&[1, 2, 3, 4]))
        };
        thread::sleep(Duration::from_millis(30));
        r.close();
        assert!(!w.join().unwrap());
        let mut out = [0i16; 2];
        assert_eq!(r.read(&mut out, Duration::from_millis(10)), -1);
        assert!(!r.push(&[1]));
    }

    #[test]
    fn wake_releases_a_waiting_reader() {
        let r = Arc::new(PcmRing::new(2));
        let reader = {
            let r = r.clone();
            thread::spawn(move || {
                let mut out = [0i16; 2];
                let t = Instant::now();
                (r.read(&mut out, Duration::from_secs(5)), t.elapsed())
            })
        };
        thread::sleep(Duration::from_millis(30));
        r.wake();
        let (n, took) = reader.join().unwrap();
        assert_eq!(n, 0);
        assert!(took < Duration::from_secs(2));
    }
}
