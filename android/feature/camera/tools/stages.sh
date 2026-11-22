#!/bin/bash
# Measures pipeline stages one by one with the camera-bench screen (agent H).
#   ./scripts/build-install.sh --no-build --wait 1 --then 'android/feature/camera/tools/stages.sh presence detect'
set -e
: "${ADB:?run via build-install.sh / echo-lock.sh}" "${SERIAL:?}"
HERE=$(cd "$(dirname "$0")" && pwd)
A() { "$ADB" -s "$SERIAL" "$@"; }
A shell pm grant app.vesper android.permission.CAMERA
A shell am force-stop app.vesper
A logcat -c
A shell am start -W -n app.vesper/.MainActivity >/dev/null; sleep 5
for st in "$@"; do
  A shell am start -n app.vesper/.MainActivity --es screen camera-bench --es stage "$st" >/dev/null 2>&1; sleep 5
  MEASURE_SEC=${MEASURE_SEC:-30} "$HERE/measure.sh" "bench stage=$st"
  A logcat -d -s VesperCamera:* VesperCam1:* | grep -E "bench $st|restart failed" | tail -1
  A shell input keyevent KEYCODE_BACK; sleep 2
done
