# Clock faces (agent A, `:feature:clock`)

## Done

- **Faces** (all `FaceView`s registered in `ClockFeature.install`, order in brackets):
  Digital [0] (unchanged), Analog [10], World [20], Solar [30], Float [40], Liquid Glass [50], Weather [60], Calendar [70].
  Each ports its `design/<Name>.dc.html` 1:1 at 960×480 with `SpecText`, and takes `FaceConfig.night`.
  - **Analog**: superellipse n=5 minute track (a=446, b=222) and numerals (a=380, b=172), ticks rotated to the curve normal (`Superellipse.ring`, NOTES §5.7); date at 9, alarm at 3 from `services.alarms.next`. Two layers: the dial redraws on the minute, the hands view redraws once a second (aligned to the wall-clock second), stopped in `onScreenHidden`/detach.
  - **World**: land mask from `World.dc.html → rows()`, lit by the design's sun declination / subsolar longitude with the smoothstep twilight band; ~1,000 dots recomputed once a minute and drawn as 48 `drawPoints` batches (24 brightness levels × under-text). Cities from `clock.world.cities` (default the design's three); "you are here" from `services.location`. No colour option.
  - **Solar**: the sky / corona / horizon streak / disc + two glows are painted once per colour into a half-res bitmap on `Dispatchers.Default` (soft gradients; `BlurMaskFilter` sigmas from the CSS), then only text per minute.
  - **Float**: two-tone `hsl` bubble gradients per glyph (170°), tilt/lift per position, `BlendMode.SCREEN` between digits, colon dots on top. **Font:** Nunito Black from `feature/clock/src/main/assets/fonts/Nunito-Black.ttf` when that file is there (`FloatFont`, OFL); until it is bundled, Inter 900 with a 14 px round-joined outline stroke (see Needs from the user).
  - **Liquid Glass**: wallpaper (`Blue|Dusk|Graphite`) and the "lens" copy (colours passed through the tint and toward white by 0.14 + 0.5·glass, then pre-blurred by the glass amount) are painted once off the main thread at half res; the digits are a path filled with a `BitmapShader` of the lens copy under a 1.08× scale matrix about the centre (CSS `background-size: 108%`), then the specular band and a 1.5 px rim. Shadow = quarter-res blurred copy of the text, rebuilt per minute. Glyph contours are merged per glyph (`Path.op UNION`) because Inter variable keeps overlapping contours, which the rim stroke would otherwise show. Settings: `clock.glass.amount` (0–1, default 0.45), `clock.glass.wallpaper`.
  - **Weather**: day / dusk / night sky from sunrise/sunset (weather source, else computed for `services.location`: `SunMath.sunriseSunset`), stars + drifting cloud banks (pre-blurred 1/8-res bitmaps), hourly / sunrise / tomorrow modules as plain content fills (no glass on content). TOMORROW bars = 10 of tomorrow's `Weather.hourly` precipitation chances centred on the rain (`rainWindow`); no sample data. Motion updates **once a second** (clouds move ≤ 1.5 px/s under a 40 px blur); none in Night or while hidden.
  - **Calendar**: day/date, mini month, agenda for today + tomorrow with the live "now" line, past events at 0.4, "in N min" badge (≤ 60 min). Redraws on the minute.
