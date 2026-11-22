# Models in `:feature:camera`

All models are in `src/main/assets/models/`. `:app` stores `.tflite` files uncompressed, so they are memory-mapped (`ModelFiles.load`).

The weights were downloaded only from each project's own official location. Each checksum below was checked against a hash published by the server: GitHub's git blob SHA-1 (contents API), and Google Cloud Storage's `x-goog-hash`.

Licence decision, made by the user and recorded in NOTES §6.6: *"This is non commercial open source use so you can use the common face recognition models."*

## Face embedding: Idiap EdgeFace (XXS default, XS option)

Two models ship. The advanced setting **Face model** (`camera.embedder`) picks one: `edgeface-xxs` (default) or `edgeface-xs`. Each has its own embedder id, which is stored with every template, so switching models (or upgrading from the MobileFaceNet build) means re-enrolling.

| | EdgeFace-XXS (default) | EdgeFace-XS γ = 0.6 |
|---|---|---|
| File | `edgeface_xxs_fp16.tflite` (2,642,176 B) | `edgeface_xs_fp16.tflite` (3,750,728 B) |
| sha256 (shipped file) | `27bdb20e18153220ac561413150f189b674ffce5af666c142a2b6231e390859e` | `f108780ad0f4bb3e18681a4ad22c06d4c6228291fce34c3253756dc314beffd0` |
| Source | `https://github.com/otroshi/edgeface/raw/main/checkpoints/edgeface_xxs.pt` | `https://github.com/otroshi/edgeface/raw/main/checkpoints/edgeface_xs_gamma_06.pt` |
| Checkpoint size, git blob SHA-1 | 5,032,125 B, `9cd55d480a4e60c3ccb8d0e33730b89c6932c6f0` | 7,170,425 B, `af5f8c5cf75e8b90d82d08f7a07ee10677327bfd` |
| Checkpoint sha256 | `5b6bac48ea660aca185be8a8ac934db2093b51259461721da53c468230bc24c9` | `5ae7504cd9aee0a5d52c2115fd2eb66b0985dd1730f40134b5854e0cb658ce16` |
| Size | 1.24 M params, 47 M MACs | 1.77 M params, 76 M MACs |
| Published accuracy (EdgeFace README / paper) | LFW 99.57 % | LFW 99.73 %, IJB-C TAR 94.85 % at FAR 1e-4 |
| Embedder id | `edgeface-xxs-fp16-v1` | `edgeface-xs-g06-fp16-v1` |
| Runtime options | XNNPACK on, 2 threads | XNNPACK on, 2 threads |

- **What:** EdgeFace (George, Ecabert, Otroshi Shahreza, Kotwal, Marcel; IEEE T-BIOM 2024), winner of the compact track of the IJCB 2023 efficient face recognition competition. EdgeNeXt backbone with low-rank linear layers (LoRaLin), trained with CosFace loss on WebFace4M/12M. Input is 112×112 RGB aligned to the ArcFace 5-point template, values (x − 127.5) / 127.5. Output is a 512-d embedding, L2-normalised in `TfliteEmbedder`. Same input and output as MobileFaceNet, so alignment and matching didn't change.
- **Checksums:** each downloaded checkpoint's git blob SHA-1 (`sha1("blob <size>\0" + bytes)`) was checked against the blob SHA GitHub's contents API publishes for `checkpoints/` on `main`.
- **Licence:** the EdgeFace code and checkpoints are in a BSD-3-Clause repository (repo `LICENSE`). The training data (WebFace4M/12M, subsets of WebFace260M) is released for **non-commercial research only**. This project may use the models because it is non-commercial and open source (the user decision above, which allows non-commercial open-source weights). Commercial use needs another model or a data licence.
- The model code used for the export is the repo's own `backbones/` + `timmfr.py` (reviewed, run from the scratch dir; timm 1.0.31).

### Conversion

Done on desktop, in isolated Python 3.11 venvs.
- Tools: `torch` 2.15.0, `timm` 1.0.31 (export); `onnx` 1.17.0, `onnxsim` 0.4.36, `onnx2tf` 1.27.10, TensorFlow 2.19.0 (conversion).

Steps:
1. Load the checkpoint with `torch.load(…, weights_only=True)` (tensors only, no pickled code) into the official `get_model(name)`.
2. Switch every `nn.GELU` to `approximate="tanh"`. The exact (erf) GELU becomes an `Erf` op that TFLite only has as a Flex (TF select) op. The tanh form is within ~1e-3 of erf and costs nothing measurable (cos below).
3. `torch.onnx.export`, opset 17, batch 1, then `onnxsim`.
4. `onnx2tf` with `output_signaturedefs=True` (NCHW → NHWC). It writes fp32 and fp16-weight `.tflite`. onnx2tf's own test-data download was skipped (it is a pickled file); local random inputs were used. A dynamic-range int8 file was also made (`TFLiteConverter`, `Optimize.DEFAULT`) for comparison.

**Checks against the original PyTorch model** (exact erf GELU; cosine of the embeddings, min over 4 random inputs / the drawn test face):

| Variant | XXS | XS |
|---|---|---|
| fp32 | 0.99997 / 0.99999 | 0.99993 / 0.99999 |
| **fp16 (shipped)** | **0.99995 / 0.99998** | **0.99994 / 0.99997** |
| dynamic int8 | 0.99216 / 0.99672 | 0.97874 / 0.98945 |

