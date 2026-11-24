#!/bin/bash
# One-time Echo system setup (user-approved Nov 22, 2026; NOTES §5.1, §5.3). Reboots the Echo.
#  1. Vesper as a privileged system app: /system/priv-app/Vesper + its privapp allow-list.
#  2. The mic button key layout: /system/usr/keylayout/gating.kl (KEY_POWER → KEYCODE_MUTE), and
#     /system/etc/init/echohome.rc, which lets the app cut the mics in hardware (red LED).
#  3. Built-in Camera app disabled (opening the shutter launched it over the launcher).
#  4. Lock screen disabled.
# Needs a partition backup in backups/ first (./scripts/echo-backup.sh). Undo: ./scripts/echo-system-undo.sh
# Re-run (--no-build to reuse the APK) after adding a privileged permission or to refresh the /system copy.
set -e
. "$(dirname "$0")/lib.sh"
ls "$ECHO_ROOT"/backups/*/COMPLETE >/dev/null 2>&1 || { echo "No complete partition backup in backups/. Run ./scripts/echo-backup.sh first."; exit 1; }
# The /system copy is the fallback under every update: versionCode 1, so any update outranks it
# (at boot Android drops an update whose versionCode is below the /system copy's).
[ "${1:-}" = --no-build ] || (cd "$CHECKOUT/android" && ./gradlew -q :app:assembleRelease -PvesperVersionCode=1 -PvesperVersionName=0.2-system)
APK="$CHECKOUT/android/app/build/outputs/apk/release/app-release.apk"
# The device files of the checkout being installed (a worktree's own, when run from one).
XML="$CHECKOUT/device/privapp-permissions-app.vesper.xml"
KL="$CHECKOUT/device/gating.kl"
RC="$CHECKOUT/device/echohome.rc"
AAPT2=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)/aapt2
VC=$("$AAPT2" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
[ "$VC" = 1 ] || { echo "ABORT: the /system APK must have versionCode 1 (it has ${VC:-none}); run without --no-build"; exit 1; }

lock_acquire; trap lock_release EXIT
echo_connect >/dev/null

# The /system APK must not request a privileged permission missing from the allow-list (boot loop).
LEVELS=$(adb_echo shell pm list permissions -f | tr -d '\r' | awk '/^\+ permission:/{p=substr($2,12)} /protectionLevel:/{print p, $1}')
for perm in $("$AAPT2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p"); do
  if echo "$LEVELS" | grep -q "^$perm .*privileged" && ! grep -q "\"$perm\"" "$XML"; then
    echo "ABORT: $perm is privileged and not in $XML"; exit 1
  fi
done

root_echo
adb_echo remount
if ! adb_echo shell 'touch /system/.echo-rw && rm /system/.echo-rw'; then
  echo "ABORT: /system is still read-only after remount (it may need 'adb disable-verity' + a reboot; ask the user)."; exit 1
fi
# The pre-rename allow-list (package com.echobedside) goes; the APK dir name stays Vesper.
adb_echo shell 'rm -f /system/etc/permissions/privapp-permissions-com.echobedside.xml'
adb_echo shell 'mkdir -p /system/priv-app/Vesper && chmod 755 /system/priv-app/Vesper'
adb_echo push "$APK" /system/priv-app/Vesper/Vesper.apk >/dev/null
adb_echo push "$XML" /system/etc/permissions/privapp-permissions-app.vesper.xml >/dev/null
adb_echo push "$KL" /system/usr/keylayout/gating.kl >/dev/null
adb_echo push "$RC" /system/etc/init/echohome.rc >/dev/null
adb_echo shell 'chmod 644 /system/priv-app/Vesper/Vesper.apk /system/etc/permissions/privapp-permissions-app.vesper.xml /system/usr/keylayout/gating.kl /system/etc/init/echohome.rc
  restorecon -R /system/priv-app/Vesper /system/etc/permissions/privapp-permissions-app.vesper.xml /system/usr/keylayout/gating.kl /system/etc/init/echohome.rc
  sync'
adb_echo shell 'pm disable-user --user 0 com.android.camera2; locksettings set-disabled true'
echo "Installed; rebooting..."
adb_echo reboot || true
wait_boot
adb_echo shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 || true
# The "EchoHome" pill over other apps when there's no navigation bar (lean SystemUI, HomeButton.kt).
adb_echo shell cmd appops set app.vesper SYSTEM_ALERT_WINDOW allow || true
echo "--- verify"
adb_echo shell "dumpsys package app.vesper | grep -E 'codePath|pkgFlags|privateFlags' | head -6
  dumpsys package app.vesper | grep INSTALL_PACKAGES | sort -u
  dumpsys input | grep -A3 ': gating' | grep -i KeyLayoutFile
  ls -l /sys/devices/platform/amazon-gating/enable
  pm list packages -d | grep camera2
  locksettings get-disabled"
