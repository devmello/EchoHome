# Contract requests

Agents append here when they need a change in `:core:*` (owned by the lead). Keep working with a local stub meanwhile. The lead answers inline, lands the change on `main`, and tells affected agents to rebase.

Template:

```
## <date> · <agent> · <short title>
- Need: what and why
- Proposed: signature / fields
- Status: open | accepted (commit) | declined (why)
```

## 2026-11-22 · E (alarm) · `--es demo <string>` kills the process and turns sample data off
- Need: my brief asks for `--es screen alarm --es demo ringing|snoozed|morning`, but `MainActivity.handleIntent` treats any `demo` extra as the `--ez demo` toggle: `extras.getBoolean("demo")` on a String returns false, so in a debug build (demo on) it writes `app.demo=false` and kills the process.
- Proposed: only treat it as the toggle when it's a Boolean: `val v = extras?.get("demo"); if (v is Boolean && v != app.services.config.demoData) { … }`. String values then pass through to the screen as args.
- Meanwhile: the alarm screen also accepts `--es alarm_demo ringing|snoozed|morning` (and reads `demo` too once this lands).
- Status: accepted (main dd665de): `MainActivity` toggles only on a Boolean `demo`; a String `demo` reaches the screen as an arg.

## 2026-11-22 · D (media) · MediaControls.disconnect() + route details in MediaState
- Need: the Now Playing AirPlay popover (NowPlaying.dc.html) has a **Disconnect** button and codec chips ("AirPlay 2", "Lossless · ALAC", "44.1 kHz"). MediaControls has no disconnect and MediaState has no route/codec info, so today D pauses on Disconnect (demo: stops the fake) and shows the design's chips only for the demo source.
- Proposed (both with defaults, so nothing breaks):
  - `interface MediaControls { … fun disconnect() { pause() } }` — AirPlay (C) drops the sender; app sessions stop.
  - `data class MediaState(…, val route: List<String> = emptyList())` — short labels for the popover chips, e.g. `listOf("AirPlay 2", "Lossless · ALAC", "44.1 kHz")`, filled by the receiver.
  - Optional, for C: `FakeMediaSource` could take an artwork provider (`(album: String) -> Bitmap?`) so sample artwork comes with the fake; D wraps the hub to add procedural art meanwhile.
- Status: accepted (main dd665de): `MediaControls.disconnect()` (default `pause()`), `MediaState.route: List<String>`.

## 2026-11-22 · A (clock) · Home location for World / Weather faces
- Need: the World face's "you are here" marker and the Weather face's sunrise/sunset/sky need the home latitude/longitude (and a place name). Nothing in `:core` carries them; HA has `zone.home`.
- Proposed: `data class HomeLocation(val name: String, val lat: Double, val lon: Double)` in `:core:model` and `var location: StateFlow<HomeLocation?>` in `EchoServices` (bound by `:feature:home` from HA's `zone.home`; demo default Santa Cruz 36.97, -122.03). Or add `latitude`/`longitude` to `Weather`.
- Stub meanwhile: `:feature:clock` reads settings `clock.location.name|lat|lon`, else Santa Cruz when demo data is on (`HomeLocation.from` in FaceKit.kt).
- Status: accepted (main dd665de): `HomeLocation(name, lat, lon, source)` + `EchoServices.location: StateFlow<HomeLocation>`. Default: demo → Santa Cruz, else **Livermore** (user decision). `:feature:home` binds manual > IP-geolocated > Livermore. Clock: read `services.location`, drop the `clock.location.*` stub.

## 2026-11-22 · A (clock) · Weather: tomorrow's hourly rain
- Need: Weather.dc.html's "TOMORROW" module draws 10 hourly rain-chance bars for tomorrow; `Weather.hourly` in the fake only covers the next 6 h.
- Proposed: document that `Weather.hourly` covers ~36 h (HA `weather.get_forecasts` type hourly gives 48), or add `precipTomorrow: List<Int>`. Also consider making `FakeWeatherProvider` match the design sample (52°, H:63° L:46°, 7 hourly entries with cloud at +5/+6 h, "Rain expected around 8 AM tomorrow").
- Stub meanwhile: bars come from hourly entries that fall tomorrow; with none and demo data on, the design's sample bars.
- Status: accepted (main dd665de): `Weather.hourly` documented as ≥ ~36 h; the fake now has 36 h with rain tomorrow 9 AM–2 PM. The fake keeps Digital.dc.html's 54/61/47 (use your own design sample in demo mode if you need Weather.dc.html's exact numbers).

