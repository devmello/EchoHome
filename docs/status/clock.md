# Clock faces (agent A, `:feature:clock`)

## Done

- **Faces** (all `FaceView`s registered in `ClockFeature.install`, order in brackets):
  Digital [0] (unchanged), Analog [10], World [20], Solar [30], Float [40], Liquid Glass [50], Weather [60], Calendar [70].
  Each ports its `design/<Name>.dc.html` 1:1 at 960×480 with `SpecText`, and takes `FaceConfig.night`.
  - **Analog**: superellipse n=5 minute track (a=446, b=222) and numerals (a=380, b=172), ticks rotated to the curve normal (`Superellipse.ring`, NOTES §5.7); date at 9, alarm at 3 from `services.alarms.next`. Two layers: the dial redraws on the minute, the hands view redraws once a second (aligned to the wall-clock second), stopped in `onScreenHidden`/detach.
  - **World**: land mask from `World.dc.html → rows()`, lit by the design's sun declination / subsolar longitude with the smoothstep twilight band; ~1,000 dots recomputed once a minute and drawn as 48 `drawPoints` batches (24 brightness levels × under-text). Cities from `clock.world.cities` (default the design's three); "you are here" from `services.location`. No colour option.
  - **Solar**: the sky / corona / horizon streak / disc + two glows are painted once per colour into a half-res bitmap on `Dispatchers.Default` (soft gradients; `BlurMaskFilter` sigmas from the CSS), then only text per minute.
  - **Float**: two-tone `hsl` bubble gradients per glyph (170°), tilt/lift per position, `BlendMode.SCREEN` between digits, colon dots on top. **Font:** Nunito at wght 900 from `feature/clock/src/main/assets/fonts/Nunito-wght.ttf` (Google Fonts' variable font, OFL in `OFL-Nunito.txt`, bundled Nov 22 with the user's OK); without the file, Inter 900 with a 14 px round-joined outline stroke.
  - **Liquid Glass**: wallpaper (`Blue|Dusk|Graphite`) and the "lens" copy (colours passed through the tint and toward white by 0.14 + 0.5·glass, then pre-blurred by the glass amount) are painted once off the main thread at half res; the digits are a path filled with a `BitmapShader` of the lens copy under a 1.08× scale matrix about the centre (CSS `background-size: 108%`), then the specular band and a 1.5 px rim. Shadow = quarter-res blurred copy of the text, rebuilt per minute. Glyph contours are merged per glyph (`Path.op UNION`) because Inter variable keeps overlapping contours, which the rim stroke would otherwise show. Settings: `clock.glass.amount` (0–1, default 0.45), `clock.glass.wallpaper`.
  - **Weather**: day / dusk / night sky from sunrise/sunset (weather source, else computed for `services.location`: `SunMath.sunriseSunset`), stars + drifting cloud banks (pre-blurred 1/8-res bitmaps), hourly / sunrise / tomorrow modules as plain content fills (no glass on content). TOMORROW bars = 10 of tomorrow's `Weather.hourly` precipitation chances centred on the rain (`rainWindow`); no sample data. Motion updates **once a second** (clouds move ≤ 1.5 px/s under a 40 px blur); none in Night or while hidden.
  - **Calendar**: day/date, mini month, agenda for today + tomorrow with the live "now" line, past events at 0.4, "in N min" badge (≤ 60 min). Redraws on the minute.
