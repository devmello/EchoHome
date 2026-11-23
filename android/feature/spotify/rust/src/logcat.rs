//! `log` → logcat, tag `VesperSpotify` (the Kotlin side uses the same tag), INFO and above.
//!
//! librespot logs the account name ("Authenticated as '…'") and, at debug level, request
//! details. Every line goes through [`redact`] first, so no user name, token, blob or device id
//! reaches the log.

use std::sync::Once;

use log::{Level, LevelFilter, Log, Metadata, Record};

#[cfg(target_os = "android")]
mod sys {
    use std::os::raw::{c_char, c_int};
    #[link(name = "log")]
    unsafe extern "C" {
        pub fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
}

const TAG: &[u8] = b"VesperSpotify\0";

/// Shortest run of token-like characters that is treated as a secret.
const SECRET_MIN_LEN: usize = 24;

fn is_token_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '+' | '/' | '=' | '_' | '-' | '.' | '%')
}

/// Removes what may identify the account or authenticate it: quoted user names after
/// "Authenticated as" / "user", and any run of ≥ 24 token-like characters (access tokens,
/// base64 blobs, client tokens, device ids). Short words, numbers, hosts and track names stay.
pub fn redact(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    // 1. user names: `Authenticated as 'name'`, `user "name"`, `for user "name"`
    let mut rest = line;
    while let Some(pos) = find_user_marker(rest) {
        let (head, tail) = rest.split_at(pos.0);
        out.push_str(head);
        out.push_str(pos.1);
        let tail = &tail[pos.1.len()..];
        let mut chars = tail.chars();
        match chars.next() {
            Some(q @ ('\'' | '"')) => {
                if let Some(end) = tail[1..].find(q) {
                    out.push(q);
                    out.push_str("<user>");
                    out.push(q);
                    rest = &tail[1 + end + 1..];
                } else {
                    rest = tail;
                }
            }
            _ => rest = tail,
        }
    }
    out.push_str(rest);

    // 2. long token-like runs
    let mut result = String::with_capacity(out.len());
    let mut run = String::new();
    for c in out.chars() {
        if is_token_char(c) {
            run.push(c);
        } else {
            flush_run(&mut result, &mut run);
            result.push(c);
        }
    }
    flush_run(&mut result, &mut run);
    result
}

fn flush_run(result: &mut String, run: &mut String) {
    // A long run that is all letters (a long word) or a URL path without digits stays.
    let secret = run.chars().count() >= SECRET_MIN_LEN && run.chars().any(|c| c.is_ascii_digit());
    if secret {
        result.push_str("<redacted>");
    } else {
        result.push_str(run);
    }
    run.clear();
}

fn find_user_marker(s: &str) -> Option<(usize, &'static str)> {
    const MARKERS: [&str; 3] = ["Authenticated as ", "for user ", "user "];
    MARKERS
        .iter()
        .filter_map(|m| s.find(m).map(|i| (i, *m)))
        .min_by_key(|(i, _)| *i)
}

struct Logcat;

impl Log for Logcat {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= Level::Info
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        // android/log.h: DEBUG 3, INFO 4, WARN 5, ERROR 6
        let prio = match record.level() {
            Level::Error => 6,
            Level::Warn => 5,
            Level::Info => 4,
            Level::Debug => 3,
            Level::Trace => 2,
        };
        let target = record.target().trim_start_matches("librespot_");
        let line = redact(&format!("[{target}] {}", record.args()));
        write(prio, &line);
    }

    fn flush(&self) {}
}

pub fn write(prio: i32, line: &str) {
    let mut buf: Vec<u8> = line.bytes().filter(|&b| b != 0).collect();
    buf.push(0);
    #[cfg(target_os = "android")]
    // SAFETY: both strings are NUL-terminated and live for the call.
    unsafe {
        sys::__android_log_write(prio, TAG.as_ptr().cast(), buf.as_ptr().cast());
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (prio, TAG);
        eprintln!("{}", String::from_utf8_lossy(&buf[..buf.len() - 1]));
    }
}

pub fn init() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let _ = log::set_logger(&Logcat);
        log::set_max_level(LevelFilter::Info);
        let default_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            write(6, &redact(&format!("panic: {info}")));
            if cfg!(not(target_os = "android")) {
                default_hook(info);
            }
        }));
    });
}

#[cfg(test)]
mod tests {
    use super::redact;

    #[test]
    fn user_names_are_removed() {
        assert_eq!(redact("Authenticated as 'drh8spxczmnxeqlywln0iptuo' !"), "Authenticated as '<user>' !");
        assert_eq!(redact("Login error for user \"maya\": MAC mismatch"), "Login error for user \"<user>\": MAC mismatch");
    }

    #[test]
    fn tokens_and_blobs_are_removed() {
        let line = "token BQDx9z0aZ3kq1L2m3N4o5P6q7R8s9T0u1V2w3X4y5Z6 expires in 3600";
        assert_eq!(redact(line), "token <redacted> expires in 3600");
        assert_eq!(redact("device 2c1d4a0b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b"), "device <redacted>");
    }

    #[test]
    fn ordinary_lines_stay() {
        let line = "Connecting to AP \"ap-gew4.spotify.com:4070\"";
        assert_eq!(redact(line), line);
        assert_eq!(redact("Loading <Afterglow> with Spotify URI <spotify:track:abc>"), "Loading <Afterglow> with Spotify URI <spotify:track:abc>");
        assert_eq!(redact("<Internationalisation> (240000 ms) loaded"), "<Internationalisation> (240000 ms) loaded");
    }
}
