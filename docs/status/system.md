# System layer (agent B) — `:feature:system`

Branch `worktree-agent-a105af019691ec581` (merged a405a84), then `system-speed` (from a8c903f). Overlays: island z300, banners z400, HUDs z500, Control Center z600 (the camera overlay owns z250 and the camera-in-use dot). Plain Views, no AndroidX/Compose, no live blur: glass refracts pre-blurred 1/4-scale snapshots, and every full-window blur runs on a background thread.

## Done

- **Volume keys** → `host.registerKeyHandler` → `AudioManager.adjustStreamVolume(STREAM_MUSIC, dir, 0)` (no system panel), repeats included, with the glass level HUD (HUDs.dc.html: drop spring 0.55 s, width spring, hold `system.hud.seconds`, default 1.5 s). `KEYCODE_VOLUME_MUTE` toggles mute. Checked on the Echo before quiet hours (hud-05: volume 0 → 7 %).
- **Mic button** (mic only, per the user): `KEYCODE_MUTE` via `/system/usr/keylayout/gating.kl` (installed by the lead; the app logs `gating remap installed=true`). `KeyRouter` → `HardwareMonitor.onMicButton` reads the hardware latch `/sys/devices/platform/amazon-gating/state` (world-readable) into `SystemState.micMutedHardware` with the "Microphone off / on" HUD; if the latch doesn't change, the press toggles the software mute. `input keyevent 91` reaches the app (hud-06/07). Before the remap the button was KEY_POWER: B still wakes the screen if a latch change coincides with SCREEN_OFF (harmless now; remove once a real press is confirmed).
- **Shutter** without root: `SystemState.shutterClosed` from `/sys/kernel/debug/gpio` ("Camera Lens Cover", gpio-499, active-low, world-readable), with the window policy dump as a first choice when DUMP works. Polled every 1 s only while the launcher is visible; "Camera off · Shutter closed" HUD on change.
- **Indicators**: HUD capsule (levels update in place, messages queue, same subject replaces), red mic-off / camera-off badges top-right (hidden while the capsule is about the same subject; `system.hud.badges`). Camera-in-use is the camera overlay's. The mic button no longer marks the camera off.
- **Control Center** (ControlCenter.dc.html): pull down from the top edge outside the island, follows the finger, settles open past 110 px or 700 px/s; drag up / tap outside / back closes. PixelCopy → blurs on the `echo-blur` thread (background 24 / sat 1.3 / 55 % over black + tints, tiles on a second 20 / 1.8 / 1.1 pre-blur). Connectivity (Wi-Fi: real toggle now the app is a system app, Wi-Fi panel otherwise; Bluetooth labelled with the connected phone; AirPlay → `system.cc.airplay`, read by C; Spotify inert), media tile, brightness + volume tall sliders, DND ("On until <next alarm>"), Night, mic, camera, timer, profiles row ("Auto by face"), settings button → `navigator.show("settings-pair")` (Android Settings only if that screen doesn't exist). DND/Night tiles wrap like the design. Slide = RenderNode translation/alpha. `services.systemControls` = `RealSystemControls` (`setBrightness(v, showHud)`: keys/voice true; the CC slider false since the HUD would sit under the CC).
- **Banners** (Banners.dc.html): `BannerListenerService` → `services.notifications`; glass banner (recorded once into a RenderNode, the spring drop animates only transform/alpha) with the previous one tucked behind; tap → StandBy-size view over the blurred screen with one tinted primary + Dismiss (`dismiss(id, byUser = true)`); swipe up hides; DND hides non-critical (`system.dnd.critical`). Reply actions → "Reply by voice" (assistant). iPhone banners show `sourceDevice` ("Phone · Maya’s iPhone · now") and use `appColor` + `glyph`; glyphs for J's keys: message, call, mail, video, music, news, heart, location, bell, calendar, alarm, home, doorbell, camera.
- **Echo Island** (Island.dc.html): hidden / compact / split (bead) / expanded; Motion.ISLAND spring on width/height/radius, content crossfade with the 0.15 s delay, grows out of a 44 px dot, glass or black (`system.island.look`), Night red. Timer, music, assistant, doorbell, call, AirPlay / Bluetooth connected. Checked against the merged `:feature:phone`: ANCS calls ("Answer on iPhone", Decline only, or no buttons when the notification has no negative action) and `BLUETOOTH_CONNECTED` (compact while fresh; after `staleAfterElapsed` only the CC Bluetooth cell shows the phone).
- **Settings page** (`host.registerSettings`, all live): Display (Night mode Auto/On/Off, island look), Notifications (show banners, time on screen, apps on the Echo, doorbell/alarms/calls in DND), Indicators (HUD time, mic/camera badges).
- **Debug** (debug builds; broadcast `app.vesper.system.DEBUG`, or the same extras on a launch/new intent via `services.intents`): `--es island story|timer|music+timer|assistant|doorbell|call|iphonecall|bluetooth|nowplaying|off`, `--es look glass|black`, `--es cc open|close`, `--es banner 0..5` (1 doorbell and 5 iPhone call are critical), `--es hud volume|brightness|micoff|micon|camoff`. Slow draws log to `VesperPerf` (debug only).
- Unit tests: SVG path parser, HUD queue, island model, banner queue: 29 tests, all pass (+ `screenCanHideTheWholeIsland` in `:core:model`).
- **Speed pass (`system-speed`)**:
  - Control Center: every glass tile is 3 RenderNodes (shadow, backdrop, surface) + 1 for its content, each re-recorded only when its key changes (Night, values, pressed sub-button, backdrop). The sliders' fill/icon draw live over the recorded glass. Tiles are prewarmed 4 s after attach and at the edge pull's touch-down. Closed, the panel stays VISIBLE above the screen at alpha 0 (GONE dropped it from the render tree and HWUI freed all tile nodes, so each open re-recorded ~40 ms). While it slides or fades the panel is a hardware layer. The background toggles INVISIBLE, not GONE (GONE ↔ VISIBLE relaid out the whole window on the first reveal frame). The radios refresh at open runs off the UI thread. A panel draw is now 1–3 ms, with 0 re-records on a typical open (11 cheap backdrop re-records when the new snapshot lands).
  - Expanded banner: recorded once into a RenderNode with a compositing layer; the 0.4 s fade is the node's alpha. That's group opacity like CSS, where it used to multiply each element's alpha, so mid-fade overlaps blend slightly differently.
  - `:core:design` `GlassPainter` shadow: rendered once per shape size (the same `setShadowLayer`, into an immutable bitmap with the shape erased) and drawn as a 9-slice. No per-frame blur and no clip. Same look: bench screen, max 1/255 difference around the static slider (glass-shadow-before/after). architecture.md's glass rule now says to leave shadows on.
  - `IslandManager.hideOnScreen(id)` / `hiddenOnScreens` (E's request): the island shows nothing while such a screen is current.
  - `HardwareMonitor` logs the raw gating latch on every change (`VesperSystem`: `gating …/state=N → micMutedHardware=…`), for the polarity check.

## Left

- Control Center still has ~6 % janky frames (target < 5 %), p99 36 ms (target < 33 ms), in a debug build with the music island animating. What's left is (a) the first 2–3 reveal frames (UI draw 12–25 ms; this is likely cold, JIT-less debug code, since the panel itself logs < 8 ms) and (b) GPU time of ~25–30 ms on frames that redraw the whole window while CC moves (screen + blurred background + panel layer). Ideas: measure a release build; stop drawing the screen underneath once the background is opaque; drop the ShadowSlice clipOutPath like core's shadow.
- Confirm with one real mic-button press: gating polarity, the HUD, and that the screen stays on (needs the user, after quiet hours).
- Volume / DND / mic tests with sound are on hold for quiet hours. The Wi-Fi toggle is untested on purpose (turning Wi-Fi off would also drop wireless adb).

## Measured on the Echo

**Speed pass (13:06–13:23, debug build, digital face, music island animating, raw touch events via `echo-touch.sh`, no `input` JVM):**

| Scenario | Before (a8c903f) | After (`system-speed`) |
|---|---|---|
| CC open/close ×3 (16 s) | 18.1 % janky, p50/p90/p99 11 / 20 / 109 ms, CPU 43 % | **6.3 % janky, 5 / 12 / 36 ms**, CPU 31 % |
| CC panel draw per open | 3–4 draws × 30–50 ms | prewarm 2–5 ms (0–1 re-records), draws 1–3 ms, backdrop arrival 11 cheap re-records |
| Banner expand + close | 19.7 % janky, p90 42 / p99 53 ms (expanded fade 20 ms per frame) | 10.9–12 % janky, p90 17 ms (one 38 ms record, then the fade composites a layer); p99 28–81 ms varies run to run |
| Bench, moving core glass with shadows, back-to-back framestats | 85 % janky, p50 20 / p99 34 ms, GPU p50 23 ms, UI 6.5 ms | 55 % janky, p50 18 / p99 27 ms, GPU p50 15 ms, UI 3.4 ms (another run: 0.2 % janky, p50 7 / p99 11 ms) |
| Idle (digital face) | 0.7 % janky, 5 ms | 0 %, 5 ms; PSS 62 → 66 MB |

Before the speed pass:

Release-like conditions not yet measured; all numbers are the debug build with every merged feature installed (whole-app PSS), 05:40, after the system install, DND on, analog face on screen (it renders ~26 fps by itself, which is the idle frame count).

| Scenario | PSS | CPU (1 core) | Frames | Janky | p50 / p90 / p99 |
|---|---|---|---|---|---|
| Idle, overlays idle | 89.0 MB | 20.4 % (face) | 209 | 0 % | 6 / 6 / 8 ms |
| Control Center open/close ×3 | 95.0 MB | 43.3 % | 457 | 14.7 % | 13 / 19 / 93 ms |
| Critical banners ×2 + expand + close | 90.7 MB | 24.2 % | 205 | 30.7 % | 6 / 25 / 69 ms |
| Brightness HUD ×3 | 91.0 MB | 27.0 % | 247 | 0 % | 6 / 8 / 9 ms |
| Island story (26 s: timer, split, doorbell expand, assistant, call) | 93.4 MB | 25.6 % | 631 | 16.3 % | 6 / 18 / 65 ms |

Earlier (05:10, before other features were merged, standby digital face): idle 51.6 MB, 1.4 % CPU, **0 frames** (all four overlays draw nothing when idle); music+timer island steady 12.2 % CPU, 0 % janky (eq at 30 fps). Device MemAvailable stayed 307–450 MB throughout.

Improvements made from these runs: island crash fix (it never drew in the first run), CC / expanded-banner blurs moved off the UI thread, HUD/banner backdrops by PixelCopy instead of a 27 ms software draw on the UI thread, compact banner as a RenderNode (banner frames 8–9 ms → only the first), shader cache per glass size. Story jank 31 % → 16 %, CC p90 57 → 19 ms.

## Screenshots

`docs/status/shots/system/`, compared with the designs at 960×480:

| Shot | Design | Notes |
|---|---|---|
| island-01-timer, island-02-split | Island.dc.html compact, split | 230/250×44 capsule + 44 bead with ring |
| island-03-music-expanded | Island.dc.html Now Playing 660×160 | matches; the glass refracts the clock behind |
| island-04-doorbell-expanded | Island.dc.html doorbell 640×196 | porch stand-in art (no camera snapshot) |
| island-05-assistant, island-06-call-expanded | Island.dc.html | |
| island-07-iphone-call-expanded, island-10-iphone-call-compact | lead's ANCS contract | "Answer on iPhone", Decline only |
| island-08-bluetooth | like AirPlay connected | |
| island-09-black-split | Island.dc.html black look | |
| hud-01-volume, hud-05-volume-key | HUDs.dc.html level | 05 is a real volume key (before quiet hours) |
| hud-02-mic-off, hud-04-camera-off, hud-06-mute-key, hud-07-mute-key-again | HUDs.dc.html messages | 06/07 are `input keyevent 91`, the remapped key |
| banner-01-compact, banner-02-stacked (with the red mic badge), banner-07-dnd-critical-stacked | Banners.dc.html compact + tucked | |
| banner-03-expanded, banner-05-iphone-expanded | Banners.dc.html expanded | iPhone call: no primary action → Dismiss only |
| banner-04-iphone, banner-08-critical-async-glass | Banners + `sourceDevice` | 08: glass from the PixelCopy backdrop over the analog face |
| banner-06-dnd-hides-message | DND | a non-critical banner doesn't show |
| cc-01-open, cc-02-bluetooth-phone | ControlCenter.dc.html | DND "On until 7:00 AM" wraps like the design |
| cc-03-tile-nodes | ControlCenter.dc.html | after the RenderNode change: same as before |
| glass-shadow-before, glass-shadow-after | core glass (bench screen) | shadow layer vs cached 9-slice: ≤ 1/255 around the slider |

## Open questions

1. **Gating polarity**: `amazon-gating/state` reads `0` with the mics on (rechecked 13:25). B assumes `1` = mics cut (`GATING_MUTED_VALUE`). B can't press the physical button, and injecting the key wouldn't move the hardware latch. One press by the user with the red LED seen confirms it: `logcat -s VesperSystem | grep gating` shows the raw value.
2. **Shutter via dumpsys**: the in-process window policy dump returns nothing even with DUMP, so B reads debugfs (works, live). Fine either way.
3. **iPhone call "Silence"**: per the lead only Decline shows. Silence could take the Accept slot as a neutral button.
4. The 05:12 Android Settings (System page) in front of the launcher wasn't B as far as B can tell: B's runs never tap the CC settings button (only edge swipes at x 200 and BACK), B's run 4 ended 05:11:55 and run 5 started 05:12:44 with `am start` of our activity. B's runs now end with `am start` MainActivity anyway.

## Needs from the user / lead

- Done by the lead (user-approved): `gating.kl`, the app as /system/priv-app "Vesper", Camera2 disabled, lock screen off. **Privileged permission B uses: `android.permission.DUMP`** (in the allow-list). B also requests the normal `CHANGE_WIFI_STATE` (Wi-Fi toggle as a system app) and `WRITE_SETTINGS` (granted for preinstalled apps). Nothing else.
- One mic-button press by the user (after quiet hours) to confirm polarity and the no-sleep behaviour.

## Device setup (one-time, done)

```
adb shell pm grant app.vesper android.permission.DUMP              # shutter state from dumpsys window policy
adb shell appops set app.vesper WRITE_SETTINGS allow               # system brightness from CC/HUD
adb shell cmd notification allow_listener app.vesper/app.vesper.feature.system.banner.BannerListenerService
adb shell cmd notification allow_dnd app.vesper                    # lets the CC toggle DND (not used during quiet hours)
```
