# Camera: presence + profiles (agent H, `:feature:camera`)

Branch `worktree-agent-aa37f514d836924ba`. Spec: NOTES §6.5, §6.6, `design/Profiles.dc.html`.

## Capture gate (step 1): PASSED, no camera patch needed

Measured Nov 22, 2026 ~04:00 on the Echo (`--es screen camera-probe`, `tools/probe.sh`). Shutter open
(`dumpsys input` SwitchValues 0). **The room was dark: light sensor 0.0 lux.**

| API | Result | Size | fps (asked 15) | first frame | app CPU (every frame delivered) |
|---|---|---|---|---|---|
| Camera2 (hardware level LIMITED) | frames OK | 176×144 YUV_420_888 | 9.5 | ~620 ms | 8.3–9.3 % of a core |
| Camera1 | frames OK | 176×144 NV21 | 9.5 | ~600 ms | 1.8–2.4 % of a core |

- Both APIs deliver frames. Offered sizes: 1600×1200, 1600×896, 1280×960, 1280×720, 1024×768, 1024×576, 960×720, 640×480, 320×240, 176×144. fps ranges: (15,15) (20,20) (5,30) (30,30).
- Frames were near-black (mean luma 2–3) because the room was dark. **Light test** (`--es flash true`): turning the screen white at full brightness for 4 s doubled the mean luma on both APIs (2.8 → 5.6 → 3.0). The sensor is live and responds to light; it isn't a dead pipeline. **Not verified yet: image quality in a lit room** (colour, focus, real face detection). Re-run `tools/probe.sh` in daylight to confirm.
- **Camera1 is the cheaper path** (about 4× less app CPU: one reused NV21 buffer, no ImageReader). The pipeline uses Camera1.
- One Camera2 error happened only when two clients opened the camera at once (`createDefaultRequest … -38`). The pipeline now never runs alongside the probe (`externalUser`).

## Done

