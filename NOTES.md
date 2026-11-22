# Vesper (was "Echo Bedside"): project notes

**Names (decided Nov 22, 2026):** the project is **Vesper** (the evening star: a bedside display that lives in the evening and the night). The device shows on the network as **"Echo Show"** (AirPlay, Bluetooth, the settings page; setting `device.name`). The code package `com.echobedside` and the module and script names get renamed in one commit after the agents' branches merge.

A replacement for the stock Echo Show 5 software. The jailbroken Echo runs LineageOS, and we build our own Apple-style ("Liquid Glass") smart-display UI on top: StandBy clock faces, Night mode, alarm, an audio-only AirPlay receiver with Now Playing, home controls, an AI assistant that replaces Alexa, timers and weather. The goal is a real alternative to Apple's HomeView hub (Oct 2026, $299).

Written Nov 22, 2026 at the end of the design phase. Next step: implementation (handed to Claude Code).

---

## 1. The device

| | |
|---|---|
| Device | Amazon Echo Show 5, 1st gen (2019), codename **checkers**, model H23K37 |
| SoC | MediaTek MT8163, quad-core Cortex-A53 @ 1.3 GHz, Mali-T720 GPU |
| RAM / storage | **1 GB** / 8 GB |
| Display | 5.5", **960×480** landscape, touch |
| Camera | 1 MP front, 720p, with a physical sliding shutter. **The camera is detected** (1 front camera via Camera1 + Camera2 APIs). The shutter reports a switch event (`SW_CAMERA_LENS_COVER`). |
| Buttons (top) | Volume up/down → `KEY_VOLUMEUP/DOWN` on `gpio-keys` (/dev/input/event6). **Mic/camera-off button → `KEY_POWER` on the `gating` device (/dev/input/event0)**, so Android treats it as a power press (see §5.3). The shutter → `SW_CAMERA_LENS_COVER` on `gpio-keys`. |
| OS | Unofficial **LineageOS 18.1 (Android 11 / API 30)**, build 18.1-20261101-UNOFFICIAL-checkers, **`userdebug`**, ABI **armeabi-v7a only** (32-bit). There's **no `su` binary**, but a userdebug build allows **`adb root`** / `adb remount` for system changes. Stock Launcher3 is the current home app. No third-party apps are installed. |
| GPU / display | Mali-T720, **OpenGL ES 3.1**. 960×480 at **~59 Hz**, density 195. |
| Memory | 997 MB total, **~336 MB available** with the stock launcher running. Budget the whole app (UI + AirPlay + voice) to well under 200 MB. |
| Sensors | **Ambient light sensor present** ("Light Sensor", amazon-oss, on-change). No motion sensors. |
| Network | Wi-Fi IP **192.168.10.136** (Nov 22, 2026; reserve it in the router). Wireless debugging is on, and `service.adb.tcp.port` = **5555**, so classic `adb connect 192.168.10.136:5555` may also work. Check whether it survives a reboot. |
| Hardware support | Reports say everything works except the front camera, but **this unit's report shows the camera detected**. Test an actual capture before applying the camera patch (§6.5). Full probe output: `device-report.md`. |
| Debugging | **Wireless debugging is enabled** (as of Nov 22, 2026), so adb works over Wi-Fi with no cable (see below). |

**Scripts (run from the echo-root folder, on the Mac):**
- `./scripts/echo-pair.sh`: one-time pairing. It finds the Echo's pairing service over mDNS (or asks for IP:port), asks for the 6-digit code, pairs, saves the IP to `.echo-ip`, then connects.
- `./scripts/echo-connect.sh`: connects every time after that. The wireless debugging port changes after each reboot, so it rediscovers it over mDNS. It saves the serial to `.echo-serial`, so you can use `adb -s "$(cat .echo-serial)" …`.
- `./scripts/echo-probe.sh`: writes `device-report.md`. It covers build/root, display/GPU, CPU/RAM, sensors (light sensor?), cameras, input devices and keys (`getevent -lp`), audio, the current home app and installed apps. It then records live key events while you press the mic/camera button and the volume buttons. **Read `device-report.md` before writing device-specific code.**

**Connecting manually (Android 11 wireless debugging):**
1. On the Echo: Settings → System → Developer options → Wireless debugging. Note the **IP:port** shown, and use "Pair device with pairing code" the first time.
2. From the Mac: `./platform-tools/adb pair <IP>:<pairing-port> <code>` (once), then `./platform-tools/adb connect <IP>:<port>`. The connect port is different from the pairing port and changes after a reboot.
3. Check with `adb devices`, then `adb install -r app.apk`, `adb logcat`, `adb shell`.
4. Optional, for a fixed port once connected: `adb tcpip 5555` (resets on reboot).
5. Wireless adb exposes the device on the LAN. Turn it off when not developing, and never on an untrusted network.

