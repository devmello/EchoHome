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
- Status: open

## 2026-11-22 · D (media) · MediaControls.disconnect() + route details in MediaState
- Need: the Now Playing AirPlay popover (NowPlaying.dc.html) has a **Disconnect** button and codec chips ("AirPlay 2", "Lossless · ALAC", "44.1 kHz"). MediaControls has no disconnect and MediaState has no route/codec info, so today D pauses on Disconnect (demo: stops the fake) and shows the design's chips only for the demo source.
- Proposed (both with defaults, so nothing breaks):
  - `interface MediaControls { … fun disconnect() { pause() } }` — AirPlay (C) drops the sender; app sessions stop.
  - `data class MediaState(…, val route: List<String> = emptyList())` — short labels for the popover chips, e.g. `listOf("AirPlay 2", "Lossless · ALAC", "44.1 kHz")`, filled by the receiver.
  - Optional, for C: `FakeMediaSource` could take an artwork provider (`(album: String) -> Bitmap?`) so sample artwork comes with the fake; D wraps the hub to add procedural art meanwhile.
- Status: open

## 2026-11-22 · A (clock) · Home location for World / Weather faces
- Need: the World face's "you are here" marker and the Weather face's sunrise/sunset/sky need the home latitude/longitude (and a place name). Nothing in `:core` carries them; HA has `zone.home`.
- Proposed: `data class HomeLocation(val name: String, val lat: Double, val lon: Double)` in `:core:model` and `var location: StateFlow<HomeLocation?>` in `EchoServices` (bound by `:feature:home` from HA's `zone.home`; demo default Santa Cruz 36.97, -122.03). Or add `latitude`/`longitude` to `Weather`.
- Stub meanwhile: `:feature:clock` reads settings `clock.location.name|lat|lon`, else Santa Cruz when demo data is on (`HomeLocation.from` in FaceKit.kt).
- Status: open

## 2026-11-22 · A (clock) · Weather: tomorrow's hourly rain
- Need: Weather.dc.html's "TOMORROW" module draws 10 hourly rain-chance bars for tomorrow; `Weather.hourly` in the fake only covers the next 6 h.
- Proposed: document that `Weather.hourly` covers ~36 h (HA `weather.get_forecasts` type hourly gives 48), or add `precipTomorrow: List<Int>`. Also consider making `FakeWeatherProvider` match the design sample (52°, H:63° L:46°, 7 hourly entries with cloud at +5/+6 h, "Rain expected around 8 AM tomorrow").
- Stub meanwhile: bars come from hourly entries that fall tomorrow; with none and demo data on, the design's sample bars.
- Status: open

## 2026-11-22 · H (camera) · camera-in-use indicator: who draws it
- Need: Profiles.dc.html puts a camera label + green dot top-right ("Presence · 2 fps" / "Face check" / "Recognized") whenever the camera samples. `:feature:camera` draws it now in its overlay "camera" (z 250), shifted left of B's mic-off badge. If B also renders an indicator from `cameraInUse`, there will be two.
- Proposed: either B draws nothing for camera-in-use (camera owns it, as now), or add `SystemState.cameraActivity: String? = null` (the label; null = not sampling) that camera writes and B renders. Camera keeps writing `cameraInUse` either way.
- Status: open

## 2026-11-22 · H (camera) · clock applies the active profile
- Need: the Profiles flow ends with "her own face and colour" (Maya → Float face, #FF375F). `services.profiles.active` (real FaceProfileManager now) carries `faceId` + `color`; `:feature:clock` should follow it (and fall back to the shared/user choice for `Profile.SHARED`). Camera doesn't touch faces.
- Proposed: no contract change; A observes `services.profiles.active`. Optional: `ProfileManager.lastReason` (camera already has it on its concrete class) if the clock wants to animate only on FACE switches.
- Status: open

## 2026-11-22 · H (camera) · shutter inferred from frames
- Need: when frames are near-black while the light sensor says the room is lit, the shutter is probably closed even without SW_CAMERA_LENS_COVER. Camera releases the camera and backs off either way; B may want to show "Camera off · Shutter closed".
- Proposed: optional `SystemState.shutterClosedByFrames: Boolean` written by camera, or leave it internal. Low priority while B reads the real switch.
- Status: open

## 2026-11-22 · E (alarm) · `SystemControls.setBrightness(value, showHud = true)`
- Need: the sunrise ramp calls `services.systemControls.setBrightness` every 10 s for 30 min (and once more at ring time). If the real `:feature:system` implementation shows the brightness HUD on each call, the HUD would pop up all through the ramp.
- Proposed: `fun setBrightness(value: Float, showHud: Boolean = true): Boolean`, like `setVolume`. I'd pass `showHud = false`.
- Meanwhile: calling it as is.
- Status: open

## 2026-11-22 · I (photos) · GlassTracker keeps a hidden glass view invalidating every frame
- Need: `GlassTracker`'s pre-draw listener compares `getLocationInWindow` with the x/y cached in `update()`, which only runs from `onDraw`. A `GlassLayout` with a backdrop whose *parent* is INVISIBLE (a hidden controls box / sheet) never draws, so the cache never updates and it calls `invalidate()` on every pre-draw. Its own visibility is VISIBLE, so `invalidate()` isn't skipped: it schedules another frame each vsync and the window never goes idle. Found on the photo frame (the frame kept rendering at 60 fps with the controls hidden). Any feature with hidden glass panels (Control Center, sheets, popovers) can hit it.
- Workaround in `:feature:photos`: hidden glass gets `backdrop = null` (the tracker is inactive without a backdrop).
- Proposed fix in `:core:design`: in the pre-draw listener, skip unless `view.isShown`, and store the new location before invalidating (`x = loc[0]; y = loc[1]; view.invalidate()`), so a view that doesn't draw costs one invalidate, not one per frame.
- Status: open

## 2026-11-22 · I (photos) · optional: per-surface glass recipe values
- Need: Photos.dc.html's control capsule uses a slightly different recipe from NOTES §3.2: fill .12, border .3, top rim `inset 0 1.5px 1px .5`, shadow `0 12px 32px .4`, specular `linear-gradient(170deg, .28 0%, 0 55%)`, blur 18 / brightness 1.1. `GlassPainter` only exposes `tint`, so photos uses the standard recipe with a .12 fill (via `tint`) and an 18 px / 1.1 pre-blur. The difference is barely visible on the device.
- Proposed (low priority): optional overrides on `GlassPainter` (`borderAlpha`, `rimTopAlpha`, `shadowDy/Blur/Alpha`, `specularAngle/stops/alphas`), defaulting to `GlassTokens`.
- Status: open