- **Face switching** (StandbyScreen): faces are built once per StandBy visit (25–50 ms each on the Echo); the current face's neighbours are built on an idle moment (never mid-animation) and parked just off screen, so a swipe only moves recorded display lists. Other screens showing → only the current face stays built. Vertical swipe, face follows the finger, neighbour slides in, commit past ¼ screen or a flick (380 ms `Motion.EASE`), rubber band at the ends. Swipes starting in the top 40 px are ignored (Control Center pull-down). Horizontal drags still go to the pager.
- **Edit mode** (long-press, `Customize.dc.html`): face zooms to 0.8 into a 34 px rounded card with a 1 px ring (corners are black masks over the stage, not an outline clip; each visible face draws from its own GPU layer while editing, see Measured); glass Night pill (red tint when forced on), tinted blue Done (the one primary action), vertical glass page dots (tap to switch, or swipe the face), glass colour button that pops open into a capsule of plain-fill swatches (spring), face name. Tap on the zoomed face toggles complications (faces that have them). Persisted: `clock.face`, `clock.color.<id>`, `clock.complications.<id>`.
- **Profiles** (camera): StandBy follows `services.profiles.active`. A recognised person's `faceId` + `color` replace the user's choice (slide animation when idle); `Profile.SHARED` = the user's own choice from settings. Face / colour edits made while someone is recognised go into their profile (`profiles.upsert`).
- **Night mode** (`Night.dc.html`, `NightDigital.dc.html`): every face maps to #BE180E (with the per-face night palettes the designs define: Solar's #7A0D07 sun, Glass's red lens/specular/rim, World's red dots, Weather/Calendar red text); StandBy dims the stage to 0.42 with a 1.6 s ease; a tap (or `presence` → PRESENT from the camera) wakes it to full for 6 s. Edit mode shows the face undimmed.
- **Settings** (`ClockSettings.section`, registered for the web settings page): clock face (CHOICE), each face's colour (COLOR, stored `#RRGGBB`; Int values from older builds still read), Digital/Analog complications (BOOLEAN), Liquid Glass frost (FLOAT 0–1) and wallpaper (CHOICE), World cities (TEXT_LIST of `City|Zone|lat|lon`, max 3), Night mode (CHOICE auto / schedule / on / off) with the schedule's from / until (TIME, default 22:00–07:00) and, under Advanced, the dark/bright lux thresholds and the dark delay. All apply live through `settings.changes` (StandBy, World, Glass, NightController).
- **Auto Night** (`NightController` + pure `AutoNight`): `Sensor.TYPE_LIGHT` on-change, enter < 3 lux for 30 s (timer re-checks since a dark room sends no events), leave > 10 lux; `SystemState.nightMode` ON/OFF override, AUTO follows the automatic source: the light sensor (`auto`) or the clock (`schedule`: `clock.night.start` → `clock.night.end`, may cross midnight; one timer to the next change, re-checked at least every 5 min, sensor not registered); the mode persists as `clock.night.mode` (also when Control Center or edit mode changes it). Writes `nightActive` and `ambientLux` (only on ≥ 10 % / 1 lux changes). Sensor registered only while StandBy is shown; mode changes (e.g. from Control Center) apply any time. The edit-mode Night pill toggles the mode ON ↔ AUTO via `services.systemControls.setNightMode`.
- **Tests** (`src/test/.../FaceMathTest.kt`, 27): superellipse on-curve / normals / cardinal points, declination / subsolar longitude / twilight smoothstep / Santa Cruz sunrise (6:58 ± 4 min, matches the design) / polar night, land mask + projection, AutoNight timing + hysteresis + mode overrides, colour mix/hsl/pass, time formatting, the tomorrow-rain window, the cities TEXT_LIST round trip, `#RRGGBB` parsing, the Night schedule window (overnight, same-day, next change, `HH:mm`).
- Debug launch extras for `--es screen standby`: `face`, `edit true|false`, `palette open`, `nightmode auto|schedule|on|off`, `nightstart` / `nightend HH:mm`, `editexp <bits>` (debug: 2 = no overlay layer, 4 = no face layers), `wake true`, `color #RRGGBB`, `glass 0..1`, `wallpaper`, `complications`, `profile <id>` (switchTo MANUAL), and (debug builds) `fakelux <lux>`.

## Left

- Liquid Glass frost / wallpaper and World cities have no on-device control (no design for one); they're on the web settings page.
- The home location is `services.location` (`:feature:home` binds it); the clock has no location setting of its own.
- The first edit-mode entry after the Echo has been idle is still janky (the 0.5 s zoom runs at ~21 ms per frame; see Measured). Later entries, the palette, swipes and Done hold 60 fps.
- Nunito Black isn't bundled yet (needs the download OK, see Needs from the user).
- Night dim is view alpha; actual backlight dimming belongs with `:feature:system` (brightness).

## Measured on the Echo

Debug build, `./scripts/echo-perf.sh` (PSS of the whole app process, CPU of one core, gfxinfo frames), Nov 22 2026. Device MemAvailable stayed at 300–335 MB.

