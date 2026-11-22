#!/bin/bash
# Camera cost on the Echo over SEC seconds: CPU of our app AND the camera daemons (cameraserver,
# camerahalserver — the HAL does most of the work), the whole device's CPU, and PSS.
# Run under the device lock ($ADB, $SERIAL set by build-install.sh / echo-lock.sh):
#   MEASURE_SEC=30 android/feature/camera/tools/measure.sh "label"
set -e
: "${ADB:?run via build-install.sh / echo-lock.sh}" "${SERIAL:?}"
SEC=${MEASURE_SEC:-30}
LABEL=${1:-measure}
sh() { "$ADB" -s "$SERIAL" shell "$@" | tr -d '\r'; }
sh pm grant app.vesper android.permission.CAMERA >/dev/null 2>&1 || true
APP=$(sh pidof app.vesper)
CS=$(sh pidof cameraserver)
HAL=$(sh pidof camerahalserver)
ticks() { for p in "$@"; do sh cat /proc/"$p"/stat 2>/dev/null | awk '{printf "%d ", $14 + $15}' || printf "0 "; done; }
total() { sh head -1 /proc/stat | awk '{s=0; for (i=2;i<=NF;i++) s+=$i; printf "%d %d", s, $5}'; }
read -r a0 c0 h0 <<<"$(ticks "$APP" "$CS" "$HAL")"
read -r t0 i0 <<<"$(total)"
S0=$(date +%s)
sleep "$SEC"
read -r a1 c1 h1 <<<"$(ticks "$APP" "$CS" "$HAL")"
read -r t1 i1 <<<"$(total)"
S1=$(date +%s)
dt=$((S1 - S0))
pct() { awk -v d="$1" -v s="$dt" 'BEGIN { printf "%.1f", d / s }'; }  # CLK_TCK=100 → % of one core
dev=$(awk -v t=$((t1 - t0)) -v i=$((i1 - i0)) 'BEGIN { printf "%.1f", (t > 0) ? (100 * (t - i) / t) : 0 }')
pss() { sh dumpsys meminfo "$1" 2>/dev/null | awk '/TOTAL PSS:/ {printf "%.1f MB", $3/1024; exit} /^ *TOTAL / {printf "%.1f MB", $2/1024; exit}'; }
echo "### $LABEL ($(date '+%Y-%m-%d %H:%M'), ${dt}s)"
echo "- CPU (% of one core): app $(pct $((a1 - a0))), cameraserver $(pct $((c1 - c0))), camerahalserver $(pct $((h1 - h0))) → camera total $(pct $(( (a1 - a0) + (c1 - c0) + (h1 - h0) )))"
echo "- Whole device busy: ${dev}% of 4 cores"
echo "- PSS: app $(pss app.vesper), cameraserver $(pss "$CS"), camerahalserver $(pss "$HAL")"
