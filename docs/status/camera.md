# Camera: presence + profiles (agent H, `:feature:camera`)

Branch `camera-fast-embedder` (phase 3, faster face model; phases 1–2 merged in main 808b3d2). Spec: NOTES §6.5, §6.6, `design/Profiles.dc.html`.

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
- **Gate** (`CameraPipeline`): the camera runs only while our window is visible (overlay `onWindowVisibilityChanged`, so no foreground service). It stops immediately on `cameraBlocked` (mic/camera button, toggle, shutter) and when permission is missing. After 3 s of near-black frames (closed shutter or dark room) it releases the camera and backs off 30 s → 5 min, retrying sooner when the light sensor rises or on a touch. Camera errors back off 60 s. Light readings: its own `TYPE_LIGHT` listener, Night's published `SystemState.ambientLux`, and, because this Echo's light HAL sends nothing on registration (it logged `lux=-1.0`), a first reading 2 s after registering from `/sys/bus/platform/drivers/als_ps/lux` (Night's 1.5 s fallback usually arrives first), else the last `ambientLux`.
- **Face pipeline** (§6.6), each stage behind `camera.stage.presence|detect|recognize` (all on, after the measurements below):
  - Arrival (EMPTY→PRESENT; after a camera restart only if nobody's profile is active) → detection burst at 320×240, 5 fps, ~2 s (+0.7 s for the camera restart). Up to 3 bursts per arrival (later ones only on motion, ≥8 s apart). No bursts at all while nobody is enrolled.
  - Detector: **MediaPipe BlazeFace short-range** (TFLite, XNNPACK, letterboxed 128×128 RGB, gives six landmarks). `android.media.FaceDetector` is the fallback if the model can't load, and the `camera.detector=legacy` option.
  - Quality filter: eye distance ≥22 px, confidence ≥0.45, crop inside the frame, mean luma 40–225, Laplacian-variance sharpness, left/right symmetry (frontal). Measured on the grey geometry crop, as before.
  - **Alignment**: the 112×112 RGB crop is warped onto the ArcFace template. It uses a least-squares similarity (Umeyama) from BlazeFace's eyes, nose and mouth. Without landmarks (legacy detector) it uses the eye midpoint and distance. Colour comes from the NV21 chroma.
  - **Embedding**: Idiap **EdgeFace-XXS** (default, ~60 ms) or **EdgeFace-XS γ0.6** (setting "Face model", ~100 ms), CosFace loss, fp16 weights on TFLite + XNNPACK, 2 threads, 512-d. MobileFaceNet (phase 2, ~0.35 s) is dropped. Sources, checksums, licences and conversion are in `android/feature/camera/MODELS.md`. LBP is the fallback if the model fails to load or run (`FallbackEmbedder`), and `camera.embedder=lbp` (debug) forces it.
  - Cosine against 3–5 templates per person, then the per-embedder `MatchPolicy` (see "Thresholds" below). Switch after 2 consecutive matches, ≤3 tries.
  - **Models load on the first detection frame** of a burst or enrolment and are **freed whenever the camera stops**: the end of a burst (back to presence size), the end of enrolment, the screen off, an error. Both happen under one lock on the camera thread.
  - "Not me" blocks that person for 5 min.
- **Settings page** (`host.registerSettings`): section "Camera & people", order 60. Every setting applies live (the pipeline and `FaceProfileManager` watch `settings.changes`):
  - Presence detection (`camera.stage.presence`)
  - Duty-cycled presence (advanced)
  - Auto by face
  - Face model (advanced; `camera.embedder`: `edgeface-xxs` "EdgeFace-XXS (fastest)", the default, or `edgeface-xs` "EdgeFace-XS (more accurate)"). It's swapped live: the old interpreter is released and the next try loads the new one. Templates from the other model are ignored, so people re-enrol.
  - Match threshold (advanced; `camera.match.threshold`, the neural floor, 0.25–0.6)
  - Return to Shared after N minutes (`camera.profiles.timeoutMin`, 1–120)

  Enrolment stays on the Echo, because it needs the camera.
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
  - The face check is in the island (Nov 23, 2026): `FaceCheckIsland` publishes a FACE_CHECK activity while the phase is DETECTING (held 0.6 s after a burst so the 2 s bursts don't flicker). Compact: face glyph, "Checking face", spinner; bead: spinner. The spinner steps at 20 fps. The old "Who's there?" pill is gone. When a face matches, the island says hello: the person's avatar (their colour, first letter) and "Hi <name>", for as long as the greeting is up (4 s); the greeting card with chips and "Not me" stays at the bottom. The hello turns into "Loaded preferences" after 1.1 s and ends after 3 s (the island then shows what it showed before). Island demos: `--es island facecheck`, `--es island facehello`. `profiles-demo` adds the demo person Maya (orange, the Solar face) if she isn't there and, on "recognized", sets the camera's own greeting (`CameraPipeline.demoGreeting`), so the clock switches to her face and the hello comes through `FaceCheckIsland` like a real match. Maya stays in the people list afterwards. Not checked end to end on the Echo: it has no people added yet.
  - "Hi <name>" glass sheet: avatar, alarm/event chips, "Not me". 0.35 s fade plus a 0.6 s spring rise, shown for 4 s.
  - Footer "On-device recognition · no images saved".
  - Night mode maps everything to #BE180E.
  - Touches pass through, except the Not me button. Any touch counts as presence.
- **Screens** (not in the pager):
  - `profiles` (settings): people, Auto by face, Allow camera, Enrol / Forget face, Add person, long-press to delete, Done.
  - `profiles-enrol --es profile <id>`: explicit consent, then 5 looks (straight, left, right, up, down). Each look takes the sharpest of 4 quality-passing samples, with a guide that shows only where the face is (no preview image). The 5 templates are then encrypted.
- **Debug screens**:
  - `camera-probe` (capture gate).
  - `camera-bench --es stage presence|presence-duty|detect|detect-compare|recognize|selftest` (holds a stage for measuring, logs per-stage timings). `detect-compare` runs both detectors on every frame.
  - `selftest` needs no camera and no person. It runs both detectors on drawn cartoon faces (`SyntheticFace`), then alignment and the embedder, with PSS readings. It runs every shipped embedder in turn and checks each against the original PyTorch model's output for the same drawing. It waits 10 s first, so other features starting up aren't counted as model PSS. `--es model <name> --es threads N --es xnnpack false` tests one variant.
  - `profiles-demo --es step empty|detecting|recognized|personalized|cycle` (design review without a camera).
- **Tools** (in `android/feature/camera/tools/`):
  - `probe.sh`: the capture gate.
  - `measure.sh`: CPU of the app, cameraserver and camerahalserver, plus PSS.
  - `stages.sh`: per-stage bench.
  - `shots.sh`: every Profiles screenshot.
  - `variants.sh`: embedder variants, a fresh process each.
  - `neural.sh`: live recognize bench, detect-compare, PSS after the camera is off.
- **Tests**: 94 JVM tests. New in phase 3: the embedder registry (unique ids and keys, XXS default, unknown or `mobilefacenet` keys fall back to the default, assets and 512-value references present) and the Face model setting. Phase 2: similarity fit, the landmark → template transform (eye order irrelevant), alignment on a drawn face, NV21 → RGB, letterbox, BlazeFace anchors, decode with weighted NMS on synthetic tensors, embedder fallback, neural threshold clamping, the settings section, and live settings. The original 76 cover covering frame differencing (noise, global shift, small changes, dark frames, strided, pixel-stride-2 and direct buffers), hysteresis, cosine and the matcher (thresholds, margin, exclusion), consecutive-match logic, LBP, the quality filter, the crop, the SecretBox envelope (round trip, layout, fresh IV, tampering, wrong AAD or key, truncation, version, IV length), TemplateStore (encrypted on disk, AAD binding, path safety) and FaceProfileManager (persistence, Auto, Not me cool-down, timeout with virtual time).

## Cheaper presence (lead, Nov 22, 2026, 18:30–19:05, release build)

Presence was the biggest steady CPU cost on the whole device: Camera1's 15 fps preview keeps the HAL busy
(~19 % of a core in total) even though we use 1–2 frames a second. Measured with `tools/measure.sh`
(app + cameraserver + camerahalserver, % of one core) and the `camera-probe` / `camera-bench` debug options
`--es mode sparse|slow:MS` (Camera2) and `--es params "k=v;…"` (Camera1).

| Presence capture | total | HAL |
|---|---|---|
| Camera1 preview 15 fps (old default) | 19.3–19.8 | 14.6 |
| Camera1 + `picture-size=176x144`, no JPEG thumbnail | 18.7 | 14.0 (HAL PSS −3.6 MB) |
| Camera2 repeating 15 fps | 16.7–19.0 | 12–13.6 |
| **Camera2 single captures, 1 per second (new default)** | **6.9** | 5.8 |
| Camera2 single captures, 2 / s | 8.8 | 6.6 |
| Camera2 single captures, 3 / s | 10.0 | 7.4 |
| Camera2 open, one capture per 10 s (cost of just being open) | 4.8 | 4.2 |
| Camera2 AE off, 400 ms frames (2.5 fps, fixed exposure) | 5.4 | 3.4 |

- Camera1 parameters barely matter: the HAL cost is per streamed frame. Camera2 lets us ask for single frames
  (`CameraCaptureSession.capture` on a timer after a 0.6 s repeating warm-up so AE settles). Mean luma
  matched the repeating stream (48 vs 49), so AE works with sparse captures. AE-off is cheaper still but
  needs our own exposure loop; not worth the risk.
- An open camera costs ~4.5 % on its own, so the only way below that is closing it. Hence **resting**: after
  motion or a touch (presence PRESENT, face check done) the camera closes for 60 s (`REST_MS`; a touch
  extends it). On resume the tracker stays PRESENT and counts the 3 min of quiet from the resume (`holdPresent`),
  so an empty room dims at most a minute later than before. Setting "Rest the camera while someone's here"
  (`camera.presence.rest`, advanced, default on) replaces "Duty-cycled presence" (Camera1 duty mode, 12.2 %).
- Detection bursts and enrolment stay on Camera1 (NV21 colour for alignment, 5 fps). If Camera2 errors,
  presence falls back to Camera1 for the rest of the process.
- Opening and closing run on a `vesper-cam-ctl` thread (closing took the main thread 100–300 ms);
  frames from a source being closed are dropped by a generation counter.
- **Dark-room bug fixed:** the back-off (30 s → 5 min) reset on every `stopCamera()`, so in a dark room the
  camera opened every ~33 s all night (~3.5 s of streaming each). Now it only resets on a lit frame, and
  while the light sensor still reads what it read when the frames went black the retry is skipped
  (lights on and touches still retry at once; a real check at least every 20 min). Dark needs 4 samples (was 6).
- On the Echo (StandBy, lit room): nobody moving 8.0 % (app 2.2 incl. the clock, HAL 5.3) vs ~20 % before;
  resting 1.9 % (the app alone, camera closed).

## Measured on the Echo (debug build, Nov 22, 2026 04:28–04:44, dark room)

CPU is % of one core over 30 s (`tools/measure.sh`; CLK_TCK 100). The camera HAL does most of the work.

| Stage | app | cameraserver | camerahalserver | **total** | device busy (4 cores) | PSS app / cs / HAL |
|---|---|---|---|---|---|---|
| Baseline: StandBy, camera released | 0.0 | 0.0 | 0.0 | **0** | 0.8 % | 32.3 / 5.1 / 15.8 MB |
| 1 Presence, 176×144 @ 2 fps (default) | 1.6 | 3.1 | 13.5 | **18.2** | 5.5 % | 34.6 / 6.2 / 42.6 MB |
| 1 Presence, duty-cycled preview (former option, removed) | 0.8 | 1.4 | 10.0 | **12.2** | 4.6 % | 36.2 / 6.2 / 42.6 MB |
| 2 Detection, 320×240 @ 5 fps (burst only) | 49.0 | 3.7 | 14.1 | **66.8** | 18.8 % | 38.7 / 6.7 / 43.1 MB |
| 3 Recognition, LBP (phase 1; neural numbers below) | +16 ms per try | | | | | |

- **Presence**: 0.43 ms per sample in the app (downscale + diff; 2.7 ms before the array fast path). Sensor + HAL streaming costs ~14 % of a core whatever our sample rate, because the HAL won't go below 15 fps (it ran at 9.5 fps in the dark). Averaged over the device that's ~4.6 % of total CPU, but only while the room is lit and our window is visible: in the dark the camera is released (0 %).
  - Duty-cycling the preview saves a third (12.2 %). Restarting the preview takes 626 ms, though, so sampling drops to ~1.1 fps. It's off by default; `camera.presence.duty=true` turns it on.
- **Detection**: `FaceDetector` takes 59–61 ms per 320×240 frame (max 150 ms), so ~30 % of a core at 5 fps. The first measurement showed 95 % app CPU: ~46 % of that was the full-screen overlay redrawing the spinner every vsync. Capped at 20 fps, the burst costs 49 %. A burst lasts ≤2.7 s, once per arrival (≤3 bursts).
- **Recognition (LBP, phase 1)**: 16–17 ms per crop + LBP embedding + match (max 149 ms on the first, JIT), ≤3 tries per arrival.
- **RAM**: the whole pipeline is ≈ +30 MB while the camera is on (app +2–6 MB, cameraserver +1.1, camerahalserver +27), under the ~40 MB budget. It's all released with the camera.

## Fast embedder (EdgeFace), measured on the Echo (debug build, Nov 22, 2026 12:58–13:03, lit room)

The lead's goal: ~100 ms or less per embedding, with accuracy close to MobileFaceNet. The candidates were EdgeFace-XS (γ0.6), EdgeFace-XXS and GhostFaceNet.
- EdgeFace was converted first. It has an official checkpoint release (`otroshi/edgeface` on GitHub, checked against the git blob SHA-1s).
- Both EdgeFace sizes met the bar, so GhostFaceNet wasn't converted.
- Sources, licence, conversion and the desktop checks are in MODELS.md.

The device was still shared (load average 5–8), so timings are on the slow side.

**Self-test** (camera off, one fresh process, 2 threads, XNNPACK):

| | EdgeFace-XXS fp16 (default) | EdgeFace-XS fp16 (option) | MobileFaceNet int8 (phase 2) |
|---|---|---|---|
| ms / embedding | **61.0** | **102.2** | 346 (437 under today's load) |
| first embedding (interpreter create) | 193 ms (92 ms) | 276 ms (156 ms) | 387 ms (7 ms) |
| PSS while loaded | +16.8 MB | +22.9 MB | +11.4 MB |
| cos vs original framework on the drawn face | **1.0000** | **1.0000** | 0.9887 |
| MACs / LFW (published) | 47 M / 99.57 % | 76 M / 99.73 % | 440 M / ~99.5 % |

- **Both pass the bar** (cos ≥ 0.98 to the reference, LFW ≥ 99.3 %).
  - **XXS is the default.** It's the fastest, and its LFW is only 0.16 points under XS.
  - XS stays as the "more accurate" setting, for a household where XXS confuses two people. It's still 3–4× faster than MobileFaceNet.
- **Format:** fp16 weights on XNNPACK are the fastest form of both models. fp32 is ~5 ms slower; dynamic int8 is 2.3× slower and less accurate. The variants table is in MODELS.md. XNNPACK takes all but 24 nodes, in 19 partitions.
- **Threads:** 4 threads gave nothing over 2 (XS fp32 118.0 vs 118.3 ms), so 2 stay.
- **PSS:** XNNPACK unpacks the fp16 weights to fp32 and keeps a workspace, so EdgeFace takes more resident memory than its file size. After all the models were released, PSS was back to its starting value + 3.6 MB (86.2 vs 82.6 MB). The rest is the self-test's own frames and JIT.
- **Same run, other stages:**
  - BlazeFace 61 ms/frame (FaceDetector 73 ms); load 51 ms, +7.2 MB.
  - Alignment 13 ms.
  - LBP 9 ms.

**Live camera** (`tools/neural.sh`, 15 s per stage). The room was lit (mean luma 123) but nobody was in front of the camera, so the quality filter saw only TOO_SMALL.

| Stage | app CPU | camera total | PSS app / cs / HAL | Per-frame app timings |
|---|---|---|---|---|
| `recognize`: detect + align + embed (XXS) on **every** frame (worst case) | 112 % | 158 % | 105.3 / 5.9 / 50.3 MB | detect 57.6 ms, align 11.7 ms, embed **56.4 ms** (max 70.2), model loads 118 ms avg (2) |
| `detect-compare` | 95 % | 140 % | 108.5 / 6.1 / 50.5 MB | BlazeFace 53.0 ms vs FaceDetector 80.9 ms. No face on either. |
| Back on home: presence only, models released | 6 % | 54 % | **88.0** / 5.4 / 49.9 MB | |

- **The "back on home" row isn't camera-off.**
  - In a lit room the home screen keeps presence running: Camera1 at 176×144, one frame per 500 ms. `dumpsys media.camera` showed our client connected. That's why the HAL stays at ~50 MB.
  - Phase 2's 16.5 MB HAL figure was taken in a dark room, where the camera releases itself.
  - When the burst ended the app dropped 17.3 MB (105.3 → 88.0): both models plus the 320×240 buffers.
- **One recognition try** is now ~58 + 12 + 56 ≈ **126 ms** (it was ~420 ms). There are ≤3 tries per arrival, so "Hi Maya" can come ~1 s after the arrival. Most of that is the 0.6 s camera restart and the 2 matching frames.
- **Budget:**
  - Live, the two models cost ~17 MB, inside +20 MB.
  - The self-test's per-model figures (XXS +16.8, XS +22.9) are upper bounds that include allocator growth.
  - With XS selected, the live total would be ~6 MB higher, just over +20 MB.
- **APK:**
  - The debug APK is 34,501,644 B.
  - Model payload, all stored uncompressed: XXS 2,642,176 B, XS 3,750,728 B, BlazeFace 229,746 B, `libtensorflowlite_jni.so` 2,366,508 B. The two references are ~2 KB each, compressed.
  - Compared with the MobileFaceNet build: **+2.76 MB** (XS + XXS − MobileFaceNet's 3,629,496 B). Dropping XS would make it −0.99 MB.

## Neural pipeline (phase 2, MobileFaceNet; superseded), measured on the Echo (debug build, Nov 22, 2026 05:29–05:45, dark room)

Other agents' processes were running on the Echo at the same time (load average ~5), so the timings are noisy and on the slow side.

**Self-test** (camera off; drawn faces at 320×240; `selftest`, one fresh process):

| | |
|---|---|
| Detection, same 4 frames × 20 | BlazeFace **53 ms**/frame vs FaceDetector **62 ms**. Both find faces with r = 30/40/55 px 20/20 times and miss r = 18 (eye distance 14 px, below the quality minimum anyway). Eye-midpoint error is 1.1–1.9 px vs 1.4–2.7 px. Only BlazeFace gives landmarks, and its confidence is 0.91 vs 0.51. |
| BlazeFace load | 41 ms (17 ms when warm). PSS **+6.7 MB** |
| Alignment (112×112, bilinear + colour) | 12 ms |
| Embedding, shipped int8 (built-in kernels, 2 threads) | **346 ms** each, first 387 ms (the interpreter loads in 7 ms from the mmap). PSS **+11.4 MB** |
| Correctness | **cos 0.9887** vs the original ONNX model's embedding of the same drawing (desktop int8: 0.9917, fp16: 0.99999). Channel order, normalisation and NHWC are confirmed end to end. |
| LBP fallback | 7 ms |

**Variants** (`tools/variants.sh`, fresh process each; the table is also in MODELS.md):

| Variant | ms / embedding | PSS added while loaded |
|---|---|---|
| fp16, XNNPACK, 2 threads | 283 | +38.8 MB |
| fp16, XNNPACK, 4 threads | 300 | +39.2 MB |
| fp32, XNNPACK, 4 threads | 300 | +32.7 MB |
| fp32, built-in, 4 threads | 265 | +22.2 MB |
| int8, XNNPACK (31 partitions), 4 threads | 335 | +15.0 MB |
| **int8, built-in, 4 threads (shipped, with 2 threads)** | **303** | **+13.8 MB** |

The network is 440 M MACs, about 1.5 GMAC/s on this CPU, so every variant takes ~0.3 s. Extra threads gained nothing while the other agents' load was on the device. fp16 and fp32 on XNNPACK repack the weights into a second fp32 copy, which is why they cost +33–39 MB. Every variant was back to its baseline PSS (±2 MB) 3 s after release.

**Live camera** (`tools/neural.sh`, 15 s per stage, frames near-black):

| Stage | app CPU | camera total | PSS app / cs / HAL | Per-frame app timings |
|---|---|---|---|---|
| `recognize`: detect + align + embed on **every** frame (worst case) | 133 % | 149 % | 77.2 / 7.4 / 43.4 MB | detect 50.6 ms, align 11.6 ms, embed **357 ms** (max 384), model loads 17 + 405 ms |
| `detect-compare`: both detectors on the same 78 frames | 66 % | 83 % | 73.2 / 7.4 / 43.5 MB | BlazeFace 48.1 ms vs FaceDetector 58.6 ms. FaceDetector reported a face on 1 of 78 black frames (false positive); BlazeFace on 0. |
| Camera off again (models released) | 0 | 0 | **64.7** / 5.9 / 16.5 MB | |

- **Budget:**
  - **PSS:** the models add **+18 MB** while loaded (BlazeFace 6.7 + MobileFaceNet 11.4). The live recognize stage was 12.5 MB above the camera-off figure. That is inside +20 MB, and it is freed when the camera stops: 77.2 → 64.7 MB, and the HAL drops to 16.5 MB.
  - **Embedding time:** ~350 ms, which **misses the ~100 ms target by 3.5×** (see "Left / open").
  - **In practice:** ≤3 tries per arrival, so a recognised person costs ~2 × (50 + 12 + 350) ms ≈ 0.8 s of one core after the 0.6 s camera restart. "Hi Maya" comes ~1.5 s after the arrival. A burst is still ≤2.7 s.
- **Detector:** BlazeFace is cheaper (48–53 vs 59–62 ms), has no false positive on black frames, and gives the landmarks alignment needs, so it's the default. Comparing finds on real faces (turned or tilted heads, which FaceDetector misses) needs a person in front of the camera: run `detect-compare` in daylight.
- **APK:** +6.3 MB, all stored uncompressed (`libtensorflowlite_jni.so` 2.37 MB, int8 model 3.63 MB, BlazeFace 0.23 MB, LiteRT classes ~0.05 MB, self-test reference 2 KB).

## Thresholds (neural `MatchPolicy`)

Both EdgeFace models use the same `MatchPolicy.NEURAL` rule as MobileFaceNet did.
- EdgeFace is trained with CosFace (large-margin cosine) loss. That gives the same cosine geometry as ArcFace: different people centre on ~0, and the same person scores well above.
- EdgeFace's published numbers are verification accuracies and TARs, at least as good as MobileFaceNet w600k's:
  - XS: LFW 99.73 %, IJB-C TAR 94.85 % at FAR 1e-4.
  - XXS: LFW 99.57 %.
- Neither the paper nor the repo publishes a cosine threshold. So the floor stays where margin-loss models usually have FAR ≈ 1e-4 to 1e-5.

- **Floor 0.36.** Margin-loss embeddings put different people's cosines around 0, with a spread of ~0.06–0.10. 0.36 is ≥3.5 spreads above that.
- **Per person: weakest pair of their 5 enrolment shots − 0.10, kept within 0.36–0.55.**
  - The same person across angles and light usually scores 0.45–0.8.
  - The slack lets a live frame be a bit worse than enrolment.
  - The 0.55 ceiling stops a very consistent enrolment (pairs at 0.8) from demanding ≥0.7 live.
- **Margin 0.05 over the runner-up,** so relatives don't flip-flop.
- **Then 2 consecutive matches,** with "Not me" as the safety net.
- **LBP keeps its old rule** (floor 0.5, slack 0.01, margin 0.02). Templates carry the embedder id (`edgeface-xxs-fp16-v1` or `edgeface-xs-g06-fp16-v1`). Older templates are ignored and the person needs to re-enrol: LBP, MobileFaceNet (`mbf-w600k-int8-v1`), or ones from the other EdgeFace model.
- **Not checked on real faces.** The brief allowed downloading only weights (no face dataset), and nobody was in front of the camera. On our crops, EdgeFace's spread of different-people scores may differ a little from MobileFaceNet's, so recalibrate once real people are enrolled.
- **How to recalibrate on the Echo:**
  1. Enrol each person. Logcat (tag `VesperCamera`) prints `enrolled <id> with edgeface-xxs-fp16-v1: weakest pair X, threshold Y` (numbers only).
  2. Walk in a few times and have other people walk in. Each try logs `match try n: <id|-> score=S second=R`.
  3. Set **Match threshold** (advanced, web settings) about halfway between the highest stranger score and the lowest genuine score.
  4. If two enrolled people's `score − second` is often under 0.05, raise the margin in `MatchPolicy.NEURAL`.

## Screenshots (Echo, `tools/shots.sh`) vs `Profiles.dc.html`

`docs/status/shots/camera/`:
- `profiles-empty.png`: matches the design: shared face at 0.35, "Presence · 2 fps" + green dot at right 18 / top 16.
- `profiles-detecting.png`: from before Nov 23, 2026, when the face check was a "Who's there?" pill (now in the island).
- `profiles-recognized.png`: matches the layout: 560×132 sheet at bottom 30, 84 px avatar with rings, "Hi Maya" 30/800, subtitle, "Alarm 6:15 AM" / "Dinner 7:00 PM" chips, "Not me".
  - **Difference:** behind the card the design already shows Maya's pink Float face. The clock doesn't follow `profiles.active` yet (`:feature:clock`, contract request filed), so the shared Digital face shows instead.
- `profiles-personalized.png`: same dependency; it looks like StandBy until the clock applies the profile.
- `settings-profiles.png`, `enrol-consent.png`, `enrol-capture.png`: no artboard exists (NOTES §2.2 lists face enrolment as still to design). They're built from Profiles.dc.html's avatar and type styles: content on plain fills, one tinted primary.
- `probe.png`: capture gate screen.

## Models

Sources, versions, checksums, licences and conversion are in `android/feature/camera/MODELS.md`.

| What | Used | Licence |
|---|---|---|
| Face detection | MediaPipe BlazeFace short-range v1 (float16 TFLite, 230 KB); fallback `android.media.FaceDetector` | Apache-2.0 / AOSP |
| Face embedding | Idiap EdgeFace-XXS (default, 2.6 MB) and EdgeFace-XS γ0.6 (3.8 MB), fp16 TFLite from the official checkpoints; fallback LBP histograms | Code BSD-3-Clause. Weights trained on WebFace4M/12M: **non-commercial research only**, allowed for this non-commercial open-source project by user decision (NOTES §6.6). LBP: ours |
| Runtime | `org.tensorflow:tensorflow-lite:2.17.0` → LiteRT 1.0.1 (no AndroidX), armeabi-v7a only | Apache-2.0 |

## Left / open

- **Embedding time: done** (option c). EdgeFace-XXS takes 56–61 ms and XS ~102 ms; MobileFaceNet took ~350 ms.
  - Open choice for the lead: drop XS to save 3.75 MB of APK, and ~6 MB of PSS when XS is selected. It's kept because it's the higher-accuracy option and costs nothing unless selected.
  - Existing enrolments (MobileFaceNet ids) must be redone after this update. There's no migration, because templates hold only embeddings, never images.
- **Not verified on the device** (the room was dark in phases 1–2; in phase 3 it was lit, but nobody was in front of the camera):
  - Real presence triggering on motion in light.
  - BlazeFace vs FaceDetector on a real face (`detect-compare`), the quality thresholds, enrolment end to end, the EdgeFace match thresholds (see "Thresholds"), and XXS vs XS on real household faces.
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

## Nov 23: per-person preferences ("Your stuff")

- **`Profile`** has three optional preferences, null = follow Shared (the settings everyone uses): `wallpaper` (Liquid Glass / Widgets: Blue, Dusk, Graphite), `nightMode` (auto, schedule, on, off), `alarmSound` (system, chime, beeps; their own alarms only). `FaceProfileManager.encode` writes them only when set (`wallpaper`, `night`, `alarmSound`); `parse` reads profiles saved before they existed as null (`oldSavedProfilesMigrate`).
- **Profiles screen**: under the people, "Maya’s stuff": plain-fill chips for Clock · <face>, Wallpaper, Night, Alarm sound, the number of her alarms and whether her calendar is set. Wallpaper / Night / Alarm sound cycle on tap (… → Everyone’s); the alarm count opens the Alarms screen. With Shared active it shows a one-line hint instead. Tiles moved up 12 px (top 160) to make room. Built as `PersonalStuff` (pure, tested).
- **Demo Maya** (`DEMO_MAYA`): Liquid Glass on Dusk, orange, soft chime alarms. `profiles-demo` gives a Maya saved by an older build (no wallpaper) the demo's face, colour and wallpaper.
- Web settings: People shows each person's clock face, wallpaper, Night mode and alarm sound (`PUT /api/people/<id>/prefs`, options from the settings they override; see `:feature:settings`).
- **Unverified on the Echo**: the "Your stuff" row's layout and taps (built and unit-tested only). `--es screen profiles` after `profiles-demo` (Maya present), tap Maya.

## Nov 24: near / far (NOTES §6.5 item 2)

Branch `worktree-agent-a3c2570639e1d4d99`. The camera decides how far away whoever's looking is and publishes it as
`SystemState.viewDistance` (`NEAR` / `FAR`, new in `:core:model`); StandBy shows a big far layout for `FAR`
(`docs/status/clock.md`, "Near / far"). Setting `clock.farLayout` ("Big clock from across the room", Clock & StandBy
section, default on; the camera reads it too, so with it off nothing here runs beyond a boolean check).

**Budget rule: no new continuous work.** Everything comes from signals that already exist:

| Signal | Source | Cost |
|---|---|---|
| Touch | `CameraOverlayView` already calls `pipeline.onTouch()` on every touch-down | none |
| Motion size | the 80×60 frame difference of each presence frame (~1 fps): `MotionResult.changed` (area) plus a new `rows` (fraction of the 60 rows with ≥ 3 changed pixels: the change's height), counted in the same loop | one compare + increment per row; the per-sample time is unchanged within noise |
| Eye distance | the arrival face check's BlazeFace box (only when people are enrolled), normalised to a 320 px wide frame | none (already computed) |
| Presence | `EMPTY` is far evidence, `UNKNOWN` (camera off, blocked, dark, hidden) forces `NEAR` | none |

The only camera time it adds: **one look before a rest**. When it says FAR and a motion sample didn't look far
(unsure, or an approach), `maybeRest()` keeps the camera on for `NearFar.LOOK_MS` = 2.2 s (two more 1 fps samples)
before the 60 s rest, at most once per rest. Without it, someone walking up after a rest is seen in one frame only
(the first frame after a restart is never motion, and the second one starts the next rest). Worst case, someone moving
around mid-room all the time: +2.2 s of presence capture per ~62 s cycle ≈ 7 % × 2.2/62 ≈ **0.25 % of a core**;
an empty room: zero (no motion, no look).

**Decision** (`presence/NearFar.kt`, pure, main thread, 22 JVM tests in `NearFarTest` + 3 in `MotionRowsTest`):

- A presence sample is *near* if the change covers ≥ 20 % of the frame and ≥ 70 % of its rows, or it's an *approach*
  (≥ 1.6× the previous sample's area, ≥ 12 %, ≥ 50 % of rows, ≤ 3 s after it). *Far* if it moved but < 10 % area or
  < 50 % of rows (someone walking sideways across the room changes a wide patch but not the full height). Otherwise
  unsure; no motion is no evidence.
- A near sample counts only if a second one follows within 3 s. A face with eye distance ≥ 18 px (confidence ≥ 0.5)
  is near at once, ≤ 11 px is far evidence. A touch is near at once.
- **FAR → NEAR** is immediate on near evidence. **NEAR → FAR** needs the hold to be over (60 s after a touch, 45 s after
  camera evidence) *and* far evidence after the last near evidence. So it starts NEAR, stays NEAR while unsure ("default
  to the normal layout"), and changes at most once per hold (test `neverFlickers`).
- An empty room is FAR (whoever walks in starts across the room); the empty-room dim (0.35) still applies on top.

**Thresholds are estimates**, not measured on people (nobody could walk up to the Echo for this). Eye distance assumes
a ~60° horizontal field of view at 320 px (`eye px ≈ 17.5 / metres`): 18 px ≈ 1 m, 11 px ≈ 1.6 m. The area/rows numbers
assume a person within ~1 m fills most of the 4:3 frame. To calibrate: logcat tag `VesperCamera` prints every change
(`near/far: FAR (far away)`, `near/far: NEAR (close motion|approach|face|touch)`) and each extra look; the values are
constructor parameters of `NearFar`.

Files: `presence/NearFar.kt` (new), `presence/MotionDetector.kt` (`rows`), `CameraPipeline.kt` (feeds samples, faces
and touches, publishes `viewDistance`, the look before a rest).

**Measured** (Echo, debug build, `scripts/echo-perf.sh`, StandBy Digital, presence pipeline running, 30 s each):

| | CPU (app) | Frames | PSS |
|---|---|---|---|
| `clock.farLayout` off | 1.2 % | 1 | 76.2 MB |
| `clock.farLayout` on | 1.1 % | 1 | 76.2 MB |

No measurable difference: the decision runs once per presence sample (~1 per second while capturing), and the
`rows` count adds nothing visible to the per-sample time. The look before a rest wasn't seen on the device (see below);
its cost is the estimate above.

**Unverified on the Echo:**
- The real camera path from start to finish: no `near/far:` log line from a real person was captured (logcat had rotated
  and nobody walked up during the sessions). The layouts were checked with the forced `distance` extra.
- Every threshold on real people: the eye-distance numbers assume a ~60° HFOV; the area/rows numbers are guesses.
- The look before a rest, and touch → NEAR through the pipeline. Touch → near in StandBy was exercised only with the
  forced state.
- To check: stand ~3 m away until it goes FAR (≥ 45 s after the last near evidence), then walk up. Watch
  `logcat -s VesperCamera | grep near/far`.