| Face (idle, 10 s, Night off) | PSS | CPU | Frames in 10 s | Frame time |
|---|---|---|---|---|
| Digital | 37.3 MB | 0 % | 0 | — |
| Analog (second hand, 1 Hz) | 35.8 MB | 1.3 % | 10 | 7–8 ms |
| World | 39.1 MB | 0 % | 0 | — |
| Solar | 41.0 MB | 0 % | 0 | — |
| Float | 33.1 MB | 0.5 % | 1 | 8 ms |
| Liquid Glass | 37.5 MB | 0 % | 0 | — |
| Weather (stars + clouds, 1 Hz) | 33.5 MB | 1.2 % | 10 | 6–7 ms (was 28 ms before the RenderNode) |
| Calendar | 38.3 MB | 0 % | 0 | — |
| Night, Analog dimmed to 0.42 | 57.4 MB | 1.5 % | 10 | 8 ms |

Idle faces draw nothing between minute ticks, except Analog's second hand and Weather's 1 Hz drift (none at Night). Motion stops in `onScreenHidden`.

| Interaction | Frames | Janky | p50 / p90 / p99 | CPU | PSS |
|---|---|---|---|---|---|
| Swipes every 1.3 s through all 8 faces and back, faces built on touch-down (before) | 360 | 26.7 % | 7 / 22 / 150 ms | 27 % | 54 MB |
| Same, faces built on idle and kept for the visit (now) | 458 | 5.9 % | 6 / 12 / 61 ms | 20 % | 53 MB |
| Edit mode: long-press in, palette, swipe, Done (before) | 120 | 63 % | 17 / 42 / 109 ms | 21 % | 72 MB |
| Same, scaled stage kept as a GPU layer at rest | 122 | 43 % | 9 / 28 / 129 ms | 16 % | 62 MB |

**Re-measured with raw panel events** (`sendevent` on `/dev/input/event2` instead of `input swipe`, which starts a JVM (`app_process`) on the Echo and competes with the app for the CPU during exactly the frames being measured):

| Interaction (raw touch) | Frames | Janky | p50 / p90 / p99 |
|---|---|---|---|
| Swipes every ~1.3 s through all faces and back | 407 | 5.7 % | 6 / 13 / 57 ms |
| Edit mode, stage layer (before this round): hold, palette, swipe, Done | 133 | 34.6 % | 11 / 30 / 150 ms |
| Edit mode now, first entry after idle (cold) | 139 | 21.6 % | 5 / 18 / 69 ms |
| Edit mode now, warm: enter 2 / 32 janky, palette 0 / 32, swipe 2 / 36, Done 1 / 26 | ~126 | ~4 % | 5 ms median |