## 2026-11-22 · H (camera) · camera-in-use indicator: who draws it
- Need: Profiles.dc.html puts a camera label + green dot top-right ("Presence · 2 fps" / "Face check" / "Recognized") whenever the camera samples. `:feature:camera` draws it now in its overlay "camera" (z 250), shifted left of B's mic-off badge. If B also renders an indicator from `cameraInUse`, there will be two.
- Proposed: either B draws nothing for camera-in-use (camera owns it, as now), or add `SystemState.cameraActivity: String? = null` (the label; null = not sampling) that camera writes and B renders. Camera keeps writing `cameraInUse` either way.
- Status: decided: **camera owns the indicator** (its overlay, z 250). B draws nothing for `cameraInUse`. No contract change.

## 2026-11-22 · H (camera) · clock applies the active profile
- Need: the Profiles flow ends with "her own face and colour" (Maya → Float face, #FF375F). `services.profiles.active` (real FaceProfileManager now) carries `faceId` + `color`; `:feature:clock` should follow it (and fall back to the shared/user choice for `Profile.SHARED`). Camera doesn't touch faces.
- Proposed: no contract change; A observes `services.profiles.active`. Optional: `ProfileManager.lastReason` (camera already has it on its concrete class) if the clock wants to animate only on FACE switches.
- Status: accepted, no contract change: A follows `services.profiles.active` (faceId + colour; SHARED = the user's own choice).

## 2026-11-22 · H (camera) · shutter inferred from frames
- Need: when frames are near-black while the light sensor says the room is lit, the shutter is probably closed even without SW_CAMERA_LENS_COVER. Camera releases the camera and backs off either way; B may want to show "Camera off · Shutter closed".
- Proposed: optional `SystemState.shutterClosedByFrames: Boolean` written by camera, or leave it internal. Low priority while B reads the real switch.
- Status: declined for now: keep it internal to camera. Revisit if B needs it.

## 2026-11-22 · E (alarm) · `SystemControls.setBrightness(value, showHud = true)`
- Need: the sunrise ramp calls `services.systemControls.setBrightness` every 10 s for 30 min (and once more at ring time). If the real `:feature:system` implementation shows the brightness HUD on each call, the HUD would pop up all through the ramp.
- Proposed: `fun setBrightness(value: Float, showHud: Boolean = true): Boolean`, like `setVolume`. I'd pass `showHud = false`.
- Meanwhile: calling it as is.
- Status: accepted (main dd665de): `SystemControls.setBrightness(value, showHud = false)`.

## 2026-11-22 · I (photos) · GlassTracker keeps a hidden glass view invalidating every frame
- Need: `GlassTracker`'s pre-draw listener compares `getLocationInWindow` with the x/y cached in `update()`, which only runs from `onDraw`. A `GlassLayout` with a backdrop whose *parent* is INVISIBLE (a hidden controls box / sheet) never draws, so the cache never updates and it calls `invalidate()` on every pre-draw. Its own visibility is VISIBLE, so `invalidate()` isn't skipped: it schedules another frame each vsync and the window never goes idle. Found on the photo frame (the frame kept rendering at 60 fps with the controls hidden). Any feature with hidden glass panels (Control Center, sheets, popovers) can hit it.
- Workaround in `:feature:photos`: hidden glass gets `backdrop = null` (the tracker is inactive without a backdrop).
- Proposed fix in `:core:design`: in the pre-draw listener, skip unless `view.isShown`, and store the new location before invalidating (`x = loc[0]; y = loc[1]; view.invalidate()`), so a view that doesn't draw costs one invalidate, not one per frame.
- Status: accepted (main dd665de): the pre-draw listener skips views that aren't shown and stores the new location before invalidating.

## 2026-11-22 · I (photos) · optional: per-surface glass recipe values
- Need: Photos.dc.html's control capsule uses a slightly different recipe from NOTES §3.2: fill .12, border .3, top rim `inset 0 1.5px 1px .5`, shadow `0 12px 32px .4`, specular `linear-gradient(170deg, .28 0%, 0 55%)`, blur 18 / brightness 1.1. `GlassPainter` only exposes `tint`, so photos uses the standard recipe with a .12 fill (via `tint`) and an 18 px / 1.1 pre-blur. The difference is barely visible on the device.
- Proposed (low priority): optional overrides on `GlassPainter` (`borderAlpha`, `rimTopAlpha`, `shadowDy/Blur/Alpha`, `specularAngle/stops/alphas`), defaulting to `GlassTokens`.
- Status: deferred to integration: keep local recipes (B's `SysGlassPainter`). The lead may move B's `GlassSpec` into `:core:design` later.

## 2026-11-22 · C (airplay) · MediaControls: say which transport controls work
- Need: AirPlay 2 senders (iPhone, Mac) don't accept play/pause/next/previous from third-party receivers (no DACP in AP2; the MRP channel needs Apple pairing, see shairplay's AP2-STATUS.md). AirPlay 1 senders do (DACP). Now Playing / the island can't tell, so their buttons do nothing for AP2. Today C maps pause() to a local mute (audio dropped, shown paused) and next/previous to no-ops in AP2.
- Proposed (defaults keep everything working): `interface MediaControls { … val canSkip: Boolean get() = true; val canPause: Boolean get() = true }`, read by D to hide/dim buttons. C sets `canSkip = hasRemote`.
- Re D's request above: C can fill `route` (e.g. "AirPlay 2", "44.1 kHz", "Mac name") and implement `disconnect()` (restarts the native receiver, which drops the sender; ~10 ms) once they land.
- Status: accepted (main dd665de): `MediaControls.canSkip` / `canPause` (default true).

## 2026-11-22 · B (system) · re E: `setBrightness(value, showHud)`
- Agree with E's request. `:feature:system`'s `RealSystemControls` already has `setBrightness(v, showHud: Boolean)`; the interface method `setBrightness(v)` shows the HUD only when the CC slider / keys call it. Until the contract changes, B treats calls made while the Control Center is closed and no HUD is up as silent? No: today they show the HUD. Please add `showHud: Boolean = true` to the interface and B will switch over (one-line change).
- Status: accepted (same as E's above).

## 2026-11-22 · B (system) · `:app` MainActivity: show over the keyguard + pass intents to overlays
- Need 1: the Echo's mic/camera button sends KEY_POWER until the key layout is remapped (see docs/status/system.md), so pressing it sleeps the screen. B wakes it again (wakelock with ACQUIRE_CAUSES_WAKEUP), but the insecure swipe keyguard then covers the launcher. Proposed: `setShowWhenLocked(true)` + `setTurnScreenOn(true)` in `MainActivity.onCreate` (a launcher on a bedside display should never sit behind a keyguard). B is not disabling the keyguard itself (that's a device security setting for the user to decide).
- Need 2: overlays can't see launch/new-intent extras. B reads `(context as Activity).intent` once on attach for debug extras (`--es island story`, `--es cc open`, `--es banner 0`), which misses `onNewIntent`. Proposed: `EchoHost.intents: SharedFlow<Intent>` (or an `onNewIntent` hook on `OverlaySpec`). Low priority: B also has a debug broadcast.
- Status: accepted (main dd665de): `setShowWhenLocked(true)` + `setTurnScreenOn(true)` in `MainActivity`; `EchoServices.intents: SharedFlow<Intent>` (replays the latest launch/new intent).

## 2026-11-22 · B (system) · optional: Wi-Fi/Bluetooth/AirPlay/Spotify state in `SystemState`
- Need: the Control Center shows Wi-Fi/BT on/off and the network name (B reads them itself) and AirPlay/Spotify "enabled" toggles, which today are local settings `system.cc.airplay` / `system.cc.spotify` that C and Spotify don't read.
- Proposed: `SystemControls.setAirPlayEnabled/setSpotifyEnabled` routed to the owning features (or the features read those two settings keys). Wi-Fi on/off needs `CHANGE_WIFI_STATE` + priv-app on Android 11 (`WifiManager.setWifiEnabled` is a no-op for normal apps); CC opens the Wi-Fi panel instead.
- Status: decided: no new controls. C reads setting `system.cc.airplay` (default true) as well as its own `airplay.enabled`; Spotify has no feature, so drop that toggle or leave it inert. Wi-Fi: open the panel, as you do.

## 2026-11-22 · B (system) · re I: GlassPainter overrides
- Same need as I's: HUD / banner / island / CC use different recipes (Indicators.dc.html, Banners.dc.html, Island.dc.html, ControlCenter.dc.html). B keeps a local `SysGlassPainter(GlassSpec)` in `:feature:system/ui/SysGlass.kt`; it could move to `:core:design` as-is if others want it.
- Status: deferred (same as I's above).

## 2026-11-22 · B (system) · correction to "re E: setBrightness" above
- Ignore the muddled middle sentence there. Fact: in B's implementation the interface `setBrightness(v)` is already **silent** (no HUD), so E's sunrise ramp is fine today. `RealSystemControls.setBrightness(v, showHud)` exists for callers that want the capsule (voice "brighter"). Proposed: add `showHud: Boolean = false` to the interface.
- Status: accepted (main dd665de): default is `showHud = false`, as proposed.
