#!/bin/bash
# Pushes the voice models fetched by fetch-voice.sh to the app's external files dir on the Echo:
#   /sdcard/Android/data/com.echobedside/files/voice/{kws,stt,tts}
# (readable by the app without any permission; no root, no /system). Holds the device lock.
# The app must have been installed once (so the directory exists and belongs to it).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../../.." && pwd)
. "$ROOT/scripts/lib.sh"
SRC="$HERE/../build/voice-models"
DEST=/sdcard/Android/data/$PKG/files/voice
[ -d "$SRC/stt" ] || { echo "run tools/fetch-voice.sh first" >&2; exit 1; }
lock_acquire
echo_connect >/dev/null
adb_echo shell mkdir -p "$DEST"
for m in "${@:-kws stt tts}"; do
  for d in $m; do
    echo "push $d ($(du -sh "$SRC/$d" | cut -f1))"
    adb_echo shell rm -rf "$DEST/$d"
    adb_echo push "$SRC/$d" "$DEST/" >/dev/null
  done
done
adb_echo shell du -sh "$DEST"/*
lock_release
