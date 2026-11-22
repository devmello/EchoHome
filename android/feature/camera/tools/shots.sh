#!/bin/bash
# Screenshots of every Profiles state for the design comparison (our own UI only, never camera
# frames). Writes into docs/status/shots/camera/.
#   ./scripts/build-install.sh --no-build --wait 1 --then 'android/feature/camera/tools/shots.sh'
set -e
: "${ADB:?run via build-install.sh / echo-lock.sh}" "${SERIAL:?}"
OUT=$(cd "$(dirname "$0")/../../../.." && pwd)/docs/status/shots/camera
mkdir -p "$OUT"
A() { "$ADB" -s "$SERIAL" "$@"; }
start() { A shell am start -n app.vesper/.MainActivity "$@" >/dev/null 2>&1; }
shot() { A exec-out screencap -p > "$OUT/$1.png"; echo "shot $OUT/$1.png"; }
A shell pm grant app.vesper android.permission.CAMERA
A shell am force-stop app.vesper
A shell am start -W -n app.vesper/.MainActivity >/dev/null; sleep 4
for step in empty detecting recognized personalized; do
  start --es screen profiles-demo --es step "$step"; sleep 2.5
  shot "profiles-$step"
done
A shell input keyevent KEYCODE_BACK; sleep 1
start --es screen profiles; sleep 2; shot settings-profiles
start --es screen profiles-enrol --es profile maya; sleep 2; shot enrol-consent
# "Maya agrees" (primary button on the consent step)
A shell input tap ${AGREE_X:-330} ${AGREE_Y:-330}; sleep 3; shot enrol-capture
A shell input keyevent KEYCODE_BACK; sleep 1
start --es screen camera-bench --es stage presence; sleep 1
A shell input keyevent KEYCODE_BACK; sleep 4
shot standby-presence
