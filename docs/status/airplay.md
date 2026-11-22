# Agent C: AirPlay audio (`:feature:airplay`)

Branch `worktree-agent-a4eccbdec392d14ec`. Spec: NOTES §6 (AirPlay receiver, audio only).

## Library decision

**shairplay (first choice in NOTES §6) works, so I didn't switch.** `shairplay` 0.13.0 on crates.io
is a pure-Rust rewrite (github.com/metaneutrons/shairplay-rust), LGPL-3.0-or-later, AirPlay 1 + 2
audio, video only behind the opt-in `video` feature. It cross-compiled for `armv7-linux-androideabi`
with cargo-ndk on the first try (no C deps; needs only liblog/libc/libm/libdl). Built with
`features = ["ap2"]` + the default `fairplay`; `video`/`hls` are off, so no mirroring code paths.

- `rust/` is a small cdylib (`echo_airplay`) wrapping it over JNI. `libecho_airplay.so` is
  **2.2 MB**, armeabi-v7a only, committed under `src/main/jniLibs/` so the app builds without
  Rust. Rebuild: `android/feature/airplay/rust/build.sh` (cargo test + cargo ndk).
- The library's own mDNS responder is switched off (it skips registration when `CI` is set,
  which the wrapper sets in-process). Kotlin advertises `_raop._tcp` + `_airplay._tcp` through
  Android's `NsdManager` instead, with the exact TXT records the library reports: the system
  daemon answers queries, so the app does no mDNS work while idle. `--es mdns lib` switches back
  to the library's responder (untested on the device).

## Done

