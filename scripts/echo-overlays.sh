#!/bin/bash
# Build EchoHome's two static ROM overlays (device/overlays/), and with --install put them in
# /system/product/overlay and reboot. --install writes /system: ask the user first (CLAUDE.md).
#   systemui:  enable_volume_ui=false (no SystemUI volume panel flashing over the island)
#   bluetooth: avrcp_controller_enable_cover_art=true (album art from the iPhone over Bluetooth)
# Undo: ./scripts/echo-overlays.sh --remove (also reboots).
set -e
. "$(dirname "$0")/lib.sh"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)
JAR=$(ls -d "$ANDROID_HOME"/platforms/android-* | tail -1)/android.jar
OUT="$ECHO_ROOT/device/overlays/build"; mkdir -p "$OUT"
NAMES="systemui bluetooth"
for n in $NAMES; do
  src="$ECHO_ROOT/device/overlays/$n"; work=$(mktemp -d)
  "$BT/aapt2" compile --dir "$src/res" -o "$work/res.zip"
  "$BT/aapt2" link -I "$JAR" --manifest "$src/AndroidManifest.xml" --min-sdk-version 30 --target-sdk-version 30 \
    -o "$work/u.apk" "$work/res.zip"
  "$BT/zipalign" -f 4 "$work/u.apk" "$work/a.apk"
  "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$OUT/Vesper-$n.apk" "$work/a.apk"
  rm -rf "$work"
done
echo "built: $(ls "$OUT"/*.apk | xargs -n1 basename | tr '\n' ' ')"
case "${1:-}" in --install|--remove) ;; *) exit 0 ;; esac

ls "$ECHO_ROOT"/backups/*/COMPLETE >/dev/null 2>&1 || { echo "No complete partition backup in backups/."; exit 1; }
lock_acquire; trap lock_release EXIT
echo_connect >/dev/null
root_echo
adb_echo remount
adb_echo shell 'touch /system/.echo-rw && rm /system/.echo-rw' || { echo "ABORT: /system is read-only"; exit 1; }
for n in $NAMES; do
  d=/system/product/overlay/Vesper-$n
  if [ "$1" = --remove ]; then adb_echo shell "rm -rf $d"; continue; fi
  adb_echo shell "mkdir -p $d && chmod 755 $d"
  adb_echo push "$OUT/Vesper-$n.apk" "$d/Vesper-$n.apk" >/dev/null
  adb_echo shell "chmod 644 $d/Vesper-$n.apk && restorecon -R $d"
done
adb_echo shell sync
echo "rebooting..."; adb_echo reboot || true
wait_boot
adb_echo shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 || true
echo "--- verify"
adb_echo shell "cmd overlay list com.android.systemui | grep -i vesper; cmd overlay list com.android.bluetooth | grep -i vesper
  dumpsys bluetooth_manager | grep -i 'Cover Artwork'"
