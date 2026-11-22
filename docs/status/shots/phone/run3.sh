#!/bin/bash
# Demo banner / call / Bluetooth media through the real ANCS session + presenter, rendered by
# agent B's banner + island overlays (temporary local merge of B's branch, not committed).
# Silent: no audio is played (quiet hours). Runs under build-install.sh --then.
S=docs/status/shots/phone
A() { $ADB -s $SERIAL "$@"; }
demo() { A shell am start -n app.vesper/.MainActivity --es screen phone-pairing --es phone_demo "$1" >/dev/null; }
A exec-out screencap -p > $S/pairing-privileged.png
A shell am start -n app.vesper/.MainActivity --es screen standby >/dev/null; sleep 2
demo banner; sleep 2.5; A exec-out screencap -p > $S/banner-messages.png
A shell input tap 480 58; sleep 1.5; A exec-out screencap -p > $S/banner-expanded.png
A shell input tap 650 410; sleep 1.5   # Dismiss -> Clear on the (fake) iPhone
demo missed; sleep 2.5; A exec-out screencap -p > $S/banner-missed-call.png
sleep 5
demo call; sleep 2.5; A exec-out screencap -p > $S/call-island.png
A shell input tap 480 60; sleep 1.5; A exec-out screencap -p > $S/call-island-tap.png
sleep 2
demo media; sleep 2.5; A exec-out screencap -p > $S/bluetooth-media-island.png
A logcat -d -s VesperPhone:* AndroidRuntime:E | tail -15
