#!/bin/bash
# Runs under build-install.sh --then (device lock held; $ADB/$SERIAL exported).
S=docs/status/shots/phone
A() { $ADB -s $SERIAL "$@"; }
focus() { A shell dumpsys window | grep -m1 mCurrentFocus; }
bt_ticks() { local p; p=$(A shell pidof com.android.bluetooth | tr -d '\r'); A shell cat /proc/$p/stat | awk '{print $14 + $15}'; }
app_ticks() { local p; p=$(A shell pidof com.echobedside | tr -d '\r'); A shell cat /proc/$p/stat | awk '{print $14 + $15}'; }

A exec-out screencap -p > $S/pairing-code.png
A shell am start -n com.echobedside/.MainActivity --es screen phone-pairing --es phone_demo paired >/dev/null; sleep 2
A exec-out screencap -p > $S/pairing-paired.png

# Idle on StandBy (no phone saved: no advertising, only broadcast receivers + the AVRCP browser binding)
A shell am start -n com.echobedside/.MainActivity --es screen standby >/dev/null; sleep 4
a0=$(app_ticks); b0=$(bt_ticks); sleep 30; a1=$(app_ticks); b1=$(bt_ticks)
echo "idle standby 30s: app ticks $((a1-a0)) ($(awk -v d=$((a1-a0)) 'BEGIN{printf "%.2f", d/30}')% of a core), bluetooth process ticks $((b1-b0)) ($(awk -v d=$((b1-b0)) 'BEGIN{printf "%.2f", d/30}')%)"

# The real pairing screen: the system asks to make the Echo discoverable.
A shell am start -n com.echobedside/.MainActivity --es screen phone-pairing >/dev/null; sleep 3
A exec-out screencap -p > $S/pairing-real-prompt.png
focus
A shell input tap 640 248   # Deny: don't leave the Echo discoverable
sleep 3
focus
A exec-out screencap -p > $S/pairing-real.png
# Pairing screen open, advertising at the BALANCED interval
a0=$(app_ticks); b0=$(bt_ticks); sleep 30; a1=$(app_ticks); b1=$(bt_ticks)
echo "pairing screen advertising 30s: app ticks $((a1-a0)) ($(awk -v d=$((a1-a0)) 'BEGIN{printf "%.2f", d/30}')%), bluetooth process ticks $((b1-b0)) ($(awk -v d=$((b1-b0)) 'BEGIN{printf "%.2f", d/30}')%)"
A shell dumpsys bluetooth_manager | grep -E "ScanMode" | head -2
A shell am start -n com.echobedside/.MainActivity --es screen standby >/dev/null; sleep 4
A logcat -d -s EchoPhone:* AndroidRuntime:E | tail -20
A shell dumpsys meminfo com.echobedside | grep -E "TOTAL PSS|TOTAL:" | head -2
