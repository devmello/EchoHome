#!/bin/bash
# The real pairing screen (no phone paired) with the speaker-first instructions. Runs under
# build-install.sh --then. Denies the system "make visible" prompt so the Echo doesn't stay discoverable.
SHOTS=$(cd "$(dirname "$0")" && pwd)
A="$ADB -s $SERIAL"
$A shell am start -n com.echobedside/.MainActivity --es screen phone-pairing >/dev/null
sleep 3
$A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
B=$($A shell cat /sdcard/ui.xml | tr '>' '\n' | grep -i 'text="Deny"' | grep -o 'bounds="[^"]*"' | head -1)
if [ -n "$B" ]; then
  set -- $(echo "$B" | grep -o '[0-9]\+')
  $A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  echo "denied the discoverable prompt"
fi
$A shell rm -f /sdcard/ui.xml
sleep 2
$A exec-out screencap -p > "$SHOTS/pairing-real.png"
$A shell am start -n com.echobedside/.MainActivity --es screen standby >/dev/null
