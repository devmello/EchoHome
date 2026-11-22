# Status: media (agent D, `:feature:media`)

Branch `worktree-agent-af29799b3bed8abf4`, rebased on main `4ecdc78` (contracts round 1, AirPlay, settings contract, quiet hours).

## Done

- **Now Playing screen** (`ScreenSpec("nowplaying", order = 10)`, `NowPlayingScreen.kt` + `NowPlayingViews.kt`), 1:1 with `design/NowPlaying.dc.html`:
  - Reads `services.media.current`; title / "Artist — Album" ellipsized to the column; source line ("AirPlay from …", "Playing from <app>", "Spotify Connect"); 12 h clock (24 h if the system says so), updated on the minute.
  - Ambient background: base colour + 3 pre-blurred blobs (ALPHA_8 masks at 1/8 size, blurred once, coloured by the paint so a palette change never re-blurs) + the dark shade. Motion = the design's drift formulas, stepped once a second with 1 s linear transitions. Rendered into a **240×120 hardware layer upscaled 4×** (the content is blurred ≥ 80 px, so it's visually identical and costs one full-screen quad instead of ~2 M px of overdraw).
  - Palette from the artwork (`Palette.kt`): my own median-cut quantiser (no AndroidX) + Palette-style roles (A = dark vibrant, B = vibrant, C = light vibrant, base = A's hue at 8.5 % lightness), clamped to the saturation / lightness bands of the designer's palettes. 1.2 s ease crossfade between tracks. Unit-tested against the design's own `base/a/b/c` for its three sample covers.
  - Live progress extrapolated from `MediaState.positionNow()`; tap/drag the scrubber to seek (only when `controls.canSeek`); elapsed / "-remaining" labels.
  - Transport: plain glyphs at rest that **lift into glass while touched** (opacity 0.18 s ease, scale 0.7→1 with `Motion.SPRING_BOUNCY`, as the design). Previous / play-pause / next go to `session.controls`.
  - Art shrinks to 0.86 when paused with the design's `cubic-bezier(0.3, 1.35, 0.5, 1)` 0.55 s (= `Motion.SPRING_SOFT`; the brief said `Motion.SPRING`, but the design file is the spec).
  - Glass volume capsule (the design's control, not `GlassSlider`): tap or drag. Uses the sender's volume when `MediaState.volume` is set (AirPlay, remote sessions), otherwise `systemControls.setVolume(v, showHud = false)`; follows hardware-key changes with the design's 0.25 s ease.
  - AirPlay button + **glass popover that pops out of the button** (spring scale from the button centre). Its backdrop is a blurred snapshot of the whole screen taken at open time (24 px, brightness .95); everything on it is plain fills (no glass on glass). Tap outside closes. Chips = `MediaState.route` (the demo carries the design's "AirPlay 2 / Lossless · ALAC / 44.1 kHz"). Disconnect = `controls.disconnect()` (contracts round 1): C drops the AirPlay sender; app sessions `transportControls.stop()`; the demo goes back to the empty state.
  - `canSkip` / `canPause` (round 1): previous/next, and pause while playing, are **dimmed to 30 % and inert** when the source can't do them (AirPlay 2 senders); the island's actions leave them out. App sessions derive them from `PlaybackState.actions`.
  - Glass controls sample a pre-blurred snapshot of the ambient layer (`PreBlur.blur`, rebuilt only when the palette changes). No live blur anywhere.
  - **Empty state**: black, static, no frames: AirPlay glyph, "Nothing playing", "AirPlay to <device name> from your iPhone, iPad or Mac", clock.
  - Night mode (`system.state.nightActive`): black background, no blobs or motion, art mapped to monochrome red, all text/glyphs `#BE180E`, glass as red outlines.
  - Everything stops in `onScreenHidden` (ticker, minute tick, crossfade, art spring, popover). Paused: the drift settles after 30 s, then zero frames.
- **Demo source** (`DemoMedia.kt`): with `config.demoData` and no real session, runs core's `FakeMediaSource` through a hub wrapper that adds procedural artwork (`DemoArt`: the design's three covers redrawn with gradients, rings and ripples; no real album art) and advances to the next track at the end. Stops as soon as a real session appears; Disconnect keeps it off until restart.
- **Music island** (`MusicIsland.kt`): publishes / updates / ends the `MUSIC` `LiveActivity` (`id = "media.music"`, `ownerScreen = "nowplaying"`, content `LiveContent.Music`, actions previous / play-or-pause / next) from `services.media.current`, only on identity / play-state / artwork changes. Paused music goes stale after 10 min. Rendering is B's.
- **Other apps' media** (`AppSessions.kt`): own `MediaListenerService` (media only; ignores notifications) → `MediaSessionManager.getActiveSessions` + `OnActiveSessionsChangedListener`; every controller (except our own package) is published as `OTHER_APP` with working `MediaControls` (play/pause/next/previous/seek, remote volume or device volume) and artwork (metadata bitmap or art URI, centre-cropped to 324 px off the main thread, decoded once per track). Source line shows the app's label ("Playing from Music"; the module's `<queries>` makes launchable apps visible on Android 11); MediaStore's `<unknown>` tags are treated as missing. Mapping is a pure function (`SessionMapping.kt`) with unit tests. **Verified on the Echo** (05:01): a track played in Eleven shows with Eleven's own cover and its palette, and our pause button paused Eleven's session (`state=3` → `state=2`).
- **Bluetooth** (main 95a9ae8, `MediaSourceKind.BLUETOOTH` from `:feature:phone`): "Bluetooth from <phone>" with a Bluetooth glyph, the route button shows the Bluetooth glyph, the popover reads BLUETOOTH / "<phone>" / "Connected to <device name>" / chip "Bluetooth" / Disconnect. Missing artwork → a procedural cover from the album/title (also any source without art; only the last 3 are kept, 420 KB each). Seek is off when `controls.canSeek` is false. `com.android.bluetooth` sessions are not mirrored (`SessionMapping.isMirrored`), so the phone doesn't show twice.
- **Tests** (`./gradlew :feature:media:testDebugUnitTest`, 27 tests): palette (synthetic + the design's three covers vs its palettes), median cut, HSL, progress / clock formatting, session mapping (playing / paused / buffering / speed / fallbacks / hidden states / seekability / skip / pause from actions / remote volume / which sessions are mirrored), settings defaults.
- **Settings** (`MediaSettings.kt`, section "Music", order 30, all applied live from `settings.changes`):
  - `media.otherApps` "Show other apps' music" (BOOLEAN, default on). Off clears the mirrored sessions and stops publishing them; on resyncs.
  - `media.island` "Show music in the island" (BOOLEAN, default on). Off ends the MUSIC activity.
  - `media.pausedDrift` "Keep the background moving while paused" (BOOLEAN, default off = the drift settles 30 s after pause).
  - Device name: the popover ("Streaming to / Connected to / Playing on <name>") and the empty state ("AirPlay to <name> from …") use `DeviceName.get(services.settings)` and follow changes live (screenshots show the default "Echo Show"). The listener's label in Android settings is "Vesper media".
  - Verified on the Echo: the defaults and the device name (silent: screenshots only). Toggling the switches on the device waits for the settings page; the readers are unit-tested.

## Left

- Spotify Connect source label/icon is a guess until a Spotify source exists.
- Night mode doesn't apply the faces' 0.42 dim itself (I assume brightness/dim is the clock/system side); say if the screen should.
- Long titles are ellipsized, not marquee-scrolled (the design ellipsizes too).

## Measured on the Echo

Debug build, Now Playing playing the demo (ambient drift + live scrubber animating every frame):

### Perf 2026-11-22 04:57 (10 s, 0.1.0, debug; rebased on main with AirPlay + contracts round 1)
- PSS total: 43.6 MB (Java 6.6 MB, Native 11.9 MB, Graphics 5.6 MB)
- Device MemAvailable: 312 MB
- CPU: 42.7% of one core (avg over 10s)
- Frames: 621 rendered, janky 613 (98.71%)
- Frame time p50/p90/p99: 18ms/19ms/20ms (budget 17ms at 59 Hz)

(Earlier run, 04:27, before the rebase: PSS 42.0 MB, CPU 42.8 %, 620 frames, same frame times.)

How to read the frames line: **620 frames in 10 s = the panel rate (58.9 Hz), every vsync gets a frame**, but gfxinfo flags them "janky" because each frame's vsync→completed latency is ~18–23 ms. `framestats` shows where it goes: UI thread ~2 ms, GPU after swap ~1 ms, and **~12 ms blocked in dequeueBuffer** (buffer back-pressure while drawing every vsync). Hiding view groups (`np=perf:<flags>`, `gpu-profile.sh`) barely changes it: everything shown 23.1 ms, no ambient 22.1, no art 22.8, no glass 21.8, no text 22.8, only transport glyphs left 21.0. **The lead's `bench` screen measures 27.4 ms the same way on the device today** (it was 7 ms at milestone 1), so it's the device's pipeline state now, not this screen. Worth a look by the lead (SurfaceFlinger / other windows?).

Before the 1/4-size ambient layer the GPU part after swap was 6.1 ms; with it, ~1 ms.

Empty state, measured (04:50): PSS 41.0 MB, **CPU 0.0 %, 0 frames** ("no frames drawn (idle)"). Paused settles to the same after 30 s; a hidden screen stops everything in `onScreenHidden`.

## Screenshots (`docs/status/shots/media/`, compared with the design rendered in Chrome at 960×480)

Design renders came from `NowPlaying.dc.html` with its template values filled in (track a, pos 1:23, Inter) and Chrome headless at 960×480.
- `playing.png`: vs design playing. Layout, art, glass, scrubber, transport and colours match; only the text is ~1–2 % narrower (Inter 4.1 with `opsz` vs Google Fonts Inter in Chrome).
- `paused.png`: art scaled to 0.86, play glyph. Matches the paused design.
- `popover.png`: AirPlay popover popped out of the button, the button white with a blue glyph. Matches, within 3 px of height.
- `pressed.png`: play button lifted into glass while held.
- `playing-track2.png` (Night Swim), `playing-track3.png` (Small Orbits; the names are by capture order, not album order): covers and their extracted ambient (compared with the design's own blob colours, see `DesignPaletteTest`).
- `empty.png`: nothing playing (my design, from the system: quiet, black, static).
- `bluetooth.png`, `bluetooth-popover.png`: `np=bt` preview of a Bluetooth source (no artwork → procedural cover; no seek; Bluetooth glyph in the top line and the route button; BLUETOOTH route popover).
- `airplay2-noskip.png`: `np=ap2` preview of an AirPlay 2 sender (`canSkip = false`): previous / next dimmed and inert.
- `night.png`: Night mode (red, black, no blobs, red-outline glass).
- `other-app.png`, `other-app-paused.png`: a track in Eleven (LineageOS's music app, label "Music") mirrored through MediaSessionManager with its own cover art, then paused from our transport button (Eleven auto-advanced to the next short clip between the two shots).

## Open questions

- Should the ambient drift continue while paused? The design keeps drifting; I settle it after 30 s paused to save power on a bedside device. Easy to change (`PAUSED_DRIFT_MS`).
- The design's text renders a little wider in Chrome (Google Fonts Inter, static) than with the bundled Inter 4.1 variable + `opsz` (SpecText); same for every screen, so it's a lead / `:core:design` call.

## Needs from the user

- Nothing for the demo. For other apps' media on a real setup: the listener grant below (one-time; `adb` or Settings → Notification access).

## Device setup

- `adb shell cmd notification allow_listener com.echobedside/com.echobedside.feature.media.MediaListenerService` (done on the Echo, Nov 22). Without it the app logs one line and the rest still works. **Note:** Android drops the grant whenever a build *without* this service is installed (every other agent's build until this branch is merged), so re-run it after merging; `app-session.sh` does.
- For the other-apps test only: `/sdcard/Music/testtone.mp3` (a generated sine tone with gradient cover art, made with ffmpeg) and `pm grant org.lineageos.eleven android.permission.READ_EXTERNAL_STORAGE`.

## Debug

- `--extra '--es np <cmd>'` on build-install: `empty`, `demo`, `paused`, `playing`, `next`, `popover`, `night`, `day`, `bt` (Bluetooth preview source), `ap2` (AirPlay 2 preview: no skip), `preview-off`, `perf:<flags>` (hide a ambient, r art, g glass, t text).
- Scripts in `docs/status/shots/media/` for `build-install.sh --then`: `states.sh` (popover, pressed, paused, tracks 2/3, empty), `bt-night.sh`, `final.sh` (route chips, AirPlay 2, Bluetooth popover), `gpu-profile.sh`, `app-session.sh` (Eleven + empty-state perf).
