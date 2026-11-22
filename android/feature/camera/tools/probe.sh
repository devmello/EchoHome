#!/bin/bash
# Capture gate (agent H): runs the camera-probe debug screen and prints its log.
# Run through the device lock, from the checkout root:
#   ./scripts/build-install.sh --no-build --wait 1 --then 'android/feature/camera/tools/probe.sh [extras]'
# extras are `am start` extras, e.g. --es flash true --es api camera1
set -e
: "${ADB:?run via build-install.sh / echo-lock.sh}" "${SERIAL:?}"
SEC=${PROBE_SEC:-28}
"$ADB" -s "$SERIAL" shell pm grant app.vesper android.permission.CAMERA || true
"$ADB" -s "$SERIAL" shell am force-stop app.vesper
"$ADB" -s "$SERIAL" logcat -c
# shellcheck disable=SC2068
"$ADB" -s "$SERIAL" shell am start -W -n app.vesper/.MainActivity --es screen camera-probe $@ >/dev/null
sleep "$SEC"
"$ADB" -s "$SERIAL" logcat -d -s VesperCamProbe:* VesperCam1:* VesperCam2:* AndroidRuntime:E | grep -v beginning
