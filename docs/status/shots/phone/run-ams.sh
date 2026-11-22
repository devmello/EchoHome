#!/bin/bash
# AMS demo on the Echo (run through scripts/build-install.sh --then, which holds the device lock and exports $ADB $SERIAL).
# A FakeAmsPhone feeds the real AmsSession + AmsMediaSource at MTU 23; Now Playing opens; art comes from the iTunes lookup.
SHOTS=$(cd "$(dirname "$0")" && pwd)
A="$ADB -s $SERIAL"
$A logcat -c
$A shell am start -n app.vesper/.MainActivity --es screen phone-pairing --es phone_demo ams >/dev/null
sleep 6
$A exec-out screencap -p > "$SHOTS/ams-nowplaying.png"
$A logcat -d | grep -E "VesperPhone|AndroidRuntime" | tail -8
$A shell am start -n app.vesper/.MainActivity --es screen phone-pairing --es phone_demo ams-podcast >/dev/null
sleep 6
$A exec-out screencap -p > "$SHOTS/ams-podcast.png"
$A logcat -d | grep -E "VesperPhone|AndroidRuntime" | tail -4
$A shell dumpsys meminfo app.vesper | grep -E "TOTAL PSS|TOTAL:" | head -2
