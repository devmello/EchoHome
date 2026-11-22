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
  - Detector: **MediaPipe BlazeFace short-range** (TFLite, XNNPACK, letterboxed 128×128 RGB, gives six landmarks). `android.media.FaceDetector` is the fallback if the model can't load, and the `camera.detector=legacy` option.
  - Quality filter: eye distance ≥22 px, confidence ≥0.45, crop inside the frame, mean luma 40–225, Laplacian-variance sharpness, left/right symmetry (frontal). Measured on the grey geometry crop, as before.
  - **Alignment**: the 112×112 RGB crop is warped onto the ArcFace template. It uses a least-squares similarity (Umeyama) from BlazeFace's eyes, nose and mouth. Without landmarks (legacy detector) it uses the eye midpoint and distance. Colour comes from the NV21 chroma.
  - **Embedding**: InsightFace MobileFaceNet `w600k_mbf` (ArcFace loss), dynamic-range int8, TFLite built-in kernels, 512-d. Sources, checksums, licences and conversion are in `android/feature/camera/MODELS.md`. LBP is the fallback if the model fails to load or run (`FallbackEmbedder`), and `camera.embedder=lbp` (debug) forces it.
  - Cosine against 3–5 templates per person, then the per-embedder `MatchPolicy` (see "Thresholds" below). Switch after 2 consecutive matches, ≤3 tries.
  - **Models load on the first detection frame** of a burst or enrolment and are **freed whenever the camera stops**: the end of a burst (back to presence size), the end of enrolment, the screen off, an error. Both happen under one lock on the camera thread.
  - "Not me" blocks that person for 5 min.
- **Settings page** (`host.registerSettings`): section "Camera & people", order 60. Every setting applies live (the pipeline and `FaceProfileManager` watch `settings.changes`):
  - Presence detection (`camera.stage.presence`)
  - Duty-cycled presence (advanced)
  - Auto by face
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
  - `camera-bench --es stage presence|presence-duty|detect|detect-compare|recognize|selftest` (holds a stage for measuring, logs per-stage timings). `detect-compare` runs both detectors on every frame.
  - `selftest` needs no camera and no person. It runs both detectors on drawn cartoon faces (`SyntheticFace`), then alignment and the embedder, with PSS readings. It checks the embedding against the original ONNX model's output for the same drawing. `--es model <name> --es threads N --es xnnpack false` tests one variant.
  - `profiles-demo --es step empty|detecting|recognized|personalized|cycle` (design review without a camera).
- **Tools** (in `android/feature/camera/tools/`):
  - `probe.sh`: the capture gate.
  - `measure.sh`: CPU of the app, cameraserver and camerahalserver, plus PSS.
  - `stages.sh`: per-stage bench.
  - `shots.sh`: every Profiles screenshot.
  - `variants.sh`: embedder variants, a fresh process each.
  - `neural.sh`: live recognize bench, detect-compare, PSS after the camera is off.
- **Tests**: 92 JVM tests. New: similarity fit, the landmark → template transform (eye order irrelevant), alignment on a drawn face, NV21 → RGB, letterbox, BlazeFace anchors, decode with weighted NMS on synthetic tensors, embedder fallback, neural threshold clamping, the settings section, and live settings. The original 76 cover covering frame differencing (noise, global shift, small changes, dark frames, strided, pixel-stride-2 and direct buffers), hysteresis, cosine and the matcher (thresholds, margin, exclusion), consecutive-match logic, LBP, the quality filter, the crop, the SecretBox envelope (round trip, layout, fresh IV, tampering, wrong AAD or key, truncation, version, IV length), TemplateStore (encrypted on disk, AAD binding, path safety) and FaceProfileManager (persistence, Auto, Not me cool-down, timeout with virtual time).

## Measured on the Echo (debug build, Nov 22, 2026 04:28–04:44, dark room)

CPU is % of one core over 30 s (`tools/measure.sh`; CLK_TCK 100). The camera HAL does most of the work.

| Stage | app | cameraserver | camerahalserver | **total** | device busy (4 cores) | PSS app / cs / HAL |
|---|---|---|---|---|---|---|
| Baseline: StandBy, camera released | 0.0 | 0.0 | 0.0 | **0** | 0.8 % | 32.3 / 5.1 / 15.8 MB |
| 1 Presence, 176×144 @ 2 fps (default) | 1.6 | 3.1 | 13.5 | **18.2** | 5.5 % | 34.6 / 6.2 / 42.6 MB |
| 1 Presence, duty-cycled preview (option) | 0.8 | 1.4 | 10.0 | **12.2** | 4.6 % | 36.2 / 6.2 / 42.6 MB |
| 2 Detection, 320×240 @ 5 fps (burst only) | 49.0 | 3.7 | 14.1 | **66.8** | 18.8 % | 38.7 / 6.7 / 43.1 MB |
| 3 Recognition, LBP (phase 1; neural numbers below) | +16 ms per try | | | | | |