- **Capture layer** (`capture/`): `Camera1Source` (used) and `Camera2Source`, Y plane only, smallest size, lowest fps range, frames dropped before touching pixels. Optional duty-cycled preview.
- **Presence** (`presence/`): 80×60 box-downscale, mean-shift-compensated frame difference (AE and lamps don't count as motion), dark detection. `PresenceTracker`: PRESENT needs 2 motions within 2 s (or a touch). EMPTY needs 3 min with no motion. UNKNOWN while the camera is off. Writes `services.system` `presence` and `cameraInUse`.
- **Gate** (`CameraPipeline`): the camera runs only while our window is visible (overlay `onWindowVisibilityChanged`, so no foreground service). It stops immediately on `cameraBlocked` (mic/camera button, toggle, shutter) and when permission is missing. After 3 s of near-black frames (closed shutter or dark room) it releases the camera and backs off 30 s → 5 min, retrying sooner when the light sensor rises or on a touch. Camera errors back off 60 s.
- **Face pipeline** (§6.6), each stage behind `camera.stage.presence|detect|recognize` (all on, after the measurements below):
  - Arrival (EMPTY→PRESENT; after a camera restart only if nobody's profile is active) → detection burst at 320×240, 5 fps, ~2 s (+0.7 s for the camera restart). Up to 3 bursts per arrival (later ones only on motion, ≥8 s apart). No bursts at all while nobody is enrolled.
  - Detector: `android.media.FaceDetector` (built in, no model).
  - Quality filter: eye distance ≥22 px, confidence ≥0.45, crop inside the frame, mean luma 40–225, Laplacian-variance sharpness, left/right symmetry (frontal).
  - 112×112 crop → embedding → cosine against 3–5 templates per person. A match needs the person's own threshold (the weakest pair of their enrolment shots, floor 0.5) and a 0.02 margin over the runner-up. Switch after 2 consecutive matches, ≤3 tries.
  - "Not me" blocks that person for 5 min.
- **Embedder: LBP baseline** (`LbpEmbedder`: uniform LBP(8,1) histograms on a 7×7 grid, Hellinger + L2; no model file, no licence issue). See "Models" below for why there's no neural model.
- **Templates**: `TemplateStore` keeps one file per person in `noBackupFilesDir/faces/` (never backed up). Each file is AES-256-GCM sealed (`SecretBox`) with a key in the Android Keystore, and the profile id is used as AAD, so a file copied onto another profile won't open. The embedder id is stored too, so templates from a different model are never compared. No frames, crops or images are ever written.
- **`services.profiles` = `FaceProfileManager`**:
  - Profiles, the active profile and Auto by face persist in settings (`camera.profiles*`). Demo mode seeds Maya (pink, Float) and Sam.
  - `enrolled` follows the template files.
  - `switchTo(FACE)` is ignored when Auto is off or the person is blocked.
  - Goes back to shared after `camera.profiles.timeoutMin` (10) min of EMPTY. UNKNOWN (a dark room at night) doesn't count.
  - `reject` = Not me. `delete` also removes the person's templates.
- **UI**:
  - Overlay `camera` (z 250): the top-right label + green dot whenever the camera samples ("Presence · 2 fps", "Face check", "Recognized", "Enrolling face"). It moves left of B's mic-off badge.
  - Empty-room dim (StandBy only, 0.35 opacity, 0.9 s ease).
  - "Who's there?" glass pill; the spinner redraws at 20 fps.
  - "Hi <name>" glass sheet: avatar, alarm/event chips, "Not me". 0.35 s fade plus a 0.6 s spring rise, shown for 4 s.
  - Footer "On-device recognition · no images saved".
  - Night mode maps everything to #BE180E.
  - Touches pass through, except the Not me button. Any touch counts as presence.
- **Screens** (not in the pager):
  - `profiles` (settings): people, Auto by face, Allow camera, Enrol / Forget face, Add person, long-press to delete, Done.
  - `profiles-enrol --es profile <id>`: explicit consent, then 5 looks (straight, left, right, up, down). Each look takes the sharpest of 4 quality-passing samples, with a guide that shows only where the face is (no preview image). The 5 templates are then encrypted.
- **Debug screens**:
  - `camera-probe` (capture gate).
  - `camera-bench --es stage presence|presence-duty|detect|recognize` (holds a stage for measuring, logs per-stage timings).
  - `profiles-demo --es step empty|detecting|recognized|personalized|cycle` (design review without a camera).
- **Tools** (in `android/feature/camera/tools/`):
  - `probe.sh`: the capture gate.
  - `measure.sh`: CPU of the app, cameraserver and camerahalserver, plus PSS.
  - `stages.sh`: per-stage bench.
  - `shots.sh`: every Profiles screenshot.
- **Tests**: 76 JVM tests covering frame differencing (noise, global shift, small changes, dark frames, strided, pixel-stride-2 and direct buffers), hysteresis, cosine and the matcher (thresholds, margin, exclusion), consecutive-match logic, LBP, the quality filter, the crop, the SecretBox envelope (round trip, layout, fresh IV, tampering, wrong AAD or key, truncation, version, IV length), TemplateStore (encrypted on disk, AAD binding, path safety) and FaceProfileManager (persistence, Auto, Not me cool-down, timeout with virtual time).

## Measured on the Echo (debug build, Nov 22, 2026 04:28–04:44, dark room)

CPU is % of one core over 30 s (`tools/measure.sh`; CLK_TCK 100). The camera HAL does most of the work.

| Stage | app | cameraserver | camerahalserver | **total** | device busy (4 cores) | PSS app / cs / HAL |
|---|---|---|---|---|---|---|
| Baseline: StandBy, camera released | 0.0 | 0.0 | 0.0 | **0** | 0.8 % | 32.3 / 5.1 / 15.8 MB |
| 1 Presence, 176×144 @ 2 fps (default) | 1.6 | 3.1 | 13.5 | **18.2** | 5.5 % | 34.6 / 6.2 / 42.6 MB |
| 1 Presence, duty-cycled preview (option) | 0.8 | 1.4 | 10.0 | **12.2** | 4.6 % | 36.2 / 6.2 / 42.6 MB |
| 2 Detection, 320×240 @ 5 fps (burst only) | 49.0 | 3.7 | 14.1 | **66.8** | 18.8 % | 38.7 / 6.7 / 43.1 MB |
| 3 Recognition (embed + match, ≤3 per arrival) | +16 ms per try | | | | | |

- **Presence**: 0.43 ms per sample in the app (downscale + diff; 2.7 ms before the array fast path). Sensor + HAL streaming costs ~14 % of a core whatever our sample rate, because the HAL won't go below 15 fps (it ran at 9.5 fps in the dark). Averaged over the device that's ~4.6 % of total CPU, but only while the room is lit and our window is visible: in the dark the camera is released (0 %).
  - Duty-cycling the preview saves a third (12.2 %). Restarting the preview takes 626 ms, though, so sampling drops to ~1.1 fps. It's off by default; `camera.presence.duty=true` turns it on.
- **Detection**: `FaceDetector` takes 59–61 ms per 320×240 frame (max 150 ms), so ~30 % of a core at 5 fps. The first measurement showed 95 % app CPU: ~46 % of that was the full-screen overlay redrawing the spinner every vsync. Capped at 20 fps, the burst costs 49 %. A burst lasts ≤2.7 s, once per arrival (≤3 bursts).
- **Recognition**: 16–17 ms per crop + LBP embedding + match (max 149 ms on the first, JIT), ≤3 tries per arrival.
- **RAM**: the whole pipeline is ≈ +30 MB while the camera is on (app +2–6 MB, cameraserver +1.1, camerahalserver +27), under the ~40 MB budget. It's all released with the camera.

## Screenshots (Echo, `tools/shots.sh`) vs `Profiles.dc.html`

`docs/status/shots/camera/`:
- `profiles-empty.png`: matches the design: shared face at 0.35, "Presence · 2 fps" + green dot at right 18 / top 16.
- `profiles-detecting.png`: matches: "Who's there?" 220×48 glass capsule at top 18, spinner, "Face check" label, footer.
- `profiles-recognized.png`: matches the layout: 560×132 sheet at bottom 30, 84 px avatar with rings, "Hi Maya" 30/800, subtitle, "Alarm 6:15 AM" / "Dinner 7:00 PM" chips, "Not me".
  - **Difference:** behind the card the design already shows Maya's pink Float face. The clock doesn't follow `profiles.active` yet (`:feature:clock`, contract request filed), so the shared Digital face shows instead.
- `profiles-personalized.png`: same dependency; it looks like StandBy until the clock applies the profile.
- `settings-profiles.png`, `enrol-consent.png`, `enrol-capture.png`: no artboard exists (NOTES §2.2 lists face enrolment as still to design). They're built from Profiles.dc.html's avatar and type styles: content on plain fills, one tinted primary.
- `probe.png`: capture gate screen.

## Models

| What | Used | Licence | Source |
|---|---|---|---|
| Face detection | `android.media.FaceDetector` (platform API) | part of Android (AOSP, Apache-2.0) | built in |
| Face embedding | **LBP histograms, implemented here** | ours | Ahonen, Hadid, Pietikäinen, "Face Recognition with Local Binary Patterns", ECCV 2004 |
| MobileFaceNet / ArcFace-class TFLite | **not used** | the common pretrained weights (InsightFace, MobileFaceNet ports) are trained on MS-Celeb-1M / CASIA-WebFace / Glint and released for **non-commercial research only** | n/a |

No third-party model or library is bundled. TFLite isn't a dependency.

## Left / open

- **Needs the user:**
  - A licence-clean face-embedding model, if LBP isn't good enough. Options: (a) a model the user trains or licenses; (b) the NOTES option of sending the 112×112 crop to the Home Assistant server over the LAN (nothing stored) to embed there. `FaceEmbedder` is the plug-in point, and templates are keyed by embedder id.
  - Approval for MediaPipe BlazeFace (Apache-2.0) + TFLite, if `FaceDetector` misses faces in practice (~1–2 MB APK, ~5 MB RAM).
- **Not verified on the device (the room was dark the whole session):**
  - Real presence triggering on motion in light.
  - Face detection on a real face, its quality thresholds, enrolment end to end, and LBP match thresholds.
  - Run `tools/probe.sh`, enrol someone in `profiles`, and walk in, in daylight.
- **Integration (contract requests filed):**
  - The clock should apply `profiles.active` (face + colour).
  - Who draws the camera-in-use indicator (camera does now; B shouldn't duplicate it).
  - Optional "shutter inferred from frames" flag.
  - Control Center's "Auto by face" can call `services.profiles.setAutoByFace` and open `navigator.show("profiles")`.
- **Smaller:**
  - "Add person" and the delete/forget confirmations use the platform AlertDialog (no design yet).
  - People beyond 4 aren't shown (no scrolling).
  - Quiet hours (§6.6 gate) aren't implemented: there's no setting for them yet.
- The camera patch (NOTES §6.5) was **not** applied and isn't needed for capture.
