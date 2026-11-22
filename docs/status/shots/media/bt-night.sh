#!/bin/bash
# Bluetooth preview source + Night mode shots, then the lead's bench screen frame stats as a
# baseline. Run via build-install.sh --then. Usage: bt-night.sh SCRATCH_DIR
D=$(cd "$(dirname "$0")" && pwd); S=$1
A="$ADB -s $SERIAL"
np() { $A shell am start -n app.vesper/.MainActivity --es screen nowplaying --es np "$1" >/dev/null 2>&1; }
shot() { $A exec-out screencap -p > "$D/$1.png"; echo "shot $1"; }
np bt; sleep 2.5; shot bluetooth
np popover; sleep 1.2; shot bluetooth-popover
$A shell input tap 120 120; sleep 0.4
np bt-off; sleep 1.5
np night; sleep 2; shot night
np day; sleep 0.5
$A shell am start -n app.vesper/.MainActivity --es screen bench >/dev/null 2>&1
sleep 2; $A shell dumpsys gfxinfo app.vesper reset >/dev/null; sleep 3
$A shell dumpsys gfxinfo app.vesper framestats > "$S/fs_bench.txt"
# Eleven: index the test tone and open its library (to find where to tap).
$A shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/testtone.mp3 >/dev/null 2>&1
$A shell am start -n org.lineageos.eleven/.ui.activities.HomeActivity >/dev/null 2>&1
sleep 4; $A exec-out screencap -p > "$S/eleven.png"
$A shell am start -n app.vesper/.MainActivity --es screen nowplaying >/dev/null 2>&1
