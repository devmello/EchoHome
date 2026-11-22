#!/bin/bash
# Screenshot the Echo (holding the device lock), optionally opening a screen first.
# Usage: ./scripts/echo-shot.sh OUT.png [screen-id] [more am-start extras...]
set -e
. "$(dirname "$0")/lib.sh"
OUT=${1:?usage: echo-shot.sh OUT.png [screen] [extras]}; shift
SCREEN=${1:-}; [ $# -gt 0 ] && shift
lock_acquire
echo_connect >/dev/null
if [ -n "$SCREEN" ]; then
  adb_echo shell am start -W -n "$HOME_ACTIVITY" --es screen "$SCREEN" "$@" >/dev/null
  sleep "${WAIT:-2}"
fi
mkdir -p "$(dirname "$OUT")"
adb_echo exec-out screencap -p > "$OUT"
echo "screenshot: $OUT"
lock_release
