#!/bin/bash
# Other apps' media on the Echo: plays a titled track in LineageOS's Eleven (the last row of its
# song list; uiautomator can't get an idle state in Eleven, so it's a plain tap), checks Now
# Playing mirrors it, pauses it with our own transport button and checks Eleven's session paused.
# Then checks the empty state draws nothing. Run via build-install.sh --then (holds the lock).
D=$(cd "$(dirname "$0")" && pwd)
A="$ADB -s $SERIAL"
np() { $A shell am start -n com.echobedside/.MainActivity --es screen nowplaying ${1:+--es np "$1"} >/dev/null 2>&1; }
if ! $A shell dumpsys package com.echobedside | grep -q MediaListenerService; then echo "installed build lacks MediaListenerService; abort"; exit 0; fi
$A shell cmd notification allow_listener com.echobedside/com.echobedside.feature.media.MediaListenerService
$A logcat -c
np preview-off; sleep 0.5
$A shell am start -n org.lineageos.eleven/.ui.activities.HomeActivity >/dev/null 2>&1; sleep 3
for i in 1 2 3 4 5 6 7 8 9 10; do $A shell input swipe 480 340 480 150 120; done
sleep 1.5
$A shell input tap 220 325; echo "tapped last row"; sleep 3
$A shell dumpsys media_session | tr -d '\r' | grep -E 'package=|state=PlaybackState|metadata' | head -10
np; sleep 3
$A exec-out screencap -p > "$D/other-app.png"; echo "shot other-app"
$A shell input tap 664 295; sleep 2
$A exec-out screencap -p > "$D/other-app-paused.png"; echo "shot other-app-paused"
$A shell dumpsys media_session | tr -d '\r' | grep -A6 'org.lineageos.eleven' | grep -E 'state=PlaybackState' | head -2
$A logcat -d -s EchoMedia:* | tail -8
$A shell am force-stop org.lineageos.eleven
# Empty state must draw nothing.
sleep 1; np empty; sleep 2; "$D/../../../../scripts/echo-perf.sh" 5 | grep -E 'PSS|CPU|Frame'
np demo