On the Echo, the camera-bench `selftest` stage embeds the same drawn face with each shipped model and compares it with `selftest_drawn112_edgeface_<xxs|xs>.txt` (the PyTorch output). See "Measured on the Echo" below.

### Measured on the Echo

`tools/variants.sh`, one fresh process per variant, camera off, 2 threads unless noted. Other agents were still using the device a little (MobileFaceNet measured 437 ms here vs 303–346 ms in phase 2), so these are on the slow side.

| Variant | ms / embedding | cos vs PyTorch on the device |
|---|---|---|
| **XXS fp16, XNNPACK (default)** | **68.4** | **1.0000** |
| XXS fp32, XNNPACK | 73.6 | 1.0000 |
| XXS int8, built-in | 157.2 | 0.9955 |
| **XS fp16, XNNPACK (option)** | **112.2** | **1.0000** |
| XS fp32, XNNPACK | 118.3 | 1.0000 |
| XS fp32, XNNPACK, 4 threads | 118.0 | |
| XS int8, built-in | 253.8 | 0.9924 |
| MobileFaceNet int8, built-in (old default) | 436.7 | 0.9887 |

- **fp16 on XNNPACK** is the fastest form of both models. On armv7 the fp16 weights are unpacked to fp32 at load, so it runs like fp32 with a smaller file. XNNPACK takes all but 24 nodes, in 19 partitions (XXS fp32: 444 of 468; the final run on the Echo logged 862 of 886).
- **int8 is slower** here: dynamic-range int8 runs on the built-in kernels (XNNPACK can't take it), and EdgeFace is small enough that XNNPACK's fp32 kernels win. It also costs accuracy (XS desktop cos 0.979, under the 0.98 bar).
- **4 threads gained nothing** over 2 (118.0 vs 118.3 ms), so 2 stays.
- **Final run** (13:01, after a 10 s settle):
  - XXS: **61.0 ms**, PSS +16.8 MB while loaded.
  - XS: **102.2 ms**, +22.9 MB.
  - Both at cos 1.0000 against PyTorch.
  - Live on the camera, XXS took 56.4 ms per embedding.
  - docs/status/camera.md has the details.

### Why MobileFaceNet was dropped

EdgeFace-XS is ~4× faster (112 vs 303–437 ms) and better on LFW (99.73 vs MobileFaceNet-class ~99.5 %), so the MobileFaceNet option would never be the right pick. Keeping it would cost 3.6 MB of APK for nothing. Its files (`mobilefacenet_w600k_int8.tflite`, `selftest_drawn112_onnx.txt`) are deleted. Templates enrolled with `mbf-w600k-int8-v1` are ignored and the person re-enrols. Its source and checksums are in git history (this file, before the EdgeFace change).

`ModelSelfTest.variant` can still compare other files: drop `models/<name>.tflite` into the assets with a reference `models/selftest_drawn112_<family>.txt` (family = name without its last `_part`), and pass `--es model <name>` (`tools/variants.sh <name>:<threads>[:xnnpack]`).

## Face detection: MediaPipe BlazeFace short-range

| | |
|---|---|
| File | `blaze_face_short_range.tflite` (229,746 B, float16 weights) |
| Source | `https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite` (MediaPipe Tasks face detector, version 1) |
| sha256 | `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` (MD5 matches GCS `x-goog-hash` `o91uwxclKQdwuXzsDL+UyQ==`) |
| What | Input is 128×128 RGB in [−1, 1], letterboxed. Outputs are `regressors` [1, 896, 16] and `classificators` [1, 896, 1]. Decoding (`BlazeFace.decode`) uses the options of the `face_detection_short_range` graph: SSD anchors with strides 8/16/16/16, scale 128, sigmoid score ≥ 0.5, and weighted NMS at IoU 0.3. The six keypoints are right eye, left eye, nose tip, mouth, right ear and left ear. |
| Licence | Apache-2.0 (MediaPipe; BlazeFace model card) |
| Runtime options | XNNPACK on, 2 threads. All 164 nodes are delegated. |

## Self-test references

`selftest_drawn112_edgeface_xxs.txt` (4,852 B; sha256 `9f86b1ce238212b719e888b91789bb372d0e2691e0cff408749a9de1cb708300`) and `selftest_drawn112_edgeface_xs.txt` (4,866 B; sha256 `63c702a03f860c37ca30704fd05668644315262eb91f6ef152452eb7ad0a6535`) each hold 512 floats. Each is the original PyTorch model's (exact GELU) normalised embedding of the drawn 112×112 cartoon face from `SyntheticFace.rgb(112, 112, 56, 56, 30)`. There's no real face in them.

## Runtime

`org.tensorflow:tensorflow-lite:2.17.0` (Apache-2.0). On Maven Central it is a relocation to `com.google.ai.edge.litert:litert:1.0.1`, which depends only on `litert-api:1.0.1`.
- No AndroidX, support or metadata libraries come with it.
- Only `libtensorflowlite_jni.so` for armeabi-v7a ships (2,366,508 B), because `:app` filters ABIs.

## Not models

`LbpEmbedder` (uniform LBP histograms) and `android.media.FaceDetector` need no model files. They are the fallbacks:
- `LbpEmbedder` is used if the embedding model can't be loaded or run (and `camera.embedder=lbp`, debug, forces it).
- `android.media.FaceDetector` is used if BlazeFace can't be loaded, or when `camera.detector=legacy` is set.
