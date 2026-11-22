#!/bin/bash
# Pairing screen: demo states, then the real screen (deny the discoverable prompt), logs.
# Runs under build-install.sh --then (holds the device lock; $ADB/$SERIAL exported).
S=docs/status/shots/phone
A() { $ADB -s $SERIAL "$@"; }
A shell dumpsys window | grep -m1 mCurrentFocus
A exec-out screencap -p > $S/pairing-code.png
A shell am start -n app.vesper/.MainActivity --es screen phone-pairing --es phone_demo paired >/dev/null
sleep 2
A exec-out screencap -p > $S/pairing-paired.png
# The real screen: the system asks to make the Echo discoverable.
A shell am start -n app.vesper/.MainActivity --es screen standby >/dev/null
sleep 3
A shell am start -n app.vesper/.MainActivity --es screen phone-pairing >/dev/null
sleep 3
A exec-out screencap -p > $S/pairing-real-prompt.png
A shell dumpsys window | grep -m1 mCurrentFocus
# Deny (we don't leave the Echo discoverable during tests)
A shell input tap 640 248
sleep 3
A exec-out screencap -p > $S/pairing-real.png
A shell dumpsys window | grep -m1 mCurrentFocus
A shell am start -n app.vesper/.MainActivity --es screen standby >/dev/null
sleep 3
A logcat -d -s VesperPhone:* AndroidRuntime:E | tail -25
A shell dumpsys bluetooth_manager | grep -E "ScanMode|Bonded devices" -A1 | head -6
