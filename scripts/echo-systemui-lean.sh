#!/bin/bash
# Switch "lean SystemUI" (device/overlays/systemui-lean: SystemUI in its Android TV configuration,
# no phone status bar / navigation bar / shade / keyguard) on or off, then restart SystemUI.
# The overlay APK must already be in /system/product/overlay (./scripts/echo-overlays.sh --install).
#   ./scripts/echo-systemui-lean.sh on|off|status
# No reboot needed; the enabled state is kept across reboots (/data/system/overlays.xml).
set -e
. "$(dirname "$0")/lib.sh"
PKG=app.vesper.overlay.systemui.lean
case ${1:-} in on|off|status) ;; *) sed -n '2,6p' "$0"; exit 2 ;; esac
lock_acquire; trap lock_release EXIT
echo_connect >/dev/null
show() {
  adb_echo shell "cmd overlay list com.android.systemui | grep -i vesper
    echo SystemUI pid: \$(pidof com.android.systemui)
    dumpsys meminfo com.android.systemui | grep -E 'TOTAL PSS|TOTAL:' | head -1"
}
if [ "$1" = status ]; then show; exit 0; fi
adb_echo shell "cmd overlay list com.android.systemui | grep -q $PKG" || { echo "$PKG isn't installed: run ./scripts/echo-overlays.sh --install"; exit 1; }
if [ "$1" = on ]; then adb_echo shell cmd overlay enable --user 0 $PKG; else adb_echo shell cmd overlay disable --user 0 $PKG; fi
root_echo
old=$(adb_echo shell pidof com.android.systemui | tr -d '\r')
adb_echo shell "kill $old" || true
# SystemUI is persistent: Android starts it again on its own.
for _ in $(seq 1 30); do
  new=$(adb_echo shell pidof com.android.systemui | tr -d '\r')
  [ -n "$new" ] && [ "$new" != "$old" ] && break
  sleep 1
done
echo "SystemUI restarted (pid $old -> ${new:-none})"
sleep 8
show
adb_echo shell "logcat -d -b crash -t 200 | grep -A3 'com.android.systemui' | head -20"