- **Face switching** (StandbyScreen): faces are built once per StandBy visit (25–50 ms each on the Echo); the current face's neighbours are built on an idle moment (never mid-animation) and parked just off screen, so a swipe only moves recorded display lists. Other screens showing → only the current face stays built. Vertical swipe, face follows the finger, neighbour slides in, commit past ¼ screen or a flick (380 ms `Motion.EASE`), rubber band at the ends. Swipes starting in the top 40 px are ignored (Control Center pull-down). Horizontal drags still go to the pager.
- **Edit mode** (long-press, `Customize.dc.html`): face zooms to 0.8 into a 34 px rounded card with a 1 px ring (corners are black masks over the stage, not an outline clip; each visible face draws from its own GPU layer while editing, see Measured); glass Night pill (red tint when forced on), tinted blue Done (the one primary action), vertical glass page dots (tap to switch, or swipe the face), glass colour button that pops open into a capsule of plain-fill swatches (spring), face name. Tap on the zoomed face toggles complications (faces that have them). Persisted: `clock.face`, `clock.color.<id>`, `clock.complications.<id>`.
- **Profiles** (camera): StandBy follows `services.profiles.active`. A recognised person's `faceId` + `color` replace the user's choice (slide animation when idle); `Profile.SHARED` = the user's own choice from settings. Face / colour edits made while someone is recognised go into their profile (`profiles.upsert`).
- **Night mode** (`Night.dc.html`, `NightDigital.dc.html`): every face maps to #BE180E (with the per-face night palettes the designs define: Solar's #7A0D07 sun, Glass's red lens/specular/rim, World's red dots, Weather/Calendar red text); StandBy dims the stage to 0.42 with a 1.6 s ease; a tap (or `presence` → PRESENT from the camera) wakes it to full for 6 s. Edit mode shows the face undimmed.
- **Settings** (`ClockSettings.section`, registered for the web settings page): clock face (CHOICE), each face's colour (COLOR, stored `#RRGGBB`; Int values from older builds still read), Digital/Analog complications (BOOLEAN), Liquid Glass frost (FLOAT 0–1) and wallpaper (CHOICE), World cities (TEXT_LIST of `City|Zone|lat|lon`, max 3), Night mode (CHOICE auto / schedule / on / off) with the schedule's from / until (TIME, default 22:00–07:00) and, under Advanced, the dark/bright lux thresholds and the dark delay. All apply live through `settings.changes` (StandBy, World, Glass, NightController).
- **Auto Night** (`NightController` + pure `AutoNight`): `Sensor.TYPE_LIGHT` on-change, enter < 3 lux for 30 s (timer re-checks since a dark room sends no events), leave > 10 lux; `SystemState.nightMode` ON/OFF override, AUTO follows the automatic source: the light sensor (`auto`) or the clock (`schedule`: `clock.night.start` → `clock.night.end`, may cross midnight; one timer to the next change, re-checked at least every 5 min, sensor not registered); the mode persists as `clock.night.mode` (also when Control Center or edit mode changes it). Writes `nightActive` and `ambientLux` (only on ≥ 10 % / 1 lux changes). Sensor registered only while StandBy is shown. The Echo's light HAL sends no reading on registering (only on changes), so after an app restart in a room that stays dark Night never came on (fixed Nov 23, 2026): with no event 1.5 s after registering, the controller reads `/sys/bus/platform/drivers/als_ps/lux` once (world-readable, e.g. `0 lux`); mode changes (e.g. from Control Center) apply any time. The edit-mode Night pill toggles the mode ON ↔ AUTO via `services.systemControls.setNightMode`.
- **Tests** (`src/test/.../FaceMathTest.kt`, 27): superellipse on-curve / normals / cardinal points, declination / subsolar longitude / twilight smoothstep / Santa Cruz sunrise (6:58 ± 4 min, matches the design) / polar night, land mask + projection, AutoNight timing + hysteresis + mode overrides, colour mix/hsl/pass, time formatting, the tomorrow-rain window, the cities TEXT_LIST round trip, `#RRGGBB` parsing, the Night schedule window (overnight, same-day, next change, `HH:mm`).
- Debug launch extras for `--es screen standby`: `face`, `edit true|false`, `palette open`, `nightmode auto|schedule|on|off`, `nightstart` / `nightend HH:mm`, `editexp <bits>` (debug: 2 = no overlay layer, 4 = no face layers), `wake true`, `color #RRGGBB`, `glass 0..1`, `wallpaper`, `complications`, `profile <id>` (switchTo MANUAL), and (debug builds) `fakelux <lux>`.

## Left

