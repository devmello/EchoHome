#!/bin/bash
# Collect facts about the Echo into device-report.md (answers the open questions in NOTES.md §7).
# Usage: ./scripts/echo-probe.sh
cd "$(dirname "$0")/.." || exit 1
ADB=./platform-tools/adb
./scripts/echo-connect.sh || exit 1
S=$(cat .echo-serial)
sh_() { "$ADB" -s "$S" shell "$@" 2>&1 | tr -d '\r'; }
OUT=device-report.md
section() { printf '\n## %s\n```\n' "$1" >> "$OUT"; }
endsec() { printf '```\n' >> "$OUT"; }

echo "# Echo device report ($(date '+%Y-%m-%d %H:%M'))" > "$OUT"
echo "Serial: $S" >> "$OUT"

section "Build"
for p in ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.type ro.lineage.version ro.lineage.build.version ro.product.cpu.abi ro.product.cpu.abilist ro.hardware ro.board.platform ro.sf.lcd_density; do
  printf '%-28s %s\n' "$p" "$(sh_ getprop $p)" >> "$OUT"
done; endsec

section "Root"
sh_ 'which su; su -c id' >> "$OUT"; endsec

section "Display"
sh_ 'wm size; wm density; dumpsys SurfaceFlinger | grep -iE "GLES|refresh" | head -5' >> "$OUT"; endsec

section "CPU / RAM / storage"
sh_ 'grep -E "Hardware|processor" /proc/cpuinfo | sort | uniq -c | head; head -3 /proc/meminfo; df -h /data /system 2>/dev/null' >> "$OUT"; endsec

section "Network"
sh_ 'ip -4 addr show wlan0 | grep inet; getprop service.adb.tcp.port' >> "$OUT"; endsec

section "Sensors (looking for a light sensor)"
sh_ 'dumpsys sensorservice | sed -n "/Sensor List/,/^$/p" | head -40' >> "$OUT"; endsec

section "Cameras"
sh_ 'dumpsys media.camera | grep -iE "Number of camera|Camera [0-9]|Facing|API" | head -15' >> "$OUT"; endsec

section "Input devices and keys (looking for the mic/camera button)"
sh_ 'getevent -lp' >> "$OUT"; endsec

section "Audio"
sh_ 'dumpsys audio | grep -iE "mic mute|microphone|devices:" | head -15' >> "$OUT"; endsec

section "Current home app and third-party apps"
sh_ 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; pm list packages -3' >> "$OUT"; endsec

echo
echo "Now press the MIC/CAMERA button on top of the Echo a few times (you have 10 seconds)..."
section "Live key events while pressing the mic/camera button"
sh_ 'timeout 10 getevent -l' >> "$OUT"; endsec
echo "Then press VOLUME UP and VOLUME DOWN once each (10 seconds)..."
section "Live key events while pressing volume up/down"
sh_ 'timeout 10 getevent -l' >> "$OUT"; endsec

echo
echo "Wrote $OUT"
