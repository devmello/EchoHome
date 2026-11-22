#!/bin/bash
# Fetches the assistant's on-device voice (sherpa-onnx) from the official GitHub releases, with
# pinned SHA-256 sums. The libs and models are git-ignored, so a fresh checkout runs this once.
#
#   ./scripts/fetch-voice.sh          # armv7 JNI libs into the module + all models (staged)
#   ./scripts/fetch-voice.sh --libs   # only the JNI libs: enough for a build with a working voice
#   ./scripts/fetch-voice.sh --push   # all of it, then push the models to the Echo (device lock)
#
# Without the libs the app still builds, but the assistant says the voice isn't installed, and an
# APK built that way replaces a working one on the Echo. Details: docs/assistant-setup.md.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MOD="$ROOT/android/feature/assistant"
case "${1:-}" in
  --libs) exec "$MOD/tools/fetch-voice.sh" --libs ;;
  --push) "$MOD/tools/fetch-voice.sh"; exec "$MOD/tools/push-voice-models.sh" ;;
  "") exec "$MOD/tools/fetch-voice.sh" ;;
  *) echo "usage: $0 [--libs|--push]" >&2; exit 2 ;;
esac
