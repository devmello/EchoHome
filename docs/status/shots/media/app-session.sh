#!/bin/bash
# Other apps' media on the Echo: plays a generated test tone (sine + gradient cover, ID3 tags) in
# LineageOS's Eleven through its library (so it has a MediaSession), then checks Now Playing
# mirrors it and that our controls reach it. Run via build-install.sh --then (holds the lock).
# Usage: app-session.sh /path/to/testtone.mp3
D=$(cd "$(dirname "$0")" && pwd)
A="$ADB -s $SERIAL"
np() { $A shell am start -n com.echobedside/.MainActivity --es screen nowplaying ${1:+--es np "$1"} >/dev/null 2>&1; }
$A shell cmd notification allow_listener com.echobedside/com.echobedside.feature.media.MediaListenerService
$A push "$1" /sdcard/Music/testtone.mp3 >/dev/null
$A shell pm grant org.lineageos.eleven android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
$A shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/testtone.mp3 >/dev/null 2>&1
$A shell am start -n org.lineageos.eleven/.ui.activities.HomeActivity >/dev/null 2>&1; sleep 5
for i in 1 2 3 4 5 6 7 8; do
  $A shell rm -f /sdcard/ui.xml; sleep 1; $A shell uiautomator dump /sdcard/ui.xml 2>&1 | tr -d "\r" | grep -v dumped
  B=$($A shell cat /sdcard/ui.xml | tr '>' '\n' | grep 'text="Test Tone"' | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | head -1)
  [ -n "$B" ] && break
  $A shell input swipe 480 330 480 170 250; sleep 0.6
done
if [ -z "$B" ]; then echo "Test Tone not found in Eleven"; else
  set -- $B; $A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); echo "tapped Test Tone"
fi
sleep 3
$A shell dumpsys media_session | tr -d '\r' | grep -E 'package=|state=PlaybackState' | head -8
np; sleep 3
$A exec-out screencap -p > "$D/other-app.png"; echo "shot other-app"
np paused; sleep 2
$A exec-out screencap -p > "$D/other-app-paused.png"; echo "shot other-app-paused"
$A shell dumpsys media_session | tr -d '\r' | grep -A3 'org.lineageos.eleven' | grep -E 'state=PlaybackState' | head -2
$A logcat -d -s EchoMedia:* | tail -6
$A shell am force-stop org.lineageos.eleven
# Empty state must draw nothing.
np empty; sleep 2; "$D/../../../../scripts/echo-perf.sh" 5 | grep -E 'PSS|CPU|Frame'
np demo
