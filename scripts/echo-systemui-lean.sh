#!/bin/bash
# Switch "lean SystemUI" on or off, then reboot. Lean = device/overlays/systemui-lean (SystemUI in
# its Android TV configuration: no phone status bar / navigation bar / shade / keyguard) PLUS
# SystemUI's KeyguardService turned off.
#
# Why KeyguardService too: the window manager binds it at boot and tells it about every wake-up;
# without the phone StatusBar its KeyguardViewMediator crashes SystemUI (NPE in
# StatusBarKeyguardViewManager.onStartedWakingUp). When the bind fails, Android's
# KeyguardServiceDelegate takes its "keyguard-less device" path (deviceHasKeyguard=false: no
# lock screen, no wait for a keyguard draw at screen-on). That's decided at boot, and a keyguard
# service lost at run time makes Android assume the lock screen is up, so this always reboots.
#
# The overlay APK must already be in /system/product/overlay (./scripts/echo-overlays.sh --install).
#   ./scripts/echo-systemui-lean.sh on|off|status
set -e
. "$(dirname "$0")/lib.sh"
PKG=app.vesper.overlay.systemui.lean
KG=com.android.systemui/com.android.systemui.keyguard.KeyguardService
case ${1:-} in on|off|status) ;; *) sed -n '2,14p' "$0"; exit 2 ;; esac
lock_acquire; trap lock_release EXIT
echo_connect >/dev/null
show() {
  adb_echo shell "cmd overlay list com.android.systemui | grep -i vesper
    echo KeyguardService: \$(pm dump com.android.systemui | grep -A30 'disabledComponents:' | grep -c keyguard.KeyguardService) disabled
    dumpsys window policy | grep -iE 'mKeyguardDelegate|showing=|deviceHasKeyguard' | head -4
    echo SystemUI pid: \$(pidof com.android.systemui)
    dumpsys meminfo com.android.systemui | grep -E 'TOTAL PSS' | head -1"
  adb_echo shell "logcat -d -b crash | grep -c 'Process: com.android.systemui'" | sed 's/^/SystemUI crashes since boot: /'
}
if [ "$1" = status ]; then show; exit 0; fi
adb_echo shell "cmd overlay list com.android.systemui | grep -q $PKG" || { echo "$PKG isn't installed: run ./scripts/echo-overlays.sh --install"; exit 1; }
root_echo
if [ "$1" = on ]; then
  # If SystemUI crash-loops anyway, RescueParty would end in a recovery prompt (no Wi-Fi, needs
  # someone at the Echo). With it off, a crash loop leaves adb reachable to switch back.
  adb_echo shell setprop persist.sys.disable_rescue true
  adb_echo shell "pm disable $KG && cmd overlay enable --user 0 $PKG"
  adb_echo shell cmd appops set app.vesper SYSTEM_ALERT_WINDOW allow || true
else
  adb_echo shell "cmd overlay disable --user 0 $PKG; pm enable $KG"
fi
# Package and overlay state are written to disk a few seconds later; a reboot before that loses them.
sleep 15
adb_echo shell sync
echo "rebooting..."; adb_echo reboot || true
wait_boot
adb_echo shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 || true
sleep 20
show
