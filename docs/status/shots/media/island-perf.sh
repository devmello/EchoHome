#!/bin/bash
# Island / media redraw cost with the (silent) demo media playing: Timers, StandBy and the alarm
# ringing demo, 10 s each, then demo data back off. Run via build-install.sh --then (holds the lock).
# Usage: island-perf.sh SHOT_PREFIX   (writes SHOT_PREFIX-timers.png)
A="$ADB -s $SERIAL"
P="$(cd "$(dirname "$0")" && pwd)/../../../../scripts/echo-perf.sh"
go() { $A shell am start -n app.vesper/.MainActivity "$@" >/dev/null 2>&1; }
go --ez demo true; sleep 7          # demo flag restarts the process
go --es screen timers; sleep 5
[ -n "$1" ] && $A exec-out screencap -p > "$1-timers.png"
echo "## timers";  "$P" 10 | grep -E 'PSS total|CPU|Frames|Frame time'
go --es screen standby; sleep 5
echo "## standby"; "$P" 10 | grep -E 'PSS total|CPU|Frames|Frame time'
go --es screen alarm --es demo ringing; sleep 5
echo "## alarm ringing (demo, silent)"; "$P" 10 | grep -E 'PSS total|CPU|Frames|Frame time'
go --ez demo false; sleep 7
go --es screen standby; sleep 2
echo "demo back off"
