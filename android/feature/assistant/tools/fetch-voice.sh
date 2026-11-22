#!/bin/bash
# Downloads the on-device voice runtime and models from sherpa-onnx's official GitHub releases
# (github.com/k2-fsa/sherpa-onnx, Apache-2.0), checks their SHA-256, and puts them where the build
# and the push script expect them. Nothing here is committed (see ../.gitignore).
#
#   android/feature/assistant/tools/fetch-voice.sh          # libs + all models
#
# Output:
#   android/feature/assistant/src/main/jniLibs/armeabi-v7a/{libonnxruntime.so,libsherpa-onnx-jni.so}
#   android/feature/assistant/build/voice-models/{kws,stt,tts}/...   (pushed by push-voice-models.sh)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$(cd "$HERE/.." && pwd)
CACHE=${VOICE_CACHE:-$HOME/.cache/echo-bedside-voice}
OUT="$MOD/build/voice-models"
JNI="$MOD/src/main/jniLibs/armeabi-v7a"
SHERPA=v2.0.1
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
mkdir -p "$CACHE" "$OUT" "$JNI"

fetch() { # url file sha256
  local f="$CACHE/$2"
  if [ ! -f "$f" ]; then echo "download $2"; curl -fL --retry 3 -o "$f.part" "$1"; mv "$f.part" "$f"; fi
  echo "$3  $f" | shasum -a 256 -c - >/dev/null || { echo "checksum mismatch: $2" >&2; rm -f "$f"; exit 1; }
}

# 1. Native runtime (onnxruntime + sherpa-onnx JNI), armeabi-v7a only.
fetch "$GH/$SHERPA/sherpa-onnx-$SHERPA-android.tar.bz2" "sherpa-onnx-$SHERPA-android.tar.bz2" \
  39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330
tmp=$(mktemp -d)
tar xjf "$CACHE/sherpa-onnx-$SHERPA-android.tar.bz2" -C "$tmp" ./jniLibs/armeabi-v7a/libonnxruntime.so ./jniLibs/armeabi-v7a/libsherpa-onnx-jni.so
cp "$tmp"/jniLibs/armeabi-v7a/*.so "$JNI/"
rm -rf "$tmp"
echo "libs: $(du -sh "$JNI" | cut -f1) in $JNI"

# 2. Models: keyword spotter (wake word), streaming STT, Piper TTS voice.
unpack() { # tarball dest-subdir file...
  local tb=$1 dest=$2; shift 2
  local tmp; tmp=$(mktemp -d)
  tar xjf "$CACHE/$tb" -C "$tmp"
  local dir; dir=$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)
  rm -rf "${OUT:?}/$dest"; mkdir -p "$OUT/$dest"
  for f in "$@"; do cp -R "$dir/$f" "$OUT/$dest/"; done
  rm -rf "$tmp"
  echo "$dest: $(du -sh "$OUT/$dest" | cut -f1)"
}

# The non-"mobile" build: the -mobile int8 encoder fails at its own chunk size (Reshape /downsample, ORT 1.21 too).
KWS=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
fetch "$GH/kws-models/$KWS.tar.bz2" "$KWS.tar.bz2" f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a
unpack "$KWS.tar.bz2" kws encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx decoder-epoch-12-avg-2-chunk-16-left-64.onnx \
  joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx tokens.txt README.md
# "Hey Nova" (placeholder wake word) in the model's BPE units; see docs/assistant-setup.md to change it.
printf '▁HE Y ▁NO V A :2.0 #0.25 @HEY_NOVA\n' > "$OUT/kws/keywords.txt"

STT=sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile
fetch "$GH/asr-models/$STT.tar.bz2" "$STT.tar.bz2" 753a5362538539212442efbfb8dd5748db82e35e7deaec5330f49c56e81e40fd
unpack "$STT.tar.bz2" stt encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt README.md

TTS=vits-piper-en_US-amy-low-int8
fetch "$GH/tts-models/$TTS.tar.bz2" "$TTS.tar.bz2" 93070ac9fadf512e56c46bdd0c5d2ce96b424fdc4e683d560167410bd2c4df7d
unpack "$TTS.tar.bz2" tts en_US-amy-low.onnx en_US-amy-low.onnx.json tokens.txt MODEL_CARD
# sherpa-onnx 2.x dropped espeak-ng (GPL): Piper voices take phonemes from this English lexicon instead.
fetch "$GH/tts-models/lexicon-en-us.txt" lexicon-en-us.txt 8b67ae549a465eee78f92ecb74644ad90c3c58ce65f19c68baf85878a10ef73a
cp "$CACHE/lexicon-en-us.txt" "$OUT/tts/"

echo "models ready in $OUT (push with tools/push-voice-models.sh)"