What's in this folder:
- `amonet/`: the amonet-checkers v2.0.2 jailbreak (bootrom exploit, TWRP, fastboot images) and an extracted copy.
- `rom/lineage-18.1-20261101-UNOFFICIAL-checkers.zip`: the ROM that's installed.
- `platform-tools/` (+ zip): adb / fastboot for macOS.
- `backups/`: empty right now. Back up partitions here before any risky flash.
- `design/`: the design source for every screen (see §2).

**The 1 GB of RAM is the main constraint.** The Echo should be a thin client: screen, mics and speaker. Heavy work (wake word, speech-to-text, the LLM, face recognition) runs on a server.

---

## 2. Design

**Live design canvas:** https://claude.ai/artifact/2bKaYfP9npn9KME9xuMshj ("Echo Bedside — Liquid Glass UI"). Press Play on an artboard to use it. Most boards are live and clickable.

`design/` holds a copy of every artboard's source as of Nov 22, 2026 (`*.dc.html` plus `canvas.json`, the board index). These files are a design-tool format: HTML/CSS markup with a small JS logic class (`class Component extends DCLogic`, where `renderVals()` returns the template values). They don't run on their own. Open the canvas link to see them, and read the files as an **exact spec**: every colour, size, radius, blur value, animation curve and state machine is in them.

All artboards are **960×480**, the Echo's native resolution, so the px values map 1:1 to device pixels.

### 2.1 Screen inventory

