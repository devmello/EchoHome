# Full-system check and design review (lead, Nov 22, 2026, 14:30–15:00)

Release build of main (every agent merged), real data (demo off), camera presence on, AirPlay and
settings server running, no secrets set, no iPhone paired, no HA. Screens opened one after another
with `./scripts/echo-shot.sh` + `./scripts/echo-perf.sh 6`. Shots: `docs/status/shots/review/`.

## Numbers

| Screen | PSS | App CPU | Frames in 6 s | Notes |
|---|---|---|---|---|
| StandBy (Analog, dimmed) | 66.6 MB | 4.7 % | 6 | |
| Now Playing (nothing playing) | 68.0 MB | 3.2 % | 0 | |
| Timers (none) | 67.7 MB | 3.0 % | 0 | |
| Assistant (idle, no key) | 81.8 MB | **36.2 %** | 192 | fixed → 19.2 %, 125 frames / 8 s |
| Alarm ringing (demo) | 70.5 MB | 29.4 % (after fix) | 251 / 8 s | was 65 % (E's measurement) |
| Commute (no trips) | 78.3 MB | 2.5 % | 0 | |
| Stocks | 80.1 MB | 3.2 % | 0 | |
| Photos (no Immich) | 84.5 MB | 3.0 % | 1 | |
| Profiles / settings pairing / phone pairing | 85.8–88.2 MB | ~3 % | 0 | |

- PSS grows to ~88 MB after every screen has been opened once (screens stay built), well under
  the 200 MB budget. Device MemAvailable stayed 330–400 MB. No crash in `logcat -b crash`.
- Jank: under 1 % on every animated screen; p99 ≤ 11 ms.
- **Whole device (superseded 19:05, see `docs/status/camera.md` "Cheaper presence": ~6 % now, 0 while resting):** the biggest steady cost isn't the app. It's `camerahalserver` streaming for
  presence (~14–18 % of a core, H's measurement; `top` showed 30 % in one sample), plus
  `cameraserver` ~3–7 %. The HAL won't go below 15 fps. It stops in the dark and whenever
  our window isn't visible. `camera.presence.duty=true` saves about a third at ~1 fps sampling.
  Left as is, because presence drives Night dimming and greetings. Revisit if the Echo runs warm.

## Fixed in this pass

1. **Presence label over screen content.** "Presence · 2 fps" (top-right) covered the Timers
   "+ 5 min timer" button and the clock on Now Playing and Assistant. The steady label now shows
   on StandBy only (where Profiles.dc.html puts it); other screens keep the green dot (the privacy
   indicator). Face check / Recognized / Enrolling keep their text everywhere. (`CameraOverlayView`)
2. **Assistant idle animation:** 30 → 15 fps when idle. CPU 36 → 19 %.
3. **Alarm ringing glow:** the 1440×900 sunrise view and sky view declare
   `hasOverlappingRendering() = false` (single draw, so alpha goes on the paint, not a
   full-size offscreen layer each frame), and the breath updates at 30 fps. CPU 65 → 29 %,
   0.8 % janky.
4. Earlier today: the island hides on the alarm screen (12e3c28); Control Center RenderNodes,
   cached glass shadows, the island equaliser as a small view (B, D).

## Still open (none block use)

- ~~Assistant idle at 19 %~~ **Fixed (18:50):** the droplet holds still 30 s after the last touch or
  state change (0 frames; the clock ticks once a minute), and a touch restarts it.
- **Timers hint** says "Try “Hey Nova, add a 12-minute rice timer”", but the wake word is off by
  default and no API key is set. Should follow the assistant's availability (like its subtitle).
- **Home screen** only exists with Home Assistant configured (as designed); `--es screen home`
  falls through to the current screen.
- **Control Center** (release build, 18:47–19:00; the 6.3 % was a debug build driven by debug extras,
  a release build opened by real swipes measured 28–32 % frames over 17 ms). GPU-bound, not UI: each
  full-screen fill is ~4 ms on the Mali-T720. Fixed: the blurred background faded through a full-screen
  offscreen layer every frame (`hasOverlappingRendering = false`); the close slide didn't use the panel's
  hardware layer; StandBy painted a third redundant black fill; a glass backdrop landing mid-slide
  re-rendered the panel layer (now applied when it stops); and with Control Center open every clock tick
  below redrew the whole window (~26 ms GPU each second; everything under the opaque background is now
  INVISIBLE while it's open: 0 frames in 5 s). Close is now 1–2 slow frames. **Still open:** opening by swipe
  has ~10 frames at 25–28 ms GPU, and they coincide with Android's own bars sliding in (SurfaceFlinger
  composites them on the same GPU). Re-measure once the bars are off (`SystemBars`, needs STATUS_BAR in
  the /system copy).
- **Hands-on items** (mic polarity, shutter, reboot alarm, iPhone, AirPlay, face enrolment):
  NOTES.md §7.1.
- Not reviewed here, because they need data: Weather/Calendar cards with a real calendar,
  Now Playing with real artwork, Photos with Immich, the Home board with HA.
