#!/bin/bash
# Slims the Echo's LineageOS without rebuilding it (NOTES §7.2, Nov 23, 2026):
#  - turns off the apps in device/slim-packages.txt (pm disable-user; reversible, /system untouched)
#  - Not SystemUI: tried Nov 23. Android still starts it while it's disabled (persistent core
#    service), and boot took 48 s instead of 27 s. Only removing it from /system would free its
#    ~60 MB, which needs its own approval and a backup.
#  - Not a full ahead-of-time compile: `speed` vs the default `speed-profile` measured the same
#    (first swipes after a start ~34 % janky either way, then ~4 %) and cost 4.7 MB more PSS.
# Usage: echo-slim.sh | --undo | --status
set -e
. "$(dirname "$0")/lib.sh"
LIST="$ECHO_ROOT/device/slim-packages.txt"
PKGS=$(sed 's/#.*//' "$LIST" | awk 'NF{print $1}')
SYSUI="com.android.systemui com.android.systemui.plugin.globalactions.wallet" # only re-enabled by --undo

lock_acquire; trap lock_release EXIT
echo_connect >/dev/null

case "${1:-}" in
  --status)
    adb_echo shell 'pm list packages -d' | sed 's/package://' | sort
    adb_echo shell 'grep MemAvailable /proc/meminfo'
    exit 0 ;;
  --undo)
    for p in $PKGS $SYSUI; do adb_echo shell "pm enable $p" >/dev/null 2>&1 && echo "on: $p" || true; done
    sleep 15 # package state is saved ~10 s after a change; a reboot before that loses it
    echo "Done. Reboot the Echo to start what was turned off."
    exit 0 ;;
esac

[ $# -eq 0 ] || { echo "unknown: $1"; exit 1; }
for p in $PKGS; do
  adb_echo shell "pm disable-user --user 0 $p" >/dev/null 2>&1 && echo "off: $p" || echo "skip: $p (not installed?)"
done
sleep 15 # let Android save the package state before anyone reboots
echo "Done. Reboot the Echo to free the memory."