- Liquid Glass frost / wallpaper and World cities have no on-device control (no design for one); they're on the web settings page.
- The home location is `services.location` (`:feature:home` binds it); the clock has no location setting of its own.
- The first edit-mode entry after the Echo has been idle is still janky (the 0.5 s zoom runs at ~21 ms per frame; see Measured). Later entries, the palette, swipes and Done hold 60 fps.
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

- Building a face on the main thread costs 25–50 ms (logcat `VesperClock: created face …`). That was the swipe jank; it's now paid once per visit on an idle moment.
- Edit-mode framestats: UI thread ~1 ms per frame; the render thread's "issue draw commands" is 7.4 ms median, 16.6 ms p90, plus buffer dequeue stalls. So the cost is the render thread replaying the scaled face + four glass controls, not app code. Remaining options: give the glass controls their own layers, or snapshot the face to a bitmap for the whole edit session.
- PSS stays under 75 MB in every test (budget 200 MB).
- Auto Night: with `--es fakelux 0.5`, still day at 20 s and Night (red, dimmed) at 34 s; `fakelux 50` back to day at once. `dumpsys sensorservice` lists our light-sensor connection only while StandBy is shown.

## Screenshots

`docs/status/shots/clock/`, all from the Echo at 960×480, compared with the design files:

