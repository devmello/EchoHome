#!/bin/bash
# Embedder variants on the drawn face, one fresh process each (camera off), for ms and PSS:
#   ./scripts/build-install.sh --no-build --wait 1 --then 'android/feature/camera/tools/variants.sh fp16:2 fp16:4 int8:4:false'
# Each argument is asset:threads[:xnnpack] (asset = models/<asset>.tflite); ~12 s per variant.
set -e
: "${ADB:?run via build-install.sh / echo-lock.sh}" "${SERIAL:?}"
A() { "$ADB" -s "$SERIAL" "$@"; }
for v in "$@"; do
  IFS=: read -r model threads xnn <<<"$v"
  A shell am force-stop com.echobedside
  A logcat -c
  A shell am start -n com.echobedside/.MainActivity --es screen camera-bench --es stage selftest \
    --es model "$model" --es threads "${threads:-2}" --es xnnpack "${xnn:-true}" >/dev/null 2>&1
  for _ in $(seq 1 30); do
    sleep 1
    A logcat -d -s EchoCamera:* | grep -q "selftest: done" && break
  done
  A logcat -d -s EchoCamera:* | grep -E "selftest: (variant|failed)" | sed 's/.*selftest: //'
  A logcat -d -s tflite:* | grep -E "Replacing" | tail -1 | sed 's/.*tflite *: /  /'
done
A shell input keyevent KEYCODE_BACK
