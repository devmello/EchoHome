#!/bin/bash
# Undo scripts/echo-system-install.sh: remove the priv-app, allow-list and key layout from /system,
# re-enable the Camera app and the lock screen, reboot. The app stays installed as a normal app
# if an update is in /data. (Full rollback: restore the system image from backups/.)
set -e
. "$(dirname "$0")/lib.sh"
lock_acquire; trap lock_release EXIT
echo_connect >/dev/null
root_echo
adb_echo remount
adb_echo shell 'rm -rf /system/priv-app/Vesper /system/etc/permissions/privapp-permissions-app.vesper.xml /system/etc/permissions/privapp-permissions-com.echobedside.xml /system/usr/keylayout/gating.kl /system/etc/init/echohome.rc; sync
  pm enable com.android.camera2; locksettings set-disabled false'
adb_echo reboot || true
wait_boot
echo "Undone."