- **Presence**: 0.43 ms per sample in the app (downscale + diff; 2.7 ms before the array fast path). Sensor + HAL streaming costs ~14 % of a core whatever our sample rate, because the HAL won't go below 15 fps (it ran at 9.5 fps in the dark). Averaged over the device that's ~4.6 % of total CPU, but only while the room is lit and our window is visible: in the dark the camera is released (0 %).
  - Duty-cycling the preview saves a third (12.2 %). Restarting the preview takes 626 ms, though, so sampling drops to ~1.1 fps. It's off by default; `camera.presence.duty=true` turns it on.
- **Detection**: `FaceDetector` takes 59–61 ms per 320×240 frame (max 150 ms), so ~30 % of a core at 5 fps. The first measurement showed 95 % app CPU: ~46 % of that was the full-screen overlay redrawing the spinner every vsync. Capped at 20 fps, the burst costs 49 %. A burst lasts ≤2.7 s, once per arrival (≤3 bursts).
- **Recognition (LBP, phase 1)**: 16–17 ms per crop + LBP embedding + match (max 149 ms on the first, JIT), ≤3 tries per arrival.
- **RAM**: the whole pipeline is ≈ +30 MB while the camera is on (app +2–6 MB, cameraserver +1.1, camerahalserver +27), under the ~40 MB budget. It's all released with the camera.

## Neural pipeline, measured on the Echo (debug build, Nov 22, 2026 05:29–05:45, dark room)

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

- **Floor 0.36.** ArcFace-loss embeddings put different people's cosines around 0, with a spread of ~0.06–0.10 for MobileFaceNet-class models. 0.36 is ≥3.5 spreads above that, roughly the FAR 1e-4 to 1e-5 point usually quoted for this model family.
- **Per person: weakest pair of their 5 enrolment shots − 0.10, kept within 0.36–0.55.**
  - The same person across angles and light usually scores 0.45–0.8.
  - The slack lets a live frame be a bit worse than enrolment.
  - The 0.55 ceiling stops a very consistent enrolment (pairs at 0.8) from demanding ≥0.7 live.
- **Margin 0.05 over the runner-up,** so relatives don't flip-flop.
- **Then 2 consecutive matches,** with "Not me" as the safety net.
- **LBP keeps its old rule** (floor 0.5, slack 0.01, margin 0.02). Templates carry the embedder id (`mbf-w600k-int8-v1`), so LBP templates from before are ignored and the person needs to re-enrol.
- **Not checked on real faces.** The brief allowed downloading only weights (no face dataset), and nobody was in front of the camera.
- **How to recalibrate on the Echo:**
  1. Enrol each person. Logcat (tag `EchoCamera`) prints `enrolled <id> with mbf-w600k-int8-v1: weakest pair X, threshold Y` (numbers only).
  2. Walk in a few times and have other people walk in. Each try logs `match try n: <id|-> score=S second=R`.
  3. Set **Match threshold** (advanced, web settings) about halfway between the highest stranger score and the lowest genuine score.
  4. If two enrolled people's `score − second` is often under 0.05, raise the margin in `MatchPolicy.NEURAL`.

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

Sources, versions, checksums, licences and conversion are in `android/feature/camera/MODELS.md`.

| What | Used | Licence |
|---|---|---|
| Face detection | MediaPipe BlazeFace short-range v1 (float16 TFLite, 230 KB); fallback `android.media.FaceDetector` | Apache-2.0 / AOSP |
| Face embedding | InsightFace MobileFaceNet `w600k_mbf` (buffalo_sc v0.7), dynamic-range int8 TFLite (3.6 MB); fallback LBP histograms | **non-commercial research only**, allowed for this non-commercial open-source project by user decision (NOTES §6.6) / ours |
| Runtime | `org.tensorflow:tensorflow-lite:2.17.0` → LiteRT 1.0.1 (no AndroidX), armeabi-v7a only | Apache-2.0 |

## Left / open

- **Embedding time misses the ~100 ms target.** It is ~350 ms per embedding because the network is 440 M MACs. Options for the lead:
  - (a) Accept it: ≤3 tries per arrival, so "Hi <name>" comes ~1.5 s after someone walks in.
  - (b) Full-integer (static) quantisation. That needs a few hundred real aligned faces for calibration, which we don't have, and it can't be checked without a face set either.
  - (c) A smaller ArcFace-class model, ~150 M MACs (e.g. EdgeFace-XS or GhostFaceNet), if one is approved and has an upstream weights release. `FaceEmbedder` and the embedder id make that a drop-in.
- **Not verified on the device (the room was dark the whole session):**
  - Real presence triggering on motion in light.
  - BlazeFace vs FaceDetector on a real face (`detect-compare`), the quality thresholds, enrolment end to end, and the neural match thresholds (see "Thresholds").
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
