# Spotify Connect receiver (`:feature:spotify`)

Branch `worktree-agent-a7135fa0f925becc2`. Spec: NOTES §6 (Spotify row). Not yet run on the Echo.

## Library decision

**librespot 0.8.0** (crates.io, released Nov 10, 2025, the latest release in Nov 2026), MIT. It's
the only maintained open Spotify Connect implementation, and 0.8.0 is the release that works with
Spotify's 2025+ servers: the login5 / OAuth token path, the "dealer" websocket Connect state
(the old Mercury `hm://remote` path is gone server-side), and the spclient metadata.

- `rust/` is a small cdylib (`vesper_spotify`) over JNI. It uses the librespot sub-crates directly
  (core, connect, playback, metadata, discovery) with `default-features = false`: no ALSA, Pulse,
  rodio, GStreamer or PortAudio backends, no OpenSSL. TLS is rustls with ring and webpki-roots.
  Every version is pinned in `Cargo.lock` (`build.sh` builds with `--locked`).
- `librespot-core` and `librespot-discovery` are vendored with small patches
  (`rust/vendor/VESPER-PATCH.md`): zeroconf version 2.12.0 (current Spotify apps send an empty
  login blob to 2.9.0, upstream PR #1847), a non-Premium account ends the session instead of
  `process::exit(1)` (it would kill the HOME app, upstream PR #1855), a short blob can't panic the
  zeroconf server, an "external" mDNS backend so NsdManager advertises, and no vergen build step.
- `libvesper_spotify.so` is **4.0 MB**, armeabi-v7a, API 30, committed under
  `src/main/jniLibs/armeabi-v7a/` like `libvesper_airplay.so` (which is tracked in git too), so
  the app builds without Rust. It needs only liblog, libdl, libm and libc. Rebuild:
  `android/feature/spotify/rust/build.sh` (`cargo test`, then `cargo ndk`).

## Done

- **Discovery:** the zeroconf HTTP server (`getInfo` / `addUser`) on port **4070** (any port if
  it's taken), advertised by Kotlin through `NsdManager` as `_spotify-connect._tcp` with TXT
  `VERSION=1.0`, `CPath=/` under `spotify.name`, or `device.name` ("Echo Show") when that's blank.
  No MulticastLock in that mode. `spotify.mdns = lib` (advanced) switches to librespot's own
  libmdns responder and takes a MulticastLock while it runs. The device id is a random 40-hex id in
  `spotify.deviceId`; device type Speaker.
- **Sign-in:** the Spotify app sends its login blob over zeroconf; librespot exchanges it for
  reusable credentials, cached in `noBackupFilesDir/spotify/credentials.json` (not backed up, not
  in git). After a restart the Echo signs in from the cache and shows in the account's device list
  without the app. Settings › Spotify › "Sign out of Spotify" deletes the cache.
- **Premium:** a free account gets phase `PremiumRequired`: the cache is dropped, a banner
  ("Spotify Premium is needed…") shows once, the settings status says so, and the receiver goes
  back to waiting for a login. Nothing crashes. A refused login (`LoginFailed`) also drops the cache.
- **Audio:** a custom librespot `Sink` converts to s16 into a 0.25 s ring; the ring's back-pressure
  paces the decoder, so nothing buffers past that (librespot itself buffers the compressed file).
  A `spotify-audio` thread reads it into an `AudioTrack` (USAGE_MEDIA, STREAM_MUSIC, 44.1 kHz
  stereo), created when librespot opens the sink and released after 30 s of silence. Bitrate
  `spotify.bitrate` 96 or **160** (default) kbps, Ogg Vorbis. Normalisation off.
- **MediaHub:** `services.media.publish("spotify", MediaState(source = SPOTIFY, sourceLabel =
  the controlling app, e.g. "Maya's iPhone"), controls)` with title, artist (all artists, or the
  show for podcasts), album, duration, position (extrapolated while playing, held while loading),
  playing, volume and cover art (Spotify's ~300 px image, fetched over HTTPS, decoded ≤ 600 px).
  Route chips: ["Spotify Connect", "160 kbps", client]. Cleared when another device takes over or
  the session ends. Now Playing and the island's music activity come from this (no Spotify-specific
  island state; no contract change).
- **Controls back to Spotify:** play, pause, next, previous, seek and volume go to librespot's
  Spirc, so the phone's Spotify app follows. `canSeek`, `canSkip`, `canPause` = true.
  `disconnect()` drops the session but stays signed in (the Echo stays in the device list).
- **Volume:** Spotify's slider sets the STREAM_MUSIC index, and the Echo's volume keys / Control
  Center slider report back to Spotify. `VolumeSync` ignores the echo of our own change in both
  directions, so the two don't fight. The AudioTrack runs at gain 1 (no double attenuation);
  the custom mixer doesn't scale samples.
- **One source at a time:** the receiver takes audio focus (AUDIOFOCUS_GAIN) when it starts playing,
  so AirPlay (which uses the same focus rules) and other apps pause, and losing focus pauses
  Spotify through Spirc (the phone shows paused). Transient loss (assistant, timer) ducks to 0.2
  and comes back. For sources that can start without focus (the Bluetooth speaker, an AirPlay 2
  sender before PCM flows) the service also watches the hub: when Spotify starts it pauses
  whichever of them is playing (through their `MediaControls`), and when one of them starts it
  pauses Spotify (`Arbitration`, unit-tested).
- **Control Center:** the Spotify tile (`system.cc.spotify`, default now on) is lit while the
  receiver is on or a Spotify session is in the hub, and its label is "Playing" / "Paused" during
  a session. Tapping it opens Now Playing during a session, otherwise turns the receiver on/off.
- **Settings page** (section "Spotify", order 31): receiver on/off (`spotify.enabled`), name,
  "Streaming quality" (96 / 160 kbps), discovery (advanced), "Sign out of Spotify", and help text
  that starts with the live status ("Ready…", "Connected. Playing from …", the Premium message)
  and says Premium is needed. Changes apply live (name, bitrate and mDNS restart the receiver).
- **Errors:** network loss → phase Offline, the native side reconnects with backoff (2 s doubling
  to 5 min, reset after 60 s connected); Android's network callback also triggers a reconnect and
  re-announces NSD when Wi-Fi comes back. A dead player thread (librespot's 15 s watchdog) →
  phase Failed → the service restarts the receiver, at most 3 times. **Crash-loop guard:** the
  start counter is persisted before each start and cleared once the receiver has been up 60 s; after
  3 starts that never got there the receiver stays off with "Turned off after failing to start"
  until it's switched off and on. Every JNI entry point catches Rust panics.
- **Logs:** tag `VesperSpotify` (native and Kotlin), INFO and up. The native bridge redacts user
  names and anything token-like (runs of 24+ characters with a digit). Credentials, tokens and
  blobs are never logged.

## Tests

- `cargo test` (16 pass, 1 ignored): ring, sink conversion, mixer, error classification, backoff,
  bitrate, cover choice, redaction, a live zeroconf getInfo / malformed addUser test on localhost,
  and the receiver starting idle and stopping cleanly. Ignored live test (`cargo test -- --ignored
  live_access_point`): connects to Spotify's access point with bad credentials. **Ran Nov 23,
  2026: the AP handshake works and Spotify answers "Bad credentials"**, which `classify` maps to
  LoginFailed. So the protocol still talks to today's servers.
- JVM: `SpotifyStateTest` (state → MediaState, positions, loading, track changes, phases, status
  text), `SpotifyPolicyTest` (volume mapping and sync, focus policy, arbitration, crash guard),
  `SpotifySettingsTest` (keys, defaults, bitrate, name fallback), and `:feature:system`
  `SpotifyTileTest` (tile state, label). `./gradlew testDebugUnitTest` passes.
- `./gradlew :app:assembleRelease` builds (APK 18.3 MB); R8 keeps the JNI names (checked with
  dexdump).

## Left / unverified (nothing here has run on the Echo)

- A real login from the Spotify app (Android and iOS) over NSD, and the cached-credential sign-in
  after a reboot.
- Playback with a Premium account. **Known Spotify-side risk (librespot issue #1649):** some
  accounts get "audio key error" for every track with librespot; it isn't something this code
  can fix. If logcat shows `audio key` errors, try another account.
- That the Premium check is right for Family / Duo / Student plans (librespot checks the account
  `type` attribute, "premium" for all of them).
- Audio focus with AirPlay and Bluetooth on the device, volume sync with the hardware keys, the
  banner and the settings status on screen, Night mode (no new UI is drawn here; the tile and Now
  Playing are existing views).
- RAM and CPU: expect a few MB of native heap (tokio with 2 workers, the 0.25 s ring, the
  compressed file buffer). Measure with `scripts/echo-perf.sh` while playing.

## Steps on the Echo (lead)

```bash
./scripts/build-install.sh
./platform-tools/adb -s "$(cat .echo-serial)" logcat -s VesperSpotify
```

1. Expect `receiver up as "Echo Show" (160 kbps, mDNS NsdManager)`.
2. From the Mac: `curl "http://<echo-ip>:4070/?action=getInfo"` returns JSON with
   `"version":"2.12.0"` and `"remoteName":"Echo Show"`.
3. On a phone on the same Wi-Fi, signed in to a **Premium** account: Spotify › devices › "Echo
   Show". Expect phase `Idle → Connecting → Connected`, a `track:` line, and sound.
4. Check Now Playing (title, cover, progress), pause/skip/seek from the Echo (the phone follows),
   volume keys (the phone's slider moves) and the phone's slider (the Echo's volume HUD moves).
5. Start AirPlay while Spotify plays (Spotify pauses), then Spotify again (AirPlay pauses).
6. State dump: `adb shell am broadcast -a app.vesper.spotify.DEBUG --ez dump true` (debug builds;
   also `--es cmd pause|play|next|previous|disconnect|reconnect|forget`, `--ef volume 0.5`).
7. A free account should show the Premium banner and no crash.

## Needs from the user

- A Spotify Premium account on a phone on the Echo's Wi-Fi for the test above.
