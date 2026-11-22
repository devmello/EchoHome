# Status: media (agent D, `:feature:media`)

Branch `worktree-agent-af29799b3bed8abf4`, based on main `d3810a5`.

## Done

- **Now Playing screen** (`ScreenSpec("nowplaying", order = 10)`, `NowPlayingScreen.kt` + `NowPlayingViews.kt`), 1:1 with `design/NowPlaying.dc.html`:
  - Reads `services.media.current`; title / "Artist — Album" ellipsized to the column; source line ("AirPlay from …", "Playing from <app>", "Spotify Connect"); 12 h clock (24 h if the system says so), updated on the minute.
  - Ambient background: base colour + 3 pre-blurred blobs (ALPHA_8 masks at 1/8 size, blurred once, coloured by the paint so a palette change never re-blurs) + the dark shade. Motion = the design's drift formulas, stepped once a second with 1 s linear transitions. Rendered into a **240×120 hardware layer upscaled 4×** (the content is blurred ≥ 80 px, so it's visually identical and costs one full-screen quad instead of ~2 M px of overdraw).
  - Palette from the artwork (`Palette.kt`): my own median-cut quantiser (no AndroidX) + Palette-style roles (A = dark vibrant, B = vibrant, C = light vibrant, base = A's hue at 8.5 % lightness), clamped to the saturation / lightness bands of the designer's palettes. 1.2 s ease crossfade between tracks. Unit-tested against the design's own `base/a/b/c` for its three sample covers.
  - Live progress extrapolated from `MediaState.positionNow()`; tap/drag the scrubber to seek (only when `controls.canSeek`); elapsed / "-remaining" labels.
  - Transport: plain glyphs at rest that **lift into glass while touched** (opacity 0.18 s ease, scale 0.7→1 with `Motion.SPRING_BOUNCY`, as the design). Previous / play-pause / next go to `session.controls`.
  - Art shrinks to 0.86 when paused with the design's `cubic-bezier(0.3, 1.35, 0.5, 1)` 0.55 s (= `Motion.SPRING_SOFT`; the brief said `Motion.SPRING`, but the design file is the spec).
  - Glass volume capsule (the design's control, not `GlassSlider`): tap or drag. Uses the sender's volume when `MediaState.volume` is set (AirPlay, remote sessions), otherwise `systemControls.setVolume(v, showHud = false)`; follows hardware-key changes with the design's 0.25 s ease.
  - AirPlay button + **glass popover that pops out of the button** (spring scale from the button centre). Its backdrop is a blurred snapshot of the whole screen taken at open time (24 px, brightness .95); everything on it is plain fills (no glass on glass). Tap outside closes. Disconnect: demo → stops the demo (empty state); other apps → `transportControls.stop()`; AirPlay → `pause()` until `MediaControls.disconnect()` exists (contract request).
  - Glass controls sample a pre-blurred snapshot of the ambient layer (`PreBlur.blur`, rebuilt only when the palette changes). No live blur anywhere.
  - **Empty state**: black, static, no frames: AirPlay glyph, "Nothing playing", "AirPlay to Echo Bedside from your iPhone, iPad or Mac", clock.
  - Night mode (`system.state.nightActive`): black background, no blobs or motion, art mapped to monochrome red, all text/glyphs `#BE180E`, glass as red outlines.
  - Everything stops in `onScreenHidden` (ticker, minute tick, crossfade, art spring, popover). Paused: the drift settles after 30 s, then zero frames.
- **Demo source** (`DemoMedia.kt`): with `config.demoData` and no real session, runs core's `FakeMediaSource` through a hub wrapper that adds procedural artwork (`DemoArt`: the design's three covers redrawn with gradients, rings and ripples; no real album art) and advances to the next track at the end. Stops as soon as a real session appears; Disconnect keeps it off until restart.
- **Music island** (`MusicIsland.kt`): publishes / updates / ends the `MUSIC` `LiveActivity` (`id = "media.music"`, `ownerScreen = "nowplaying"`, content `LiveContent.Music`, actions previous / play-or-pause / next) from `services.media.current`, only on identity / play-state / artwork changes. Paused music goes stale after 10 min. Rendering is B's.
- **Other apps' media** (`AppSessions.kt`): own `MediaListenerService` (media only; ignores notifications) → `MediaSessionManager.getActiveSessions` + `OnActiveSessionsChangedListener`; every controller (except our own package) is published as `OTHER_APP` with working `MediaControls` (play/pause/next/previous/seek, remote volume or device volume) and artwork (metadata bitmap or art URI, centre-cropped to 324 px off the main thread). Mapping is a pure function (`SessionMapping.kt`) with unit tests.
- **Tests** (`./gradlew :feature:media:testDebugUnitTest`, 23 tests): palette (synthetic + the design's three covers vs its palettes), median cut, HSL, progress / clock formatting, session mapping (playing / paused / buffering / speed / fallbacks / hidden states / seekability / remote volume).

## Left

- `MediaControls.disconnect()` and route chips (codec, sample rate) need the contract change below; until then the popover shows the design's chips for the demo and just "AirPlay" for a real AirPlay source.
- Spotify Connect source label/icon is a guess until a Spotify source exists.
- Long titles are ellipsized, not marquee-scrolled (the design ellipsizes too).

## Measured on the Echo

(see below)

## Screenshots (`docs/status/shots/media/`, compared with the design rendered in Chrome at 960×480)

(see below)

## Open questions

- Should the ambient drift continue while paused? The design keeps drifting; I settle it after 30 s paused to save power on a bedside device. Easy to change (`PAUSED_DRIFT_MS`).
- The design's text renders a little wider in Chrome (Google Fonts Inter, static) than with the bundled Inter 4.1 variable + `opsz` (SpecText); same for every screen, so it's a lead / `:core:design` call.

## Needs from the user

- Nothing for the demo. For other apps' media on a real setup: the listener grant below (one-time; `adb` or Settings → Notification access).

## Device setup

- `adb shell cmd notification allow_listener com.echobedside/com.echobedside.feature.media.MediaListenerService` (done on the Echo, Nov 22). Without it the app logs one line and the rest still works.
- For the other-apps test only: `/sdcard/Music/testtone.mp3` (a generated sine tone with gradient cover art, made with ffmpeg) and `pm grant org.lineageos.eleven android.permission.READ_EXTERNAL_STORAGE`.

## Debug

- `--extra '--es np <cmd>'` on build-install: `empty`, `demo`, `paused`, `playing`, `next`, `popover`, `night`, `day`.
- `docs/status/shots/media/states.sh` (popover, pressed, paused, track 2/3, empty) and `app-session.sh` (Eleven test) run under `build-install.sh --then`.
