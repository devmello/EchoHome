# EchoHome — app architecture (for every agent)

Project name: **EchoHome** (was Vesper until Nov 23, 2026; the code still uses the `vesper` names). Package `app.vesper` (renamed from `com.echobedside` after integration). Note: a local named `app` shadows the package root, so import `app.vesper.…` classes instead of writing the full path inline. The device shows on the network as **"Echo Show"** (`DeviceName`, setting `device.name`).

Read this after `CLAUDE.md` and `NOTES.md`. The Android project is in `android/`.

## Modules

| Module | Owner | What |
|---|---|---|
| `:app` | lead | HOME activity (`MainActivity`), `VesperApp` (builds `VesperServices`, installs features), `ScreenPager`, navigator, debug `bench` screen |
| `:core:model` | lead | Contracts: `VesperFeature`/`FeatureHost`, `VesperServices`, `LiveActivity`/`IslandManager`, `MediaHub`/`MediaState`/`MediaControls`, `SystemState`/`SystemControls`, `AlarmStore`/`TimerStore`, `Profile`/`ProfileManager`, `NotificationFeed`/`Banner`, `FaceSpec`/`FaceRegistry`, `WeatherProvider`/`CalendarProvider`, `HomeAssistant`, `SettingsStore`. Fakes in `core.model.fake`. `observeWhileAttached` helper. |
| `:core:design` | lead | `Fonts` (Inter variable), `SpecText` (CSS text layout 1:1), tokens (`VesperColors`, `Night`, `Motion`, `GlassTokens`), `PreBlur` + `Backdrop`, `GlassPainter`, `GlassLayout`, `GlassSlider`, `GlassTracker` |
| `:core:data` | lead | `PrefsSettingsStore`, `loadSecrets()`, and the optional `HomeAssistantClient` (OkHttp WebSocket + REST) |
| `:feature:clock` | A | faces, edit mode, Night mode |
| `:feature:system` | B | volume/mic/shutter, HUDs, Control Center, banners, island renderer |
| `:feature:phone` | J | iPhone over Bluetooth: ANCS notifications + calls (answered on the phone), Bluetooth speaker (A2DP sink/AVRCP) Now Playing |
| `:feature:airplay` | C | AirPlay audio receiver |
| `:feature:media` | D | Now Playing, media island states, other apps' sessions |
| `:feature:alarm` | E | alarms, timers |
| `:feature:lists` | L | reminders (exact, persisted, chime + island + banner) and lists (shopping, to-do), the `lists` screen |
| `:feature:home` | F | weather (Open-Meteo) + calendar (iCal) providers, commute, stocks (free keyless API), optional HA Home screen + doorbell |
| `:feature:assistant` | G | voice assistant: on-device wake word/STT/TTS + Claude API |
| `:feature:camera` | H | presence, profiles |
| `:feature:photos` | I | Immich photo frame |
| `:feature:settings` | K | web settings page served by the Echo (phone/Mac browser), built from `services.settingsSchema` |

Feature modules depend on `:core:model` and `:core:design` only (add `:core:data` if you need it). **Never on each other** — talk through the contracts in `VesperServices`.

## Contracts added Nov 22 evening (lead)

- `services.travel: TravelEstimator` (home binds) · `services.phone: StateFlow<PhoneStatus?>` (phone binds) · `services.crashLog: CrashLog` (`:app` watchdog binds).
- `Weather.air: AirQuality?` (US AQI, UV, pollen where available) and `Weather.nowcast: Nowcast?` (15-min precipitation, `minutesUntilRain()`).
- `CalendarEvent.profileId` (whose calendar; per-person iCal URLs live in the SecretStore under `Secrets.calendarKeyFor(profileId)`).
- `Alarm.leaveBy` / `readyMinutes` / `skipHolidays`; `NextAlarm.reason`.
- Island: `ActivityKind.LEAVE_NOW` / `WEATHER_ALERT` / `PHONE_BATTERY` with `LiveContent.LeaveNow` / `RainSoon` / `PhoneBattery` (`:feature:system` renders them).
- `Banner.actions` (extra expanded-banner buttons) and `NotificationFeed.recent` (last 50 banners, including gone ones).

