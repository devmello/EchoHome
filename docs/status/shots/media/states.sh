#!/bin/bash
# Captures the Now Playing states on the Echo (run via build-install.sh --then, which holds the lock).
# Expects $ADB, $SERIAL. Writes into docs/status/shots/media/.
D=$(cd "$(dirname "$0")" && pwd)
np() { $ADB -s $SERIAL shell am start -n app.vesper/.MainActivity --es screen nowplaying --es np "$1" >/dev/null; }
shot() { $ADB -s $SERIAL exec-out screencap -p > "$D/$1.png"; echo "shot $1"; }
# Lift-into-glass: hold the play button (a swipe that doesn't move), shoot mid-hold.
$ADB -s $SERIAL shell input swipe 664 296 664 296 1800 & sleep 0.9; shot pressed; wait; sleep 0.5
np popover; sleep 1.2; shot popover
$ADB -s $SERIAL shell input tap 120 120; sleep 0.5
np paused; sleep 1.5; shot paused
np playing; np next; sleep 2; shot playing-track2
np next; sleep 2; shot playing-track3
np next; sleep 0.5
np empty; sleep 1; shot empty
np demo; sleep 1.5