- **Receiver**: AirPlay 2 by default (`airplay.mode = ap2`), AirPlay 1 selectable (`ap1`).
  Advertised as **"Echo Bedside"** (`airplay.name`), port 7000, stable MAC-style id in
  `airplay.hwaddr`, AP2 identity seed + paired peers persisted in `files/airplay/` (so iPhones
  don't re-pair after restarts). Transient pairing (no PIN), like shairport-sync.
- **Foreground service** `AirPlayService` (`foregroundServiceType="mediaPlayback"`, declared in the
  module manifest, IMPORTANCE_MIN notification). Started from `AirPlayFeature.install`; settings
  changes (`airplay.enabled|name|mode|mdns`) restart / stop it. All native events go to one
  `airplay-events` HandlerThread that owns the state.
- **Audio**: native side converts f32 → s16 into a bounded ring (1.5 s, drops oldest if the
  AudioTrack stalls); `AudioPlayer` pulls it on an `airplay-audio` thread (URGENT_AUDIO) into an
  `AudioTrack` (USAGE_MEDIA / CONTENT_TYPE_MUSIC, i.e. STREAM_MUSIC). The thread exists only while
  a stream is set up. Flush on sender FLUSH; "paused" = no PCM for 1 s (AP2 pauses that way).
- **Volume from the sender**: dB → AudioTrack gain (10^(dB/20), -144 = mute) and
  `MediaState.volume` = (dB+30)/30. Device volume (agent B's keys) stays separate and multiplies.
- **MediaHub**: `services.media.publish("airplay", MediaState(…, source = AIRPLAY, sourceLabel =
  sender name), controls)` with title / artist / album / artwork (decoded ≤ 600 px) / duration /
  position (from RAOP `progress`, extrapolated) / playing / volume; cleared when the sender leaves.
  The sender name comes from its `_companion-link._tcp` mDNS name (e.g. the Mac showed up with its
  real name), falling back to reverse DNS, then "AirPlay".
- **Island**: `LiveActivity("airplay", AIRPLAY_CONNECTED, AirPlayConnected(senderName))` while a
  sender is connected (after 3 s or when audio starts, so picker probes of `GET /info` don't
  flash it), ended on disconnect.
- **MediaControls**: AirPlay 1 → DACP (play/pause, next, previous, volume) through the library.
  AirPlay 2 → play/pause become a local mute/unmute (shown as paused), next/previous no-ops: a
  third-party AP2 receiver can't control the sender (library's AP2-STATUS.md; same for every
  open-source receiver). `canSeek = false` (no DACP seek in the library).
- **Audio focus / system**: AUDIOFOCUS_GAIN requested when PCM starts, abandoned when the stream
  ends; transient loss mutes (and pauses the sender over DACP when possible, resuming on gain);
  ducking left to the system. Partial wake lock + `WIFI_MODE_FULL_HIGH_PERF` lock only while a
  stream is set up; a multicast lock only while a sender is connected (DACP discovery). Wi-Fi
  coming back re-announces mDNS.
- **Unit tests** (`./gradlew :feature:airplay:testDebugUnitTest`): dB/volume/gain/DACP mapping,
  RTP progress incl. u32 wrap, metadata/artwork/progress ordering across track changes, pause
  freezes position, local pause, multi-connection senders, MediaState mapping, TXT parsing,
  host-name prettifying. Rust: `cargo test` (ring buffer, pairing store).
- Debug hook (debug builds): `adb shell am broadcast -a com.echobedside.airplay.DEBUG` with
  `--es mode ap1|ap2`, `--es mdns nsd|lib`, `--es name …`, `--es cmd play|pause|next|previous`,
  `--ef volume 0.5`, `--ez dump true` (logs state + hub to `EchoAirPlay`).

## Measured on the Echo (debug build, Nov 22 2026)

Sender: pyatv 0.18 on the Mac (same Wi-Fi), `stream_file` of 44.1 kHz MP3s with tags + 600×600
cover. pyatv negotiates **AirPlay 2 realtime (type 96, ALAC over ChaCha20, transient pairing)**.

**Release build** (`build-install.sh --release --perf 20`, then `echo-perf.sh` while streaming), only
this branch's features (StandBy digital face on screen):

```
### Perf 2026-11-22 04:46 (20 s, 0.1.0, release)        ← idle, receiver advertised
- PSS total: 21.5 MB (Java 1.5 MB, Native 5.8 MB, Graphics 2.3 MB)
- Device MemAvailable: 308 MB
- CPU: 0.1% of one core (avg over 20s)
### Perf 2026-11-22 04:46 (30 s, 0.1.0, release)        ← streaming (pyatv, AP2 realtime ALAC)
- PSS total: 25.4 MB (Java 3.7 MB, Native 6.7 MB, Graphics 2.5 MB)
- CPU: 3.2% of one core (avg over 30s)
### Perf after stream (15 s)                            ← sender gone
- PSS total: 25.3 MB, CPU: 0.0% of one core
```

Debug build, per thread (`/proc/<pid>/task/*/stat` over 15 s while streaming):

| | Whole app CPU | Receiver threads | audioserver | PSS (whole app) |
|---|---|---|---|---|
| Idle, receiver advertised | **0 ticks in 20 s (0 %)** | — (2 tokio workers parked in epoll) | — | 33.2 MB |
| Streaming, AP2 realtime ALAC | **3.9 %** of one core | tokio worker 2.5 % (decrypt + ALAC), `airplay-audio` 1.2 % | 4.1 % (44.1 → 48 kHz mixer) | 37.0 MB (+3.8) |
| Streaming, AP1 (`--es mode ap1`) | **1.9 %** | tokio worker 1.1 % | 3.0 % | 36.1 MB (+3.4) |

- So the receiver costs ~3–4 % of one A53 core plus ~3–4 % in audioserver (four cores = 400 %),
  ~4 MB PSS while streaming, nothing while idle. Native heap 6 MB idle → 7.5 MB streaming.
  The `.so` adds 2.2 MB to the APK.
- Advertising: the Mac sees `76A341AF9034@Echo Bedside._raop._tcp` and `Echo Bedside._airplay._tcp`
  within ~1 s of the service starting (`dns-sd -B`); pyatv's scan finds it in 1.0 s.
- Connect → first PCM: ~0.45 s (pair-setup ~0.3 s). Receiver start: 7–23 ms.
- Metadata, artwork (600×600) and duration reached MediaHub (`hub:` lines in logcat, tag
  `EchoAirPlay`), track change A → B swapped text + art correctly, 0 samples dropped. Example:
  `hub: "Test Tone A" / "Echo Agent C" / "Bedside Sessions" art=600x600 pos=0/120000 playing=true from="<the Mac's name>" vol=0.33`
- AirPlay 1 mode also streams with metadata + artwork (pyatv needs a test-only shim: shairplay
  answers SETUP with `Session: DEADBEEF`, which iOS accepts and pyatv doesn't). The sender's
  DACP-ID arrives ("DACP remote control available"), but pyatv runs no DACP server, so commands
  aren't verified; the library also spends 2 s browsing for `_dacp._tcp` during SETUP when the
  sender doesn't advertise one (iOS / Music do).
- The release build (R8) works: the consumer ProGuard rules keep the JNI names.
- pyatv closes the connection between tracks, so the hub clears and the island ends between them;
  iOS / macOS keep the session open across tracks.

### Latency / sync notes
- Playback is "as soon as decoded": the library has no PTP/NTP-anchored playout yet (its
  AP2-STATUS.md: PTP offsets "not wired"), and the PTP sink can't bind ports 319/320 as an app
  (`Permission denied`), so there's no multi-room sync and we play ahead of the sender's clock
  by roughly (sender latency − ours). Ours ≈ AudioTrack buffer 19 116 frames (433 ms at 44.1 kHz,
  the device's minimum ×2) + whatever sits in the ring. Fine for music; lip-sync with video on the
  phone will be off, and grouping with other AirPlay speakers won't be in sync.
- Wi-Fi jitter is absorbed by the sender's retransmits (AP1/realtime) or the library's playout
  buffer (AP2 buffered) plus our ring.

## Left

- **iPhone test (AirPlay 2 buffered, type 103 AAC)**: the iOS Music / Spotify path. pyatv only
  exercises type 96. Needs an iPhone on the same Wi-Fi (see Needs from the user).
- **AirPlay 1 + DACP from a real Apple sender**: pyatv doesn't run a DACP server, so the remote
  path is untested end to end; the library also stalls SETUP ~2 s browsing `_dacp._tcp` when the
  sender has none.
- Contract request (docs/contract-requests.md): `canSkip` / `canPause` on MediaControls so Now
  Playing can hide buttons that do nothing for AirPlay 2; and D's `disconnect()` + `route` chips,
  which C can fill once they land.
- No Android `MediaSession` is published on purpose: `:feature:media` reads other apps' sessions
  through MediaSessionManager and would see ours as a duplicate; the hub is the one place.

## Open questions

- Default mode: **AP2** (as the brief says; iOS lists it as an AirPlay 2 speaker, buffered audio
  rides out Wi-Fi better). The trade-off is no remote control. AP1 gives DACP play/pause/skip
  from Now Playing but iOS shows it as a classic speaker. Worth deciding after the iPhone test.
- Should the Echo's hardware volume keys change the sender's AirPlay volume while AirPlay is the
  current session (like a HomePod), or only the device volume? (agent B / lead)
- FairPlay: the `fairplay` feature (default) has a provenance note upstream
  (`docs/fairplay-provenance.md`: earlier releases were a port of GPL code). It's needed for some
  AP2 senders. Fine for a personal device; flag if this ever ships.

## Needs from the user

- An **iPhone on the same Wi-Fi**: AirPlay to "Echo Bedside" from Control Center / Music for a
  minute (AP2 buffered path), ideally once more after `adb shell am broadcast -a
  com.echobedside.airplay.DEBUG --es mode ap1` (AP1 + DACP remote), then back with `--es mode ap2`.

## Device setup

- None. No grants, no /system changes. Runtime: normal permissions only (INTERNET,
  FOREGROUND_SERVICE, WAKE_LOCK, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE,
  CHANGE_WIFI_MULTICAST_STATE), all install-time.

## Toolchain installed on the Mac

- Homebrew rustup was already present (stable 1.96); added target `armv7-linux-androideabi`.
- `cargo install cargo-ndk` (v4.1.2).
- NDK `29.0.14206865` via `sdkmanager --sdk_root=$ANDROID_HOME`.
- Test-only, in the session scratchpad (not in the repo): Python venv with `pyatv` 0.18 (MIT) and
  `srptools` 1.0.1 (BSD-3; pyatv 0.18 breaks with srptools 2.0).

## Licences (everything linked into libecho_airplay.so)

| Crate | Licence |
|---|---|
| shairplay 0.13.0 | LGPL-3.0-or-later |
| echo_airplay (ours, links shairplay statically) | LGPL-3.0-or-later |
| symphonia 0.6 (AAC decode, AP2) | MPL-2.0 |
| curve25519-dalek, ed25519-dalek, x25519-dalek, subtle | BSD-3-Clause |
| jni 0.21, tokio, tracing, tracing-subscriber, rsa, aes, sha2, chacha20poly1305, rubato, plist, mdns-sd, rand, … (~120 crates) | MIT and/or Apache-2.0 |
| if-addrs | MIT OR BSD-3-Clause |
| zerocopy, bytemuck, unicode-ident | permissive multi-licence (BSD-2/Zlib/Unicode-3.0 options) |

LGPL note: we link shairplay statically into our `.so`. For a personal device that's fine; if the
app is ever distributed, ship the wrapper's source (it's in this repo) so the library can be
relinked, which LGPL-3 asks for. Full list: `cd android/feature/airplay/rust && cargo tree -e normal --format '{p} {l}'`.