## Contracts added Nov 23 (per-person preferences)

- `Profile.wallpaper` / `nightMode` / `alarmSound` (all optional, null = follow Shared, i.e. the settings `clock.glass.wallpaper`, `clock.night.mode`, `alarm.sound`). `FaceConfig.wallpaper`: StandBy passes the recognised person's wallpaper to faces with a background.
- `Alarm.profileId` is used by the Alarms screen, the ringing screen, voice and the web page; every alarm still rings whoever is in the room.

## Contracts added Nov 23 (lists branch)

- `services.reminders: ReminderStore` and `services.lists: ListStore` (`:feature:lists` binds; in-memory defaults). `Reminder.profileId` is optional, taken from `services.profiles.active`.
- `ReminderTime` (pure parser and formatter for "at 7pm", "in 20 minutes", "tomorrow morning") and `ListText` / `ListNames` (de-dup keys, list names) in `:core:model`, shared by the store, the assistant tools and the web sync.
- Island: `ActivityKind.REMINDER` / `REMINDER_MISSED` with `LiveContent.Reminder` (`:feature:system` renders them).

## How a feature plugs in (no `:app` edits)

Each module has `object XxxFeature : VesperFeature`. `:app` calls `install(host)` once per process, in order setup, system, phone, airplay, media, alarm, home, assistant, camera, photos, clock, settings. **`SetupFeature` (the first-run gate, in `:feature:settings`) stays first:** it tells a fresh install from a set-up one by reading the settings store before any feature writes to it. In `install`:

- `host.registerScreen(ScreenSpec(id, order, inPager) { ctx -> MyScreenView(ctx, host.services) })`. Pager order: StandBy is 0; pick 10, 20, … for yours. `inPager = false` = only opened by `services.navigator.show(id)` (e.g. alarm ringing, Control Center-style full screens).
- `host.registerOverlay(OverlaySpec(id, z) { ... })` for layers above every screen (island 300, banners 400, HUDs 500, Control Center 600). **Overlays are full-screen views: empty areas must not consume touches** (return false), so the screens below still get them.
- `host.registerKeyHandler { event -> ... }` for hardware keys.
- `host.registerFace(FaceSpec(...))` for StandBy faces.
- `host.registerSettings(SettingsSection(id, title, order, listOf(SettingSpec(key, title, type, default, help…))))` for **every user-facing setting your feature reads**. The web settings page (`:feature:settings`, opened from a phone or Mac at the Echo's address) builds its UI from these. Apply changes live from `services.settings.changes` (no app restart). Secrets (API keys, private URLs) are `SettingType.SECRET` in `services.secrets`; read `services.config.secrets` at use time, never once at install. Debug-only keys don't belong here.
- Bind the services you own: `host.services.alarms = MyAlarmStore(...)` (see the slot list in `Services.kt`). Read other slots lazily — in factories / on attach — never during `install`.
- Manifest entries (permissions, services, receivers) go in **your module's** `AndroidManifest.xml`; they merge into the app.

Screens and overlays may implement `VesperScreen` to get `onScreenShown(args)` / `onScreenHidden()`. **Stop animations, sensors and the camera in `onScreenHidden`** — nothing may draw frames off screen.

## Rules that keep it fast (1 GB RAM, Mali-T720)

- Plain platform Views + Canvas (decided in milestone 1, numbers in NOTES §8.1). **No Compose, no AndroidX** (no appcompat/lifecycle/recyclerview). Kotlin coroutines + StateFlow are available; collect with `view.observeWhileAttached(flow) { … }`.
- Spec px are device px: use the numbers from `design/*.dc.html` directly (Views take px; don't convert to dp). Text: `SpecText(sizePx, weight, trackingEm)` matches the browser's layout.
- Draw only when something changes. Clock faces redraw on the minute tick. Live values tick once a second with 1 s linear transitions (NOTES §3.5).
- Glass: `GlassLayout` / `GlassSlider` / `GlassPainter`. Over a wallpaper or artwork, blur it **once** with `PreBlur.backdrop(bitmap, 960f, 480f)` (off the main thread for big images) and set `glass.backdrop`. Never blur per frame. Over black, no backdrop (rim + fill only). `GlassPainter`'s drop shadow is blurred once per shape size into a small cached bitmap and drawn as a 9-slice (a few bitmap draws per frame), so leave it on, also on moving glass. For a big static layout, baking the shadows into the backdrop bitmap (`shadow = false`, see `:feature:home`'s `HomeWallpaper`) still saves those draws. For an overlay that redraws often (Control Center), record each tile into its own `RenderNode` and re-record it only when that tile changes.
- Bitmaps: decode at the size you draw (960×480 max), `RGB_565` where there's no alpha, recycle what you replace. Budget: the whole app < 200 MB PSS.
- Night mode: `services.system.state.value.nightActive`; map colours with `Night.map(color, night)`; glass becomes a red outline (`GlassPainter.night`).

## Running on the Echo (one device, many agents)

- `./scripts/build-install.sh [--screen ID] [--extra '--es k v'] [--shot out.png] [--perf 10] [--then 'cmds']` — builds the debug APK in *your* checkout, takes `/tmp/echo-device.lock`, installs, makes it HOME, launches (opening your screen), screenshots / measures / runs your commands, releases the lock.
- `./scripts/echo-lock.sh run 'cmds'` for any multi-step interaction (`$ADB -s $SERIAL shell input tap x y`, swipes, `screencap`). `./scripts/echo-lock.sh status` shows who holds it.
- `./scripts/echo-adb.sh <args>` for read-only one-offs (`logcat -d`, `dumpsys`, `getevent -lp`).
- `./scripts/echo-perf.sh [SEC] [--then 'cmds']` prints PSS / CPU / frame stats as Markdown for your status file.
- Launch extras: `--es screen <id>` (others become `args`), `--es face <id>`, `--ez demo true|false` (sample data on/off; restarts the process).
- The device ends up running whichever build was installed last, so always reinstall your build before screenshotting (build-install does).
- Escape hatch on the device: hold two fingers still for 2 s → Android Settings.
- **Never**: flash, `adb remount`, write `/system`, `adb root` for system changes, change partitions — ask the lead, who asks the user.

## Shared files

- `docs/contract-requests.md` — need a `:core` change? Append a request (who, what, why, proposed signature) and keep going with a local stub in your module. The lead updates `:core` on main and tells you to rebase.
- `docs/status/<agent>.md` — your status: done, left, device measurements, open questions, needs-from-user.
- **No Home Assistant dependency** (NOTES §4.1): every feature must work without HA. `services.homeAssistant` is `NotConfiguredHomeAssistant` unless the user configures it; only optional smart-home parts may use it.
- Secrets (all optional): `android/secrets.properties` (git-ignored; see `secrets.properties.example`): Anthropic key, calendar iCal URLs, Immich, optional HA. Read via `services.config.secrets`. Never log or commit them.
- Tests: JVM unit tests in `src/test` (`./gradlew :feature:x:testDebugUnitTest`). Android's `org.json` is stubbed in unit tests; add `testImplementation(libs.json)` if you parse JSON.

## Build

```bash
cd android && ./gradlew :feature:<x>:assembleDebug :feature:<x>:testDebugUnitTest
```
Needs JDK 17 (`scripts/lib.sh` sets `JAVA_HOME` / `ANDROID_HOME`; from a plain shell: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`).