Per phase (framestats, frames over 17 ms):
- **Swipe inside edit mode**: 29 / 46 janky → 1 / 49. Each visible face now draws from its own GPU layer while editing, so a swipe moves two textured quads (render-thread issue time 12.4 → 2.2 ms per frame).
- **Overlay fade**: the overlay is a GPU layer while it fades (it doesn't change then), dropped at rest so the palette spring doesn't re-render a layer.
- **Rounded corners**: black corner masks instead of `clipToOutline`.
- **Enter zoom**: warm, 6.6 ms median (2 / 32 janky). On the first entry after the Echo has been idle, the 26–32 zoom frames run at ~21 ms: render-thread issue ~16 ms plus ~14 ms waiting on buffers, with the GPU at its idle clock (`/proc/gpufreq`: 299 MHz, ramping to 520). That accounts for all of the remaining cold jank. I can't change the GPU governor (system setting).

- Building a face on the main thread costs 25–50 ms (logcat `EchoClock: created face …`). That was the swipe jank; it's now paid once per visit on an idle moment.
- Edit-mode framestats: UI thread ~1 ms per frame; the render thread's "issue draw commands" is 7.4 ms median, 16.6 ms p90, plus buffer dequeue stalls. So the cost is the render thread replaying the scaled face + four glass controls, not app code. Remaining options: give the glass controls their own layers, or snapshot the face to a bitmap for the whole edit session.
- PSS stays under 75 MB in every test (budget 200 MB).
- Auto Night: with `--es fakelux 0.5`, still day at 20 s and Night (red, dimmed) at 34 s; `fakelux 50` back to day at once. `dumpsys sensorservice` lists our light-sensor connection only while StandBy is shown.

## Screenshots

`docs/status/shots/clock/`, all from the Echo at 960×480, compared with the design files:

- Faces vs `Analog`, `Digital`, `World`, `Solar`, `Float`, `Glass`, `Weather`, `Calendar` `.dc.html`: `analog.png`, `digital.png`, `world.png`, `solar.png`, `float.png`, `glass.png`, `glass-frosted-dusk.png` (frost 1, Dusk, orange), `weather.png`, `calendar.png`. They match in layout, type and colour. Differences:
  - Float uses Inter 900 with a round stroke, not Nunito (see Open questions).
  - Weather shows data (54°, H:61 L:47, sunrise 6:56 computed for Santa Cruz, tomorrow's bars from hourly rain) instead of the artboard's fixed sample values.
- Night vs `Night.dc.html` / `NightDigital.dc.html`: `night-analog.png`, `night-digital.png`, `night-world.png`, `night-solar.png`, `night-float.png`, `glass-night.png`, `night-weather.png`, `night-calendar.png` (all dimmed to 0.42), `night-analog-awake.png` (tap: full red for 6 s).
- Edit mode vs `Customize.dc.html`: `edit-analog.png` (palette open, re-shot with the corner masks), `edit-world.png` (no colour button), `edit-night.png` (Night pill on).
- Gestures, driven with `input swipe/tap`: `gesture-swipe-up.png` (Analog → World), `gesture-longpress-edit.png`, `gesture-palette-open.png`, `gesture-color-picked.png`, `gesture-edit-swipe.png` (World → Solar inside edit mode), `gesture-done.png`.
- Night schedule: `schedule-inside.png` (window around the current time: Night on), `schedule-outside.png` (window later today: day).
- Auto Night: `auto-20s.png` (dark for 20 s, still day), `auto-34s-night.png` (Night), `auto-bright.png` (back to day).
- Profiles vs `Profiles.dc.html`: `profile-maya.png` (Maya recognised → her pink Float face, as in the design), `profile-shared.png` (back to the user's Analog).

## Open questions

- **Float font**: approved by the lead; the loader is in (`FloatFont`, falls back to Inter 900 + round stroke). The file itself is not bundled yet (see Needs from the user).
- **Complications toggle**: in edit mode, a tap on the zoomed face toggles them (Customize.dc.html has no control for it). Also on the settings page.
- **Night pill**: toggles ON ↔ AUTO (not OFF), so a tap never leaves the room lit at night by accident. OFF is on the settings page.
- **`nightActive` while StandBy is hidden**: the light sensor runs only while StandBy is shown, so `nightActive` / `ambientLux` freeze on other screens. If other features need live lux there, the controller can keep listening (on-change sensor, low cost).
- **Weather face motion** is 1 Hz (clouds ≤ 1.5 px/s under a 40 px blur, visually continuous) to stay near zero frames; the design implies continuous drift.
- **Top 40 px** of StandBy don't start a face swipe; they're left for the Control Center pull-down.
- **Profile edits**: while someone is recognised, face / colour edits change their profile (`profiles.upsert`), including `Profile.color` (also their accent elsewhere).
- Contract requests filed in `docs/contract-requests.md` (home location, tomorrow's hourly rain) are answered and applied.

## Needs from the user

- **OK to download Nunito Black** for the Float face: `Nunito-Black.ttf` (about 130 KB) and its licence `OFL.txt` from Google Fonts (fonts.google.com/specimen/Nunito, the static Black weight; SIL Open Font License 1.1). They go to `android/feature/clock/src/main/assets/fonts/Nunito-Black.ttf` and `.../fonts/OFL-Nunito.txt`; nothing else changes (the code loads it when present). I didn't download it without that OK.
- Nothing else: the clock needs no /system change, remount or flash.

## Device setup

None: no `pm grant`, `appops` or `settings put`. The light sensor needs no permission. Debug launch extras (`--es screen standby --es face …` etc., listed under Done) are for testing only.