| Screen | File | Status | Notes |
|---|---|---|---|
| Digital clock | `Digital.dc.html` | Designed | Huge time in the chosen colour, plus a right column with date, weather and next alarm. Complications can be turned off. |
| Analog clock | `Analog.dc.html` | Designed | Apple's rectangular mid-century dial. Ticks and numerals follow a superellipse (n=5) that matches the screen. White hands, colored second hand. Date at 9, alarm at 3. |
| World clock | `World.dc.html` | Designed | Dotted world map (96×36 land mask encoded in the file) with live day/night from the sun's position, your location, and world-clock cities. No colour option, as in Apple's. |
| Solar clock | `Solar.dc.html` | Designed | Light-weight time beside a glowing sun rising off the lower-right corner. |
| Float clock | `Float.dc.html` | Designed | Giant rounded two-tone digits that overlap (screen blend). |
| Liquid Glass clock | `Glass.dc.html` | Designed | iOS 26 glass clock. Digits lens the wallpaper, with a specular rim and shadow. "Glass" 0–1 slider (clear to frosted) and tint. |
| Edit faces | `Customize.dc.html` | Designed | Long-press edit mode: face switcher (glass page dots), glass colour palette, Night toggle, Done. Imports the faces. |
| Night mode | `Night.dc.html`, `NightDigital.dc.html` | Designed | Any face in red (#BE180E), dimmed (~0.42), tap/motion to wake for 6 s. Analog and Digital versions. |
| Alarm | `Alarm.dc.html` | Designed | Ringing (sunrise glow, giant Snooze left / Stop right, as in iOS 26), Snoozed (live countdown), Good morning (weather, next event, lamp). |
| Now Playing | `NowPlaying.dc.html` | Designed | AirPlay receiver. Ambient colour from the artwork, live progress, seek, volume, glass AirPlay popover. Artwork shrinks when paused. |
| AI assistant | `Assistant.dc.html` | Designed | Glass voice "droplet". Ready, listening, working (tool steps), answer with result cards. Three scripted demos. |
| Home | `Home.dc.html` | Designed | Control Center-style glass tiles: thermostat, lamp slider, toggles, scenes, lock, garage. Room switcher only changes the header so far. |
| Weather face | `Weather.dc.html` | Designed | Sky follows the time of day. Hourly, sunrise and tomorrow-rain modules. |
| Timers | `Timers.dc.html` | Designed | Up to 3 live timers on glass Live-Activity-style cards. |
| Control Center | `ControlCenter.dc.html` | Designed | Pull-down from the top edge over a blurred face. Wi-Fi / Bluetooth / AirPlay / Spotify toggles, media tile, tall brightness and volume sliders, Do Not Disturb, Night mode, mic and camera (turn red when off), timer, and a "who's using it" row (manual switch + "Auto by face"). |
| System indicators | `HUDs.dc.html` | Designed | Glass capsule that drops from the top edge (under the physical buttons): volume, brightness, "Microphone off" (red), "Camera off · Shutter closed", "Microphone on" (green). Red privacy badges stay top-right while mic/camera are off. Tweak `show` to freeze one. |
| Notification banners | `Banners.dc.html` | Designed | Glass banner from the top with app tile, title, body. Tap to expand to a StandBy-size view readable across the room, with a tinted primary action (Reply by voice / Show camera / Join call) and Dismiss. |
| Profile switching | `Profiles.dc.html` | Designed | Empty room (dimmed, "Presence · 2 fps" indicator), then "Who's there?", then "Hi Maya" glass greeting with her alarm/events and a "Not me" button, then her own face and colour. See §6.6 for the low-power pipeline. |
| Calendar face | `Calendar.dc.html` | Designed | Big day/date + mini month on the left. Agenda for today and tomorrow with a live "now" line, past events dimmed, "in N min" badge. |
| Photo frame | `Photos.dc.html` | Designed | Full-bleed photos (CSS stand-in illustrations) from an Immich album with slow zoom and crossfade, time over the photo, memory caption. Tap shows glass controls (album, pause, next) that hide after 4 s. |
| Commute | `Commute.dc.html` | Designed | "Leave in N min", drive time and arrival, traffic and rain chips, stylised route map, alternatives (another road, bus). |
| Stocks | `Stocks.dc.html` | Designed | Featured chart with area fill + 5-symbol watchlist with sparklines and change pills. Market-closed state on weekends. **Prices are demo data.** |
| Echo Island (live) | `Island.dc.html` | Designed | Dynamic-Island-style live surface. Tweaks: `scene` (Story cycle, Timer, Music + timer, Assistant, Doorbell, Call, Now Playing expanded), `look` (Liquid Glass / Black), `background`. Tap to expand. See §5.4. |
| Echo Island (states sheet) | `IslandSheet.dc.html` | Designed | 1440×1060 spec sheet: all island states side by side, with sizes. |
| Lamp | `Lamp.dc.html` | **Old style** | Not yet redesigned to the current system. |

**All data in the designs is sample data:** weather, events, the "Santa Cruz" location, world-clock cities, the 7:00 alarm, and the tracks/artists/album art (all made up, original artwork). **"Hey Nova" is a placeholder wake word.**

### 2.2 Screens still to design

- **System:** first-run setup (Wi-Fi, phone pairing, face enrolment for profiles), software update, offline and error states, settings.
- **Communication:** full-screen incoming call and video call, full-screen doorbell with live camera, intercom/announcement. (The island already has compact/expanded doorbell and call states.)
- **Home:** camera grid, lock confirmation, thermostat detail.
- **Everyday:** reminders and shopping list, sleep sounds / wind-down.
- Redesign Lamp to the current system.

---

## 3. Design system

### 3.1 Liquid Glass rules (from Apple's WWDC25 "Meet Liquid Glass" + HIG)

1. **Glass is for the control layer only**: buttons, toolbars, popovers, sheets, page indicators. **Never put glass on content** (clock digits on StandBy faces, album art, weather data, content cards).
2. **No glass on glass.** Things sitting on a glass surface use fills/vibrancy, not another glass layer (for example, the swatches inside the colour capsule and the buttons inside the alarm sheet are plain fills).
3. **Quiet at rest.** Controls can "lift" into glass only while touched (Now Playing transport buttons do this).
4. **Tint only the primary action** (Snooze on the alarm, Done in edit mode, the "Move alarm" button). Tinting everything means nothing stands out.
5. **Menus pop out of the control that opened them** (colour capsule, AirPlay popover).
6. Glass needs something behind it to refract. Over pure black it reads only as rim and shadow, which is correct for StandBy.
7. **Clear vs regular glass:** only the Glass clock uses the clear variant, over a busy wallpaper with bold text.
8. Accessibility: reduce transparency means frostier glass; increase contrast means solid black/white with borders; reduce motion means no elastic effects.

### 3.2 The glass recipe used everywhere (CSS from the designs, to port)

```
background: rgba(255,255,255,0.09);                 /* tinted: e.g. rgba(255,149,0,0.58) */
border: 0.5px solid rgba(255,255,255,0.22);
backdrop-filter: blur(16px) saturate(1.8) brightness(1.15);
box-shadow: inset 0 1px 1px rgba(255,255,255,0.42),   /* specular top rim */
            inset 0 -1px 1px rgba(255,255,255,0.08),  /* bottom rim */
            0 10px 30px rgba(0,0,0,0.45);             /* lift shadow */
+ an overlay child: linear-gradient(160deg, rgba(255,255,255,0.26) 0%,
            rgba(255,255,255,0.04) 36%, transparent 64%, rgba(255,255,255,0.07) 100%)
```
Fully round shapes (capsules and circles). Inner radii are concentric with outer ones.

### 3.3 Colours

- StandBy background: pure black `#000`.
- Text: white; secondary `rgba(255,255,255,0.6–0.78)`.
- Night mode: everything `#BE180E` (≈3.3:1 on black, chosen to pass for large text), dimmed.
- Accent swatches (Apple system dark colours): `#FFFFFF #FF453A #FF9F0A #FFD60A #30D158 #64D2FF #0A84FF #BF5AF2 #FF375F`.
- Primary action tint: system blue `#0A84FF`. Alarm and timers: orange `#FF9F0A`. Success: `#30D158`.

### 3.4 Type

- System UI font (SF Pro on Apple; on Android use **Inter**, already referenced in the designs). Tabular numerals for all times.
- Float face: rounded heavy (designs use Nunito 900 as the stand-in for SF Pro Rounded).
- Typical sizes at 960×480: Digital time 252 px/600, Analog numerals 46 px/600, Alarm time 200 px, Glass time 300 px/700, titles 28–46 px, body 15–22 px.

### 3.5 Motion

- Springs: `cubic-bezier(0.3, 1.35–1.5, 0.5, 1)` for presses, art scaling and sheets.
- Live values tick every second with 1 s linear transitions (progress bars, ambient blobs).
- The assistant droplet changes shape with the voice level (8-value border-radius wobble, rotating colour gradient inside).

---

## 4. Architecture decision: app on LineageOS, not custom firmware

**Decision: build a launcher (HOME) app on the existing LineageOS.**
- The hard part of custom firmware is drivers. LineageOS already supports every component except the camera, and the device uses an armv7 downstream kernel.
- An Android app gets audio, video, Wi-Fi, Bluetooth and Android apps for free (for example, the AirPlay receiver below).
- Set it as the default HOME app so the device boots straight into it. Use kiosk / device-owner mode to hide system UI.
- Later, install it as a **privileged system app** (`/system/priv-app`, via `adb root && adb remount` on this userdebug build, after backing up) to get `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS` and so on without prompts. Also replace `/system/media/bootanimation.zip` with our own boot animation.

**Alternative noted:** TECHO5 (github.com/HuskerMinion/techo5) is open firmware that replaces Android with Alpine Linux and a single Go daemon, turning the Show into a Home Assistant voice satellite with its own touch screen. It works on checkers, but we'd lose Android apps (AirPlay and so on). Not chosen.

### 4.1 No Home Assistant dependency (user decision, Nov 22, 2026)

The app must work on its own, with **no Home Assistant**. Every feature talks to free public services or runs on the device:
- **Weather:** Open-Meteo (free, no key). Location (user decision, Nov 22, 2026): manual settings (`weather.lat`, `weather.lon`, `weather.place`) if set, else auto-detected by keyless IP geolocation (cached, refreshed at most daily), else **Livermore, CA** (37.6819, -121.7680). The Echo has no GPS and LineageOS without Google services has no network location, so IP geolocation is the practical source.
- **Calendar:** private iCal (.ics) URLs, e.g. Google Calendar's "secret address in iCal format" (`calendar.icsUrls` in secrets.properties).
- **Stocks:** a free keyless quote API (delayed quotes).
- **Commute:** a free routing service (no key). Traffic may not be available.
- **Voice assistant:** runs on the device plus Claude (§6).
- **Home Assistant** stays an *optional* integration, used only for smart-home controls (Home screen tiles, doorbell). Those parts hide themselves when it isn't configured. The client in `:core:data` stays for that.

---

## 5. Implementation notes for the app

### 5.1 Stack (recommendation, verify on device)
- Kotlin, targeting API 30. Keep memory low (1 GB total RAM).
- Jetpack Compose or plain Views. Profile on the device early: Mali-T720 is weak, and the UI must hold 60 fps for the clock faces.
- Don't render the HTML designs in a WebView in production. `backdrop-filter` and large blurs will be too slow on this GPU. Port them natively.

### 5.2 Liquid Glass on Android 11
- **Android's `RenderEffect` blur needs API 31 (Android 12). This device is API 30**, so there's no live backdrop blur.
- Workaround: the app draws its own backgrounds (wallpapers, album art, black), so **pre-blur the background once** (RenderScript intrinsic blur, or downscale plus a stack blur) and draw the matching region of the blurred bitmap inside each glass shape. Then add the tint fill, rim highlights (gradient strokes), the specular gradient and the shadow. It looks the same at almost no per-frame cost.
- Lensing on the Glass clock: draw the text with a shader that samples a slightly magnified and brightened copy of the wallpaper (BitmapShader with a scale matrix).
- Ambient Now Playing colours: extract a palette from the artwork (AndroidX Palette), draw 3 large pre-blurred circles and animate their translation only.

### 5.3 Volume / brightness / mic-camera displays (replace the stock UI)
- **Volume:** while our app is in the foreground, handle `KEYCODE_VOLUME_UP/DOWN` in `dispatchKeyEvent` and call `AudioManager.adjustStreamVolume(STREAM_MUSIC, dir, 0)`. Passing flags=0 (no `FLAG_SHOW_UI`) means the system panel doesn't appear. Then animate our own glass volume display.
  - For when another app is in front: an AccessibilityService can catch the keys, with an overlay window (the approach third-party volume-panel apps use), or a SystemUI `PLUGIN_VOLUME` plugin (replaces the volume dialog; plugins need a debuggable build, and **this build is userdebug**, so it's an option).
- **Brightness:** there are no brightness buttons. It changes only from our UI or automation. Set `Settings.System.SCREEN_BRIGHTNESS` (WRITE_SETTINGS) or the window brightness, and show our own display.
- **Mic/camera button (measured):** it sends **`KEY_POWER` from the `gating` input device** (/dev/input/event0). Out of the box, Android will treat it as the power button and sleep/wake the screen. Fix: remap that device's key layout so it sends a dedicated keycode instead.
  - Add `/system/usr/keylayout/gating.kl` (via `adb root && adb remount`), mapping its scancode (check with `getevent` without `-l`; KEY_POWER is usually 116) to an unused keycode.
  - Handle that keycode in our app: show the "Microphone off" display, set our mute state and show the persistent badge.
  - Find out whether the button also cuts the mics in hardware (the device is named "gating"). If it does, read the real state from sysfs rather than toggling in software.
- **Camera shutter (measured):** it reports **`SW_CAMERA_LENS_COVER`** on `gpio-keys`, so the shutter position is a real signal. Read it from the input device (or `dumpsys input`) to show "Camera off · Shutter closed" and stop the camera pipeline. Near-black frames are only a fallback. Slide the shutter during `getevent -l` to confirm the values.
- **Auto Night mode:** **the light sensor exists** (`Sensor.TYPE_LIGHT`, on-change). Switch to Night (red + dim) below a lux threshold with hysteresis (e.g. enter < 3 lux for 30 s, leave > 10 lux). Use it for auto-brightness too.

### 5.4 Echo Island (our Dynamic Island)

There's no camera cutout in the screen, so the island is pure software: one live surface **12 px below the top edge, centred**, where the physical buttons are. It shows ongoing things without covering the screen.

- **States** (sizes at 960×480):
  - Hidden when nothing is running.
  - **Compact** single activity, 44 px tall: timer 230 wide (ring + countdown), music 250 (art + equaliser bars), assistant 220 (colour orb + voice bars).
  - **Split**: main compact + a separate 44 px **bead** 8 px to the right for the second activity.
  - **Expanded**: Now Playing 660×160, timer 460×120, doorbell 640×196 (live camera thumbnail + Talk / Unlock / Dismiss), call 580×116 (Decline / Accept).
- **Behaviour:**
  - Tap compact to expand. Tap outside to collapse.
  - Alerts (doorbell, incoming call, alarm, timer done) expand on their own.
  - Hide an activity's island while its full screen is open (no music island on Now Playing).
- **Priority** (highest first): call, doorbell, alarm ringing, timer done, assistant, music, running timer, AirPlay connected. At most two visible (main + bead).
- **Motion:** animate bounds and corner radius with one spring, `cubic-bezier(0.3, 1.32, 0.5, 1)` over 0.6 s. Crossfade the contents, with the expanded content fading in 0.15 s late.
- **Looks:** "Liquid Glass" (default over wallpapers and photos) or "Black" (blends on black StandBy faces, like Apple's hardware-hiding island). Make it a setting. Night mode makes it red and monochrome with no glass.
- **Implementation:**
  - Our launcher owns every screen, so the island is the top layer of our own UI.
  - For when another app is in front, run it in an overlay service (`SYSTEM_ALERT_WINDOW`).
  - Model each item as a `LiveActivity` {id, kind, priority, compact leading/trailing, expanded layout, actions, staleAfter} that services publish to an IslandManager.
  - Sources: our timer, alarm and assistant services; the AirPlay receiver; Home Assistant events (doorbell, only if HA is configured); and other apps' media through `NotificationListenerService` + `MediaSessionManager.getActiveSessions()`.
  - For the glass look, use a pre-blurred snapshot of the region behind the island, re-taken only when the background changes. Never blur live.

### 5.5 Control Center, banners, system indicators

- **Pull-down:** the system status bar is hidden in kiosk mode, so detect our own swipe down from the top edge. The panel slides down over a blurred snapshot of the current screen. Toggles:
  - **Wi-Fi:** normal apps can't switch Wi-Fi on Android 10+. Needs the priv-app install or root (`svc wifi enable|disable`).
  - **Bluetooth:** `BluetoothAdapter.enable/disable`, allowed on API 30.
  - **Do Not Disturb:** `NotificationManager.setInterruptionFilter`, which needs notification-policy access.
  - **Mic:** `AudioManager.setMicrophoneMute` plus stopping the assistant's mic stream. The hardware button is separate (§5.3).
  - **Camera:** stop every camera user and show the red badge.
  - **Brightness:** `Settings.System.SCREEN_BRIGHTNESS`.
  - **Volume:** `AudioManager`.
  - **Night mode:** auto / off.
  - **Profiles:** manual avatars + "Auto by face".
- **Banners:**
  - Sources: **iPhone notifications via ANCS** (`:feature:phone`), Android notifications on the Echo via `NotificationListenerService`, our own services, and Home Assistant events if configured (optional).
  - Do Not Disturb hides banners except doorbell, alarm and calls (configurable).
  - Tap expands to the StandBy-size view. "Reply by voice" hands off to the assistant.
- **Indicators:** see §5.3. The persistent red mic/camera badges sit top-right. The island uses the top-centre, so they don't collide.

### 5.6 Data sources for the glance screens

- **Calendar:** private iCal (.ics) URLs fetched directly (Google "secret address in iCal format", iCloud public links), refreshed every ~15 min, cached on disk. CalDAV later if needed. No Home Assistant.
- **Photos:** the Immich server API (API key; album assets). Download 960-px previews, cache them on disk, prefetch the next photo. The slow zoom is a scale transform only (cheap).
- **Commute:** a free routing service with no key (drive time between the saved home location and the next calendar event's location; traffic only if a free source has it). The "Leave in N min" countdown is computed from the next calendar event's start time and the travel time.
- **Stocks:** a free keyless quote API (delayed quotes are fine).
- **Weather:** Open-Meteo (free, no key): current, hourly, daily high/low, sunrise/sunset, precipitation. Location: manual > IP geolocation > Livermore (§4.1).

### 5.7 Clock face details worth keeping
- Analog dial: for an angle θ, point = centre + d·t with d=(sin θ, −cos θ) and t = (|dx/a|^5 + |dy/b|^5)^(−1/5). Minute track a=446,b=222; numerals a=380,b=172. Ticks are rotated to the superellipse normal (see `Analog.dc.html → ring()`).
- World map: land mask rows are in `World.dc.html → rows()` (hex, 96 columns × 36 rows, 3.75°/dot, left edge 157.5°E, top 76°N). Day/night uses the sun's declination and longitude, with a smooth twilight band.
- Night mode: every face takes `night=true` and maps all colours to `#BE180E`.

---

## 6. Feature plan (subsystems)

| Feature | Plan |
|---|---|
| **AirPlay receiver (audio only)** | **Scope: AirPlay audio only. No screen mirroring or video.** That keeps CPU and RAM low and skips all video decoding. We need: the device appearing as an AirPlay 2 speaker, audio playback, metadata (title/artist/album/artwork/progress) for Now Playing and the island, remote control (play/pause/skip/volume back to the sender), and ideally multi-room sync. Options, in order of preference: (1) **shairplay** Rust library (docs.rs/crate/shairplay): AirPlay 1 + AirPlay 2 audio with metadata and remote control; video is a separate opt-in feature, so build it audio-only and call it from Kotlin via JNI (verify it cross-compiles to armv7 Android). (2) **android-airplay-server** (github.com/jqssun/android-airplay-server, UxPlay-based): a ready-made Android app, but it's built around mirroring, so check whether its audio-only mode is light enough. (3) **shairport-sync** is audio-only and the most mature, but it's built for Linux, so on Android it would need a rooted chroot/Termux setup with mDNS and an audio bridge. Treat this as a last resort. Whichever we pick, run it as a foreground service that publishes a MediaSession, so Now Playing, the island and Control Center all read from one place. **Built (agent C): shairplay via JNI. Default mode AirPlay 1 (user decision, Nov 22, 2026: no multi-room needed), because AirPlay 2 senders don't accept play/pause/skip from third-party receivers, while AirPlay 1 senders do (DACP). `airplay.mode=ap2` switches back.** |
| **iPhone notifications** | **ANCS** (Apple Notification Center Service) over Bluetooth LE: the Echo pairs once with the iPhone and receives every notification (app, title, preview), with no app on the phone. iPhone notifications become glass banners. Incoming calls show on the island and as a banner (caller, "Answer on iPhone") with Decline/Silence only: **calls are answered on the phone**, and no call audio goes through the Echo. Replies to messages aren't possible over ANCS. Requires the Echo's Bluetooth chip to advertise as an LE peripheral (verify first). `:feature:phone`. |
| **Bluetooth audio** | The Echo is already a Bluetooth speaker: this build has A2DP sink + AVRCP controller enabled. The phone's track info, artwork (AVRCP cover art if supported), position and controls go into `MediaHub` as source `BLUETOOTH`, so **Now Playing and the island work for Bluetooth as well as AirPlay**. `:feature:phone`. |
| **Spotify** | Spotify Connect via the Spotify app or librespot (to verify on Android/armv7). |
| **AI assistant (replaces Alexa)** | **No Home Assistant** (§4.1). Wake word, speech-to-text and text-to-speech run **on the device**. The suggested library is sherpa-onnx (Apache-2.0, armv7 Android builds), which covers keyword spotting, streaming STT and Piper voices for TTS; the Android `TextToSpeech` engine can be used if one is installed. The conversation agent is **Claude** through the Anthropic API (Haiku for speed), with tool use for local actions: timers, alarms, media, weather, Home controls if HA is configured. RAM is the risk: measure each model and keep the whole app under ~200 MB. Fall back to tap-to-talk if an always-on wake word costs too much. |
| **Quick start for voice** | Tap-to-talk first (tap the droplet), then add the on-device wake word ("Hey Nova" is a placeholder) once its CPU/RAM is measured. |
| **Home control** | **Optional.** Home Assistant WebSocket/REST API (lights, climate, scenes, locks, covers, cameras) when configured. Without it, the Home screen isn't shown. |
| **Weather / calendar** | Open-Meteo and iCal URLs, directly (§4.1, §5.6). |
| **Alarms** | Our own alarm engine (AlarmManager, exact alarms), with a sunrise ramp (screen brightness + warm colour ~30 min before), snooze of 9 min, and a Good-morning summary. |
| **Photos** | Photo frame face from the Immich server. |
| **Profiles** | Per-person content via face recognition on the server (see camera). |

### 6.5 Camera
- Front camera doesn't work on stock LineageOS. **github.com/jxlarrea/lineageos-echo-show-camera** makes it work on LineageOS 18.1. checkers (OV9734) is listed as **community verified**, needing the pinned `libdpframework` build the install scripts select. Apply this first.
- Uses, cheapest first:
  1. Presence: wake on motion, dim when the room is empty (low-res frame differencing).
  2. Distance-based layout: big clock when far, controls when near.
  3. Video calls / intercom between Echos (WebRTC).
  4. Optionally expose it as a Home Assistant camera (RTSP/MJPEG) if HA is used, respecting the mute button.
  5. Face recognition for profiles, on the server.
  6. QR scanning for setup and pairing.
  7. Simple motion-zone "gestures" (a wave to snooze). Full hand tracking is likely too heavy for this CPU.
- Always show a camera-in-use indicator. Off while the mute button is engaged.

### 6.6 Profile switching by face, built for a 1.3 GHz A53 with 1 GB RAM

Design rule: **the expensive step runs at most once per person arriving, never continuously.** Each stage only wakes the next. Targets (to verify on the device): a few percent average CPU, under ~40 MB RAM, camera released whenever idle.

1. **Gate:** run only when the camera is allowed (mute button off, camera toggle on) and outside quiet hours. If frames are near-black, the shutter is closed: stop and show "Camera off".
2. **Presence (always-on while gated):** lowest camera preview size, **Y (grey) plane only**, downscale to ~80×60, frame-difference at **~2 fps**. Almost free. This also drives wake-on-motion and dim-when-empty.
3. **Face detection (only after motion, for ~2 s):** a tiny detector. Candidates: TFLite BlazeFace short-range (128×128 input) with XNNPACK, or Android's legacy `android.media.FaceDetector`. Run at ~5 fps until a face is found or the burst ends.
4. **Quality filter:** only continue when the face is large enough, roughly frontal and not blurry. Most frames stop here.
5. **Recognition (once per arrival, ≤3 tries):** crop to 112×112 and compute an embedding with a MobileFaceNet-class **int8** TFLite model, then cosine-compare to the enrolled templates (3–5 per person).
   - **Licence (user decision, Nov 22, 2026):** this is a non-commercial, open-source project, so non-commercial-research weights (MobileFaceNet / ArcFace-family, e.g. InsightFace) are allowed. Record each model's source and licence in `android/feature/camera/MODELS.md`, and don't ship the weights in anything commercial.
   - Switch only after **2 consecutive matches** above threshold.
   - Don't re-run while the same person stays in view. Switch back to "shared" after N minutes with no presence.
   - Option: send only the 112×112 crop to a server on the LAN for the embedding step if on-device is too slow. Nothing is stored either way.
6. **Act:** show the "Hi Maya" sheet with "Not me". A rejection blocks that person for a few minutes. Apply that person's face, colour, alarm, calendar and music.
- **Privacy:**
  - No frames or crops are saved. Only embeddings are stored, encrypted on the device.
  - The indicator is visible whenever the camera samples.
  - Enrolment happens in settings, with 5 angles and explicit consent per person.
- The camera stack from the patch is an older (Android 7-era) HAL. Check which camera API (Camera1 / Camera2) gives low-res YUV frames most cheaply.

---

## 7. Open questions to check on the device

- [x] Mic/camera button emits an event: yes, `KEY_POWER` on `gating`. Needs a key-layout remap (§5.3).
- [x] Ambient light sensor: yes.
- [x] Build type: **userdebug** (adb root, remount, SystemUI plugins possible). No `su`. **`adb root` needs LineageOS's "Rooted debugging" toggle** (Developer options); it's off, and it's a security setting for the user to switch on. Without it `adb root` only restarts adbd (the wireless port changes).
- [x] System setup approved by the user (Nov 22, 2026): Camera app disabled (`pm disable-user com.android.camera2`) and lock screen disabled (`locksettings set-disabled true`): **done**. Priv-app install + mic key layout: **done Nov 22, 05:25** (`scripts/echo-system-install.sh`; user turned on Rooted debugging). Verified after the reboot: `/system/priv-app/Vesper` with PRIVILEGED (adb installs become UPDATED_SYSTEM_APP updates and keep it), BLUETOOTH_PRIVILEGED + START_ACTIVITIES_FROM_BACKGROUND granted, `KeyLayoutFile: /system/usr/keylayout/gating.kl`, HOME still ours. Undo: `scripts/echo-system-undo.sh`.
  - **Incident:** the backup before the install was incomplete (a script bug copied only `kb`, yet marked it complete), so the install ran without a full backup. The install only *added* three files (APK dir, allow-list XML, gating.kl), which the undo script removes. Earlier verified copies of recovery, swdl and kb exist in `backups/INCOMPLETE-20261122-051325`. Fixed the script (`5e8064a`) and took a full verified backup afterwards.
  - After this reboot the Echo offered plain adb on `:5555` instead of wireless debugging; `scripts/echo-connect.sh` falls back to it.
- [ ] Camera: detected by both camera APIs. Test a real preview/capture. Apply the camera patch only if capture fails.
- [ ] Slide the camera shutter during `getevent -l` and record the `SW_CAMERA_LENS_COVER` values.
- [ ] Does pressing the mic button currently put the screen to sleep? Does it cut the mics in hardware?
- [ ] Does `adb connect 192.168.10.136:5555` work, and does it survive a reboot?
- [ ] CPU/RAM headroom with the AirPlay receiver + our launcher + mic streaming running together.
- [x] Compose vs Views: **Views** chosen; Views + pre-blurred glass hold 59 fps with 7 ms frames (§8.1).
- [ ] Back up partitions (TWRP) into `backups/` before installing anything as a system app.
- [x] Wireless adb pairing from the Mac works (Nov 22, 2026). IP 192.168.10.136. Give it a DHCP reservation.
- [ ] Profile pipeline: measure presence-check and face-detection cost (CPU %, ms per frame) on the device before building the full flow.
- [ ] Overlay window (island over other apps) behaviour and performance on Android 11.

---

## 8. Suggested build order (for Claude Code)

0. **Setup:** on the Mac you need JDK 17 + the Android SDK (Android Studio or command-line tools; check what's installed). Create a Kotlin app: `minSdk 30`, `targetSdk 30`, ABI `armeabi-v7a` only, landscape 960×480. Install it with `./platform-tools/adb -s "$(cat .echo-serial)" install -r …`. Register a HOME activity and make it the default launcher. Keep Launcher3 installed as a fallback.
1. **Shell + clocks:** full-screen launcher, Digital face first, then Analog / World / Solar / Float / Glass. Edit mode (long-press). Night mode driven by the light sensor.
2. **System layer:** volume keys handled in-app (no system panel), the `gating` power-key remap for the mic/camera button, the shutter switch, indicators (HUDs), Control Center, and the island framework (§5.4) with its LiveActivity model.
3. **Audio AirPlay receiver** (§6) as a foreground service with a MediaSession, then Now Playing and the island music state.
4. **Alarms + timers** (exact alarms, sunrise ramp, snooze), then the Alarm and Timers screens.
5. **Glances without Home Assistant:** Weather (Open-Meteo), Calendar (iCal), Commute (free routing), Stocks (free API). Optional HA Home screen + doorbell.
6. **Voice assistant:** on-device wake word/STT/TTS + Claude API, then the Assistant screen.
7. **Camera:** presence (wake/dim) first, then profile switching (§6.6) once the cost is measured.
8. **Photos (Immich), Stocks,** then the remaining screens in §2.2.

Profile and measure on the device after every milestone. Keep RAM under ~200 MB for the whole app, and hold 60 fps on the clock faces.

### 8.1 Status

- **Milestone 0 done (Nov 22, 2026).** Toolchain on the Mac: Homebrew `openjdk@17` (the system default is JDK 27, too new for the build; `scripts/lib.sh` sets `JAVA_HOME`), Android command-line tools at `/opt/homebrew/share/android-commandlinetools` (platform android-35, build-tools 35), Gradle 9.9.2 wrapper, AGP 9.5.2 with built-in Kotlin. Package `com.echobedside`, minSdk/targetSdk 30, armeabi-v7a. OkHttp is pinned to 4.12 because 5.x needs compileSdk 37.
- **Milestone 1, Digital face done:** it's the HOME screen on the Echo, with the Inter v4.1 variable font bundled (OFL; opsz follows px size like CSS `font-optical-sizing: auto`). Long-press is reserved for edit mode. The dev escape hatch is **two fingers held for 2 s → Android Settings**.
- **UI toolkit decision: plain platform Views + Canvas, no Compose, no AndroidX** (coroutines/StateFlow only). Measured on the Echo, Nov 22, 2026:
  - Digital face, release build: **17 MB PSS**, 0% CPU idle, one frame per minute.
  - Same in a debug build: 28 MB PSS.
  - Debug `bench` screen (`--es screen bench`): full-screen wallpaper, pre-blurred backdrop, three moving glass components and 252 px text redrawn every frame. **59 fps: frame time p50/p90/p99 = 7/8/9 ms, 0.16% janky**, 42% of one core (debug build), 80 MB PSS (two 960×480 bitmaps plus GPU textures).
  - So Views + pre-blurred glass hold the panel rate with headroom. Compose wasn't measured; it would add ~10–20 MB and startup cost for nothing we need.
- **Phase 0 of PARALLEL_BUILD_PROMPT.md done:** git repo, multi-module project (`:app`, `:core:design|model|data`, nine `:feature:*` modules), contracts + fakes, device scripts. Read `docs/architecture.md` for the module map and rules.
  - `./scripts/build-install.sh`: build + lock + install + HOME + launch + shot/perf.
  - `./scripts/echo-perf.sh`, `echo-shot.sh`, `echo-lock.sh`, `echo-adb.sh`.
  - `./scripts/echo-deploy.sh`: release build.
  - `./scripts/echo-restore-launcher.sh`: Launcher3 as HOME again.
  - Launcher3 keeps running in the background (~140 MB RSS) because it's the system recents provider. Look at this when RAM gets tight.
