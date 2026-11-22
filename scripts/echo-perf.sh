#!/bin/bash
# Measure the app on the Echo over SEC seconds (default 10): PSS memory, frame stats, CPU.
# Interact with the device meanwhile (or pass a --then command to build-install.sh) to measure
# animations. Prints a Markdown block to paste into docs/status/<agent>.md.
#
# Usage: ./scripts/echo-perf.sh [SEC] [--then 'CMD run while measuring']
set -e
. "$(dirname "$0")/lib.sh"
SEC=${1:-10}; [ "${1:-}" ] && shift
DURING=""; [ "${1:-}" = --then ] && DURING=$2

lock_acquire
echo_connect >/dev/null
export ADB SERIAL; SERIAL=$(serial)
PID=$(adb_echo shell pidof $PKG | tr -d '\r')
[ -n "$PID" ] || { echo "$PKG isn't running" >&2; exit 1; }

ticks() { adb_echo shell cat /proc/"$PID"/stat | awk '{print $14 + $15}'; }
adb_echo shell dumpsys gfxinfo $PKG reset >/dev/null
T0=$(ticks); S0=$(date +%s)
[ -n "$DURING" ] && bash -c "$DURING"
REMAIN=$(( SEC - ($(date +%s) - S0) )); [ $REMAIN -gt 0 ] && sleep $REMAIN
T1=$(ticks); S1=$(date +%s)
CPU=$(awk -v d=$((T1 - T0)) -v s=$((S1 - S0)) 'BEGIN { printf "%.1f", (s > 0 ? d / s : 0) }') # CLK_TCK=100 → % of one core

MEM=$(adb_echo shell dumpsys meminfo $PKG | tr -d '\r')
GFX=$(adb_echo shell dumpsys gfxinfo $PKG | tr -d '\r')
AVAIL=$(adb_echo shell cat /proc/meminfo | awk '/MemAvailable/ {printf "%d MB", $2/1024}')
pss() { echo "$MEM" | awk -v k="$1" '$0 ~ k {for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) {printf "%.1f MB", $i/1024; exit}}'; }

echo "### Perf $(date '+%Y-%m-%d %H:%M') ($SEC s, $(adb_echo shell dumpsys package $PKG | awk -F= '/versionName/ {print $2; exit}' | tr -d '\r'), $(adb_echo shell dumpsys package $PKG | grep -q 'flags=.*DEBUGGABLE' && echo debug || echo release))"
echo "- PSS total: $(echo "$MEM" | awk '/TOTAL PSS:/ {printf "%.1f MB", $3/1024}') (Java $(pss 'Java Heap:'), Native $(pss 'Native Heap:'), Graphics $(pss 'Graphics:'))"
echo "- Device MemAvailable: $AVAIL"
echo "- CPU: ${CPU}% of one core (avg over ${SEC}s)"
echo "- Frames: $(echo "$GFX" | awk -F': ' '/Total frames rendered/ {print $2; exit}') rendered, janky $(echo "$GFX" | awk -F': ' '/Janky frames:/ {print $2; exit}')"
FRAMES=$(echo "$GFX" | awk -F': ' '/Total frames rendered/ {print $2; exit}')
if [ "${FRAMES:-0}" -gt 0 ]; then
  echo "- Frame time p50/p90/p99: $(echo "$GFX" | awk -F': ' '/^50th percentile/ {a=$2} /^90th percentile/ {b=$2} /^99th percentile/ {c=$2} END {print a"/"b"/"c}') (budget 17ms at 59 Hz)"
else
  echo "- Frame time: no frames drawn (idle)"
fi
lock_release
