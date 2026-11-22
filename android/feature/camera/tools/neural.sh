#!/bin/bash
# Neural face pipeline on the live camera (agent H): recognize bench (BlazeFace + align +
# MobileFaceNet each frame), both detectors on the same frames, then PSS after the camera is off.
#   ./scripts/build-install.sh --no-build --wait 1 --then 'android/feature/camera/tools/neural.sh'
# ~75 s under the device lock.
set -e
: "${ADB:?run via build-install.sh / echo-lock.sh}" "${SERIAL:?}"
HERE=$(cd "$(dirname "$0")" && pwd)
A() { "$ADB" -s "$SERIAL" "$@"; }
A shell pm grant com.echobedside android.permission.CAMERA
A shell am force-stop com.echobedside
A logcat -c
A shell am start -W -n com.echobedside/.MainActivity >/dev/null; sleep 4
for st in recognize detect-compare; do
  A shell am start -n com.echobedside/.MainActivity --es screen camera-bench --es stage "$st" >/dev/null 2>&1; sleep 6
  MEASURE_SEC=${MEASURE_SEC:-20} "$HERE/measure.sh" "bench stage=$st"
  A logcat -d -s EchoCamera:* | grep -E "bench $st" | tail -1
  A shell input keyevent KEYCODE_BACK; sleep 2
done
A logcat -d -s EchoCamera:* | grep -cE "compare:" | sed 's/^/compare lines with a face: /'
sleep 4
MEASURE_SEC=5 "$HERE/measure.sh" "camera off again (models released)"
A logcat -d -s EchoCamera:* | grep -E "camera (on|off)|face model|BlazeFace failed" | tail -6