- Faces vs `Analog`, `Digital`, `World`, `Solar`, `Float`, `Glass`, `Weather`, `Calendar` `.dc.html`: `analog.png`, `digital.png`, `world.png`, `solar.png`, `float.png`, `glass.png`, `glass-frosted-dusk.png` (frost 1, Dusk, orange), `weather.png`, `calendar.png`. They match in layout, type and colour. Differences:
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
- **Two "Night mode" settings**: `:feature:system` (merged later) also registers `system.night.mode` (AUTO/ON/OFF). Both drive `SystemState.nightMode` and stay in step, but the settings page lists Night mode twice. Filed in `docs/contract-requests.md`: proposed that system drops its spec, because only the clock's has the schedule.
- Contract requests filed in `docs/contract-requests.md` (home location, tomorrow's hourly rain) are answered and applied.

## Needs from the user

- Nothing else: the clock needs no /system change, remount or flash.

## Device setup

None: no `pm grant`, `appops` or `settings put`. The light sensor needs no permission. Debug launch extras (`--es screen standby --es face …` etc., listed under Done) are for testing only.

## Nov 22 evening: sun/moon, personal line, AQI/UV

- **Night sun/moon** (`AstroView`, `Astro.kt`): moon glyph with lit part, phase name + %, sunrise/sunset (weather source if both present, else `SunMath` NOAA-style for `services.location`; after sunset shows tomorrow's). Bottom-right in red; Analog: lower-left inside the dial (hands can cross it); hidden on Weather/Calendar faces. Updates on minute tick / inputs only. Setting `clock.night.astro`.
- **Personal line** (`PersonalView`): avatar, name, their next alarm (own, else shared; uses store `next` when it's theirs) and next event (own calendar, else shared), top-left, 700 ms fade in/out, Night red, ellipsised to avoid the island. Setting `clock.personalLine`. Debug extra `fakealarm on|off` (Maya 6:15 alarm).
- **AQI/UV**: Digital's weather block gets "AQI 42 · UV 3" when `Weather.air` exists. Not added to the Weather face.
- Tests: `AstroTest.kt` (moon full/new/quarters vs known dates, San Francisco sunrise/sunset solstices within 5 min, alarm/event selection, air line).
- Device: idle StandBy 35.1 MB PSS, 0.2% CPU, 0 frames (before: 37.3 MB, 0%). Shots: `shots/clock/astro-night-*.png`, `personal-*.png`.
- Unverified: AQI line on device (no air data), Night + personal on faces other than Digital/Analog/Float, extras during face swipes (hidden while sliding), real-calendar events.
- Note: demo data was toggled on the Echo for screenshots and set back to false.

## Nov 22 night: Widgets page (two StandBy stacks side by side)

Like iPhone StandBy's widgets view: pager screen `widgets` (order 5, right after StandBy), `WidgetsScreen.kt`.

- **Layout**: two glass tiles 448×432 (24 px margins, 16 px gap, radius 34). Each is a `WidgetStack` that shows one widget. A vertical swipe or flick moves to the next widget (up) or previous (down) with a 420 ms short spring (`PathInterpolator(0.3, 1.12, 0.5, 1)`), and wraps around. Page dots for each stack sit in the outer 24 px margin. A drag is claimed only once it's clearly vertical (`StackGesture.decide`: past slop and 1.2× steeper than wide), so horizontal swipes still page. Swipes starting in the top 40 px are left to Control Center. Widget content sits 16 px lower (`StackWidget.DY`) so the island and its shadow don't cover the header rows.
- **Widgets** (`Widgets.kt`, order in `WidgetKind`):
  - Clock: date and 144 px time, with the next alarm.
  - Weather: now, condition icon, H/L and 5 hours.
  - Calendar: today's next 3 events with all-day ones first, "Now · until …", coloured by the person's profile or the calendar. With no events left, it shows tomorrow's first event.
  - Next alarm: time, day plus "in 7 h 12 min", label and `NextAlarm.reason`, or "No alarm". A tap opens `alarms`.
  - Timers: the first timer large with a remaining bar, others as rows. It ticks once a second, and only while a timer runs and the widget is on screen. A tap opens `timers`.
  - Now Playing: art (center-crop `BitmapShader`; at Night a luminance→#BE180E colour filter), source, title and artist, and previous / play-pause / next through `MediaSession.controls` (skip dimmed when `!canSkip`). Otherwise "Not playing".
  - Air & UV: AQI with an EPA colour and a 0–300 scale, UV and its category, today's high, and the top pollen.
  - Sun & Moon: the day's arc with the sun, sunrise and sunset ("Sunset in …"), the moon glyph (`MoonPainter`, extracted from `AstroView`), and "97% lit · Full moon in 2 days".
- **Settings** (in the Clock section, applied live from `settings.changes`): `clock.widgets.left` (default `clock`), `clock.widgets.right` (default `weather`), and `clock.widgets.wallpaper` (Blue / Dusk / Graphite from Liquid Glass, or Black; default Graphite).
- **Per-person**: with a profile active, Calendar shows that person's events plus shared ones, and Alarm / Clock show their own plus shared alarms (`WidgetData.alarmFor` / `visible`). The person's name and dot show at the top right.
- **Night**: the page draws on black, the tiles become red outlines (`GlassPainter.night`), everything maps through `Night.map`, and the page dims to 0.42 (a touch wakes it for 6 s). The light sensor (`NightController`) runs while the page shows.
- **Cost**:
  - The wallpaper plus both tiles' glass and lift shadows are painted once, off the main thread, into one immutable 960×480 bitmap. A frame is that bitmap plus the widgets' display lists.
  - Widgets are built on idle moments (`MessageQueue.IdleHandler`, never mid-gesture). Once built they stay attached, parked 4000 px below the clip with their display list recorded, so a swipe and its commit only change `translationY`.
  - A parked widget doesn't redraw on data or minute changes; it catches up when it slides in.
  - Redraws happen only on a change: the minute for the clock, data flows, the 1 Hz timer tick, and the slide.
- **Debug extras** (`--es screen widgets`): `left <id>`, `right <id>`, `wallpaper <name>`, `profile <id>`, and (debug builds) `timer <seconds>`.
- **Tests**: `WidgetModelTest` (14): order and ids, wrap-around both ends, parse fallback, per-stack persistence and reload from the settings page, the gesture-direction decision, settle (quarter or fling, fling against the drag returns), shared vs per-person alarms (store reason kept), today's events per person, countdown rounding, relative times, UV/AQI scales, the next full/new moon.

Measured on the Echo (debug build, `echo-perf.sh`; swipes are raw `sendevent` on `/dev/input/event2`, 12 swipes over both stacks and directions, ~1 s apart):

| Case | Frames | Janky | p50 / p95 / p99 | Notes |
|---|---|---|---|---|
| Swipes, widgets built on first slide (first version) | 611 | 3.9 % | 5 / 9 / 85 ms | build 10–30 ms + first record 18 ms + attach/layout 12–14 ms at commit |
| Swipes, same process again (all built) | 617 | 0.5 % | 5 / – / 15 ms | |
| Swipes, built and recorded on idle, parked (now), fresh process | 454 | 0.44 % | 5 / 8 / 12 ms | |
| Same, second pass | 455 | 0 % | 5 / 7 / 10 ms | |
| Same, later run | 466 | 0.86 % | 5 / 7 / 13 ms | |

- **Idle**: a temporary draw log on every widget (removed) counted 0 widget draws in 30 s and 0 in 20 s across a minute boundary. On launch each widget recorded once on idle, and a swipe re-recorded nothing.
- **Frame counts**: the page as a whole showed 3–13 frames / 10 s at 2–3 % CPU, where StandBy showed 0 frames and 0.7 % in the same minute. Those frames are not widget draws; their source (the page's own `onDraw`, or an overlay) isn't found yet.
- **Bluetooth music**: while music played over Bluetooth, the island's level bars drew ~16 fps on StandBy and here alike (155–160 frames / 10 s).
- **PSS**: 67–80 MB during these tests.
- **Shots** (`shots/clock/`):
  - `widgets-default.png` (Clock + Weather)
  - `widgets-swipe-left.png`, `widgets-swipe-right.png` (after `input swipe`)
  - `widgets-calendar-alarm.png`
  - `widgets-air-sunmoon.png`
- A horizontal swipe on a tile pages (widgets → Now Playing, StandBy → widgets), checked by screenshot.
- **Unverified on the device**:
  - **Night**: neither `--es night on` (not one of the system overlay's launch keys) nor the `app.vesper.system.DEBUG` broadcast (received) showed Night. Most likely `NightController`, which this page starts, rewrote `nightActive` from the light sensor. It needs a check with `clock.night.mode=on`.
  - The per-person views (only unit-tested; I didn't switch the real profile).
  - The tap actions (alarms / timers / media controls).
  - The 1 Hz timer tick's frame cost on its own (measured only together with other activity).
  - The source of the 3–13 idle frames above.
- The test timer started with `timer` was cancelled afterwards.

## Nov 23: per-person wallpaper and Night mode

- **`Profile.wallpaper`** (`:core:model`, null = follow Shared): the recognised person's Liquid Glass wallpaper (Blue / Dusk / Graphite). StandBy passes it to the face as `FaceConfig.wallpaper` (only to faces with a background: `glass`), and `GlassFace` uses it over `clock.glass.wallpaper`. The Widgets page bakes the person's wallpaper over `clock.widgets.wallpaper` (Black stays a setting-only choice). Resolution is `PersonPrefs` (pure, `PersonPrefsTest`).
- **Edit mode wallpaper pill** (no board has one): on Liquid Glass, a glass pill bottom-left, mirroring the colour capsule, "Wallpaper · Dusk" with the palette's colour dot. Each tap picks the next wallpaper, into the recognised person's profile, or `clock.glass.wallpaper` for Shared. The `wallpaper` launch extra writes the same way.
- **`Profile.nightMode`** (null = follow Shared): `NightController` uses the person's mode while they're active (`auto` / `schedule` / `on` / `off`) and goes back to `clock.night.mode` when they leave. A Night change made then (edit mode, Control Center, the phone remote) goes into their mode if they have one, else the setting, so a person's choice never overwrites everyone's.
- Demo: `FaceProfileManager.DEMO_MAYA` is now Liquid Glass on **Dusk** in orange (Shared is Liquid Glass on Blue), so `profiles-demo` shows the wallpaper change.
- **Unverified on the Echo**: all of it (built and unit-tested only). To see it: `--es screen profiles-demo --es step personalized` (Maya on Dusk), then long-press for edit mode and tap the wallpaper pill; `--es screen widgets --es profile maya` for the Widgets page on her wallpaper. The pill's position (22, 410, 44 tall) wasn't checked against the face card's corner on screen.

## Nov 23 early morning: first StandBy ⇄ Widgets swipes after a start

**Problem.** Right after EchoHome starts, the first pager swipes between StandBy and Widgets dropped frames. Later swipes were smooth. AOT compiling (`cmd package compile -m speed`) didn't help, so it isn't JIT warm-up.

**Baseline** (release build of 9e452f4, stock SystemUI config, 3 runs). Each run: force-stop, HOME relaunches, wait 20 s, `gfxinfo reset`, then 3 × (`input swipe 800 240 150 240 250`, 1.2 s, `input swipe 150 240 800 240 250`, 1.2 s). The "warm" pass repeats that in the same process.

| | Frames | Janky | p50 / p90 / p95 / p99 |
|---|---|---|---|
| First 6 swipes, run 1 | 272 | 40.4 % | 6 / 24 / 97 / 150 ms |
| run 2 | 275 | 37.5 % | 6 / 19 / 61 / 150 ms |
| run 3 | 272 | 38.2 % | 6 / 20 / 61 / 150 ms |
| **median** | 272 | **38.2 %** | **6 / 20 / 61 / 150 ms** |
| Next 6 swipes, same process (median) | 210 | 4.3 % | 5 / 6 / 15 / 125 ms |

- PSS: 44.1 MB 20 s after the start (median of 41.5 / 44.1 / 45.1 MB). After the swipes, which build Widgets and Now Playing, it was 69.0 MB (65.5 / 69.0 / 69.4 MB).
- Time to first frame: the system relaunches HOME right after `am force-stop`, so `am start -W` reports `TotalTime: 0`. `ActivityTaskManager: Displayed app.vesper/.MainActivity` gave +1722 / 1682 / 1676 ms (median 1682 ms).

**Cause** (atrace with `gfx view input am dalvik sched freq res`, main thread):
1. **The Widgets page was built inside the first drag.** `ScreenPager` created a page's neighbours when it intercepted a drag. The first StandBy → Widgets move spent 160 ms in input (constructing `WidgetsScreen` and its two first widgets). The next frame spent 47 ms recording it.
2. **Idle-time warm-up ran between the frames of the pager's slide.** A `MessageQueue.IdleHandler` runs whenever the queue is empty, and the queue is also empty between animation frames. Widgets showed at the moment the finger lifted, at the start of the 380 ms settle. Its stacks then built the 14 missing widgets, one per idle moment (10–30 ms each, plus a 20–65 ms first record), so frames of 30, 41, 30, 32, 21, 58 and 66 ms ran every ~160 ms. StandBy does the same on every return: it rebuilds its released neighbour faces, so each pass had one 58 ms frame (the warm pass's p99 of 125 ms).
3. **The second swipe built Now Playing mid-drag.** Leaving Widgets also creates page 2. That cost 43 ms of input plus a 48 ms record.

**Fix** (measured on the Echo on Nov 23 evening, see below):
- `:core:design` `UiQuiet` + `QuietWork`. The screen is quiet when no finger is down and no frame has drawn for 50 ms (about three frames). `MainActivity` installs the frame listener and feeds touches. `QuietWork` runs one small step per idle moment, only while the screen is quiet, and waits again after each step. StandBy's face warm-up and the widget stacks' warm-up now use it instead of a bare IdleHandler.
- `ScreenPager` builds the current page's ±1 neighbours ahead, starting 2.5 s after it attaches and only on quiet moments while the pager rests. This keeps the first StandBy frame unchanged. It then records the neighbours' display lists off screen: it draws the page into a scratch `RenderNode`, so every child records its own node. It records again, on a quiet moment and at most once a second per page, when an off-screen neighbour invalidates (e.g. a widget built ahead).
- The widget stacks warm up from attach, not only while shown. All 16 widgets are built and recorded before the first visit.
- The baked Widgets wallpaper is uploaded with `Bitmap.prepareToDraw()` as soon as it's baked.

**After the fix** (release build of b4ff464, same 3-run procedure, Nov 23 22:20):

| | Frames | Janky | p50 / p90 / p95 / p99 |
|---|---|---|---|
| First 6 swipes, run 1 | 228 | 7.0 % | 5 / 7 / 17 / 53 ms |
| run 2 | 229 | 1.8 % | 5 / 6 / 7 / 48 ms |
| run 3 | 228 | 1.8 % | 5 / 6 / 8 / 48 ms |
| **median** | 228 | **1.8 %** | **5 / 6 / 8 / 48 ms** (before: 38.2 %, 6 / 20 / 61 / 150) |
| Next 6 swipes, same process (median) | 228 | 1.3 % | 5 / 6 / 6 / 48 ms (before: 4.3 %, p99 125) |

- PSS 20 s after the start: 59.7 MB (59.5 / 60.2 / 59.7), up from 44.1 MB, because Widgets and Now Playing are now built ahead (+15.6 MB, more than the 2–5 MB I expected). After the swipes: 67.8 MB (68.2 / 67.8 / 67.2), slightly below the 69.0 MB before. Device MemAvailable stayed ~384 MB.
- Time to first frame: 1811 / 1823 / 1737 ms (median 1811 ms, before 1682 ms; within run-to-run noise of a cold start on this device, not investigated further).
- The ~48 ms p99 frame in every pass is a single frame per pass; not traced yet.

## Nov 24: the pager follows the Screens setting (`:app`)

- `ScreenPager` pages are keyed by screen id (`ScreenPager(context, ids) { id -> view }`). `setPages(ids, current)` replaces the list live: pages that stay keep their views (the current one is only moved, not rebuilt or re-recorded), pages that left turn invisible at once and are removed one per quiet moment (first step of the existing `QuietWork` warm-up), and a page that comes back before it was freed is reused. New neighbours are built ahead on quiet moments as before; ahead-of-time work (`WARM_DELAY_MS`, prerender) is unchanged.
- `MainActivity` builds the list with `PagerScreens.pages(app.screens, settings["pager.screens"])` and re-applies it on `settings.changes`: current page kept if still listed, else StandBy (with `onScreenHidden` / `onScreenShown`). A hidden screen opens on top through `navigator.show` like any off-pager screen.
- Measured (details in settings.md): hiding 3 built pages on a resting page drew 3–5 frames, none janky; showing them again costs one ~61 ms frame on a resting page when the new neighbour is built ahead. All 11 pages visited: 98.5 MB PSS; 4 pages: 74.1 MB.

## Nov 24: near / far layout (NOTES §6.5 item 2)

From across the room StandBy shows one big clock and one fact; close up (walking up, touching) the normal face. The
camera decides (`SystemState.viewDistance`, see `docs/status/camera.md` "Near / far" for the signals, thresholds and
hysteresis); StandBy only follows it. Camera off, blocked or dark → always the normal layout.

- **Far layout** (`FarLayout.kt`; no artboard, derived from the StandBy designs: same type, larger, fewer elements):
  - **Digital, World, Solar, Weather, Calendar** give way to `FarClockView`: Digital's time (Inter, line-height .8,
    tracking −0.045em, the colon 0.01em apart and lifted 0.05em) at the largest size where four tabular digits fit
    between 48 px margins (≈338 px; Digital's is 252), Solar's weight 400, the others 600, in the face's colour (white
    for World / Weather), and under it **one fact** at 44/600 with a 40 px icon in the colour at 0.92: the next alarm
    if it rings within 12 h, else rain starting within the hour (nowcast), else the weather now ("47°  Cloudy"), else
    the date. `FarFact.pick` is pure and tested.
  - **Analog, Float, Liquid Glass** are already one big clock: they stay and fade their small details out
    (`FaceView.setFarAmount`, new in `:core:model`, default no-op): Analog's date, alarm **and second hand** (its 1 Hz
    redraw stops), Float's alarm line, Glass's date and alarm.
  - Night: everything #BE180E and the usual 0.42 dim; the Night sun/moon and the personal line fade out with the far layout.
  - Other features' faces (photos) have no far layout.
- **Transition**: 0.6 s `Motion.EASE`. For the big clock, the face fades out while growing 6 % and the far clock fades
  in from 0.9×, both on hardware layers for the duration (two textures whose alpha / scale change, no re-recording); at
  rest the layers are dropped, the face is `INVISIBLE` and stopped (`onScreenHidden`: Weather's drift stops), and only
  the far clock draws, once a minute. For the own-far faces the details' alpha is redrawn each frame (36 frames).
  Never during a touch, a face swipe or edit mode (it waits and retries every 150 ms); instant when StandBy is hidden.
- **Touch in far**: the camera already says NEAR on touch-down; StandBy swallows that gesture (no face swipe, long-press
  or tap action such as the Weather face's radar; Night still wakes) and goes near when the finger lifts.
  Horizontal swipes still page. Edit mode always shows the near face. While far, a recognised person's face switches
  in place instead of sliding.
- **Setting**: `clock.farLayout` "Big clock from across the room" (Clock & StandBy, default on, applied live). With it
  off the camera always says NEAR and StandBy never goes far.
- **Debug extras** (`--es screen standby`): `farlayout true|false` (the setting), and in debug builds
  `distance near|far|auto` (forces the layout over the camera; `--es faranim true` animates to it) and
  `distance cycle` (near ⇄ far every 2 s, 6 times, animated: measures the transition without `am start` competing).
- **Tests**: `FarLayoutTest` (9): styles and weights, the fact's priority (alarm ≤ 12 h, past alarm, rain soon vs
  later vs already raining, clear night/day, the date), `fade`.

Screenshots (`shots/clock/`, from the Echo, debug build, forced with `distance`):
`far-digital.png` (vs `far-off-digital.png`, the same moment near), `far-world.png`, `far-solar.png`,
`far-weather.png`, `far-calendar.png`, `far-analog.png` (vs `far-off-analog.png`), `far-glass.png`, `far-float.png`,
Night: `far-night-digital.png`, `far-night-analog.png` (vs `far-off-night-digital.png`), and
`far-transition-mid.png` (Digital → far, mid-transition).

**Measured** (Echo, debug build, `scripts/echo-perf.sh`, forced with `distance`, 20–30 s idle each):

| Face | Near | Far |
|---|---|---|
| Digital | 1.1 % CPU, 0 frames, PSS 64.3 MB | 1.2 % CPU, 0 frames, PSS 62.7 MB |
| Analog | 3.1 % CPU, 20 frames / 20 s (second hand) | 1.6 % CPU, 0 frames (second hand stops) |
| Liquid Glass | 1.6 % CPU, 0 frames | 1.8 % CPU, 0 frames |

So at rest the far layout costs nothing, and for Analog it saves the 1 Hz redraw. In auto mode with the presence
pipeline running, the setting on vs off measured 1.1 % vs 1.2 % CPU, 1 frame each, 76.2 MB both
(`docs/status/camera.md`).

Transitions (`distance cycle`: six animated changes in 13 s, no `am start` competing):

| Face | Frames | Janky | p50 / p90 / p99 | CPU over the 13 s |
|---|---|---|---|---|
| Digital (big clock, hardware layers) | 222 | 14.9 % | 5 / 18 / 31 ms | 11.5 % |
| Liquid Glass (details redrawn) | 216 | 0 % | 9 / 10 / 12 ms | 19.1 % |
| Analog (details redrawn) | 221 | 0.45 % | 10 / 12 / 15 ms | 23.2 % |

**Follow-up:** Digital's janky frames are most likely at the start of each transition: the hardware layers are built
and the far clock draws for the first time there. Pre-building the far clock's layer, or starting the alpha one frame
later, should fix that. Low priority: a real transition happens at most once per hold (45–60 s), usually once per
arrival.

**Unverified on the Echo:** the change being driven by the camera rather than the forced extra (see camera.md), and a
real person's touch while far. Calendar's and Solar's far views were checked in screenshots only, with no perf run.
