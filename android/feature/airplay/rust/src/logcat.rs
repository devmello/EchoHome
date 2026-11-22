//! `tracing` → logcat (tag `VesperAirPlayRs`). INFO and above only, to keep the log quiet.

use std::io::Write;
use std::sync::Once;

use tracing::{Level, Metadata};
use tracing_subscriber::fmt::MakeWriter;

#[cfg(target_os = "android")]
mod sys {
    use std::os::raw::{c_char, c_int};
    #[link(name = "log")]
    unsafe extern "C" {
        pub fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
}

const TAG: &[u8] = b"VesperAirPlayRs\0";

pub struct LogLine {
    prio: i32,
    buf: Vec<u8>,
}

impl Write for LogLine {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.buf.extend_from_slice(data);
        Ok(data.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl Drop for LogLine {
    fn drop(&mut self) {
        while self.buf.last() == Some(&b'\n') {
            self.buf.pop();
        }
        if self.buf.is_empty() {
            return;
        }
        self.buf.retain(|&b| b != 0);
        self.buf.push(0);
        #[cfg(target_os = "android")]
        // SAFETY: both strings are NUL-terminated and live for the call.
        unsafe {
            sys::__android_log_write(self.prio, TAG.as_ptr().cast(), self.buf.as_ptr().cast());
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (self.prio, TAG);
            eprintln!("{}", String::from_utf8_lossy(&self.buf[..self.buf.len() - 1]));
        }
    }
}

pub struct Logcat;

impl<'a> MakeWriter<'a> for Logcat {
    type Writer = LogLine;
    fn make_writer(&'a self) -> LogLine {
        LogLine { prio: 4, buf: Vec::with_capacity(160) }
    }
    fn make_writer_for(&'a self, meta: &Metadata<'_>) -> LogLine {
        // android/log.h: VERBOSE 2, DEBUG 3, INFO 4, WARN 5, ERROR 6
        let prio = match *meta.level() {
            Level::ERROR => 6,
            Level::WARN => 5,
            Level::INFO => 4,
            Level::DEBUG => 3,
            Level::TRACE => 2,
        };
        LogLine { prio, buf: Vec::with_capacity(160) }
    }
}

pub fn init() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let _ = tracing_subscriber::fmt()
            .with_writer(Logcat)
            .with_ansi(false)
            .without_time()
            .with_level(true)
            .with_target(false)
            .with_max_level(Level::INFO)
            .try_init();
    });
}
