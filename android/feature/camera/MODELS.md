# Models in `:feature:camera`

All models are in `src/main/assets/models/`. `:app` stores `.tflite` files uncompressed, so they are memory-mapped (`ModelFiles.load`).

The weights were downloaded only from each project's own release. Each checksum below was checked against a hash published by the server: GitHub's blob `Content-MD5`, and Google Cloud Storage's `x-goog-hash`.

Licence decision, made by the user and recorded in NOTES §6.6: *"This is non commercial open source use so you can use the common face recognition models."*

## Face embedding: InsightFace MobileFaceNet `w600k_mbf`

| | |
|---|---|
| File | `mobilefacenet_w600k_int8.tflite` (3,629,496 B) |
| sha256 (shipped file) | `148b58cf1f6d9c9f7ad6bfae9c70f3ee660a1df627eafc2eade24fd085c116e3` |
| Source | `https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_sc.zip` (InsightFace model pack `buffalo_sc`, release v0.7), file `w600k_mbf.onnx` |
| sha256 (`buffalo_sc.zip`, 14,969,382 B) | `57d31b56b6ffa911c8a73cfc1707c73cab76efe7f13b675a05223bf42de47c72` (MD5 matches GitHub's `Content-MD5` `Ub31GrEoruwsGRE67lIsXg==`) |
| sha256 (`w600k_mbf.onnx`, 13,616,099 B) | `9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f` |
| What | MobileFaceNet ("MBF"; 3.4 M weights, 440 M MACs), trained with ArcFace loss on WebFace600K. Input is 112×112 RGB, aligned to the ArcFace 5-point template, with values (x − 127.5) / 127.5. Output is a 512-d embedding, L2-normalised in `TfliteEmbedder`. |
| Licence | InsightFace code is MIT. Its pretrained models are released **for non-commercial research use only**, and the WebFace260M/600K training data has the same restriction. This project may use them only because it is non-commercial and open source (the user decision above). Commercial use needs a licence from InsightFace or a different model. |
| Embedder id | `mbf-w600k-int8-v1`. It is stored with every template, so a model change means re-enrolling. |
| Runtime options | TFLite built-in kernels with **XNNPACK off**, 2 threads (see "Why int8" below). |

### Conversion

Done on desktop, in an isolated Python 3.11 venv.
- Tools: `onnx` 1.17.0, `onnxsim` 0.4.36, `onnx2tf` 1.27.10, TensorFlow 2.19.0, `onnxruntime` 1.21.0.

Steps:
1. Set the ONNX batch dimension to a static 1, then run `onnxsim`.
2. Run `onnx2tf -i mbf_static.onnx -o out` to convert NCHW → NHWC. This writes a SavedModel plus fp32 and fp16 `.tflite` files. onnx2tf's own test-image download was skipped: it is a pickled file, so local random inputs were used instead.
3. Run `tf.lite.TFLiteConverter.from_saved_model(out)` with `optimizations = [tf.lite.Optimize.DEFAULT]` and no representative dataset. This gives dynamic-range quantisation: int8 weights per channel, float activations.

Checks against the original ONNX (cosine of the output embeddings):

| Model | Random inputs | Drawn test face |
|---|---|---|
| fp16 | 0.99999 | 0.99999 |
| int8 (desktop) | ~0.988 | 0.9917 |

On the Echo, the camera-bench `selftest` stage embeds the same drawn face and compares it with `selftest_drawn112_onnx.txt`, the original model's output (see docs/status/camera.md).

### Why int8 and not fp16

Each variant was measured on the Echo in a fresh process with `tools/variants.sh`. The table below shows the results.
- None of them is faster: the network is compute-bound at roughly 1.5 GMAC/s on the A53.
- fp16 and fp32 on XNNPACK repack the weights into a second fp32 copy.
- XNNPACK can't run the dynamic-range int8 convolutions.
- int8 on the built-in kernels is the only variant inside the +20 MB budget.

| Variant | ms / embedding | PSS added while loaded |
|---|---|---|
| fp16, XNNPACK, 2 threads | 283 | +38.8 MB |
| fp16, XNNPACK, 4 threads | 300 | +39.2 MB |
| fp32, XNNPACK, 4 threads | 300 | +32.7 MB |
| fp32, built-in, 4 threads | 265 | +22.2 MB |
| int8, XNNPACK (partial), 4 threads | 335 | +15.0 MB |
| **int8, built-in, 4 threads** | **303** | **+13.8 MB** |

`ModelSelfTest.variant` can still compare other files: drop them into the assets folder as `mobilefacenet_w600k_<name>.tflite` and pass `--es model <name>`.

## Face detection: MediaPipe BlazeFace short-range

| | |
|---|---|
| File | `blaze_face_short_range.tflite` (229,746 B, float16 weights) |
| Source | `https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite` (MediaPipe Tasks face detector, version 1) |
| sha256 | `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` (MD5 matches GCS `x-goog-hash` `o91uwxclKQdwuXzsDL+UyQ==`) |
| What | Input is 128×128 RGB in [−1, 1], letterboxed. Outputs are `regressors` [1, 896, 16] and `classificators` [1, 896, 1]. Decoding (`BlazeFace.decode`) uses the options of the `face_detection_short_range` graph: SSD anchors with strides 8/16/16/16, scale 128, sigmoid score ≥ 0.5, and weighted NMS at IoU 0.3. The six keypoints are right eye, left eye, nose tip, mouth, right ear and left ear. |
| Licence | Apache-2.0 (MediaPipe; BlazeFace model card) |
| Runtime options | XNNPACK on, 2 threads. All 164 nodes are delegated. |

## Self-test reference

`selftest_drawn112_onnx.txt` (4,870 B; sha256 `27ae237a9f1f3d6a11bca9ac86055e081c545b79694f1c349327426ef36c3a18`) holds 512 floats. It is the original ONNX model's normalised embedding of the drawn 112×112 cartoon face from `SyntheticFace.rgb(112, 112, 56, 56, 30)`. There's no real face in it.

## Runtime

`org.tensorflow:tensorflow-lite:2.17.0` (Apache-2.0). On Maven Central it is a relocation to `com.google.ai.edge.litert:litert:1.0.1`, which depends only on `litert-api:1.0.1`.
- No AndroidX, support or metadata libraries come with it.
- Only `libtensorflowlite_jni.so` for armeabi-v7a ships (2,366,508 B), because `:app` filters ABIs.

## Not models

`LbpEmbedder` (uniform LBP histograms) and `android.media.FaceDetector` need no model files. They are the fallbacks:
- `LbpEmbedder` is used if the embedding model can't be loaded.
- `android.media.FaceDetector` is used if BlazeFace can't be loaded, or when `camera.detector=legacy` is set.
