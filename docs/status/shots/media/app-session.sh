#!/bin/bash
# Other apps' media on the Echo: plays a generated test tone (sine + gradient cover, ID3 tags) in
# LineageOS's Eleven, then shows Now Playing. Run via build-install.sh --then (holds the lock).
# Usage: app-session.sh /path/to/testtone.mp3
D=$(cd "$(dirname "$0")" && pwd)
A="$ADB -s $SERIAL"
$A shell cmd notification allow_listener com.echobedside/com.echobedside.feature.media.MediaListenerService
$A push "$1" /sdcard/Music/testtone.mp3 >/dev/null
$A shell pm grant org.lineageos.eleven android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
$A shell am start -a android.intent.action.VIEW -d file:///sdcard/Music/testtone.mp3 -t audio/mpeg >/dev/null
sleep 4
$A shell dumpsys media_session | grep -E 'package=|state=PlaybackState|metadata:' | head -12
$A shell am start -n com.echobedside/.MainActivity --es screen nowplaying >/dev/null
sleep 3
$A exec-out screencap -p > "$D/other-app.png"; echo "shot other-app"
$A logcat -d -s EchoMedia:* | tail -8
