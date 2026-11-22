#!/bin/bash
# Contracts round 1 shots (route chips, AirPlay 2 without skip, Bluetooth route) and Eleven's
# song list scrolled to the end. Run via build-install.sh --then. Usage: final.sh SCRATCH_DIR
D=$(cd "$(dirname "$0")" && pwd); S=$1
A="$ADB -s $SERIAL"
np() { $A shell am start -n app.vesper/.MainActivity --es screen nowplaying ${1:+--es np "$1"} >/dev/null 2>&1; }
shot() { $A exec-out screencap -p > "$D/$1.png"; echo "shot $1"; }
np popover; sleep 1.2; shot popover
$A shell input tap 120 120; sleep 0.4
np ap2; sleep 2.5; shot airplay2-noskip
np bt; sleep 2.5; np popover; sleep 1.2; shot bluetooth-popover
$A shell input tap 120 120; sleep 0.4
np preview-off; sleep 1
$A shell am start -n org.lineageos.eleven/.ui.activities.HomeActivity >/dev/null 2>&1; sleep 3
for i in 1 2 3 4 5 6 7 8 9 10; do $A shell input swipe 480 340 480 150 120; done
sleep 1.5; $A exec-out screencap -p > "$S/eleven-end.png"
np
