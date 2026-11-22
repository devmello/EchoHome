#!/bin/bash
# Back up every Echo partition except userdata and cache to backups/<timestamp>/ (gzip'd images),
# verified against on-device SHA-256. Run before any /system change (CLAUDE.md). Needs adb root.
# Restore one: gunzip -c backups/<ts>/<name>.img.gz | adb shell 'dd of=/dev/block/by-name/<name>'
# (or from TWRP / amonet). Takes ~10 min over Wi-Fi, holding the device lock.
set -e
. "$(dirname "$0")/lib.sh"
OUT="$ECHO_ROOT/backups/$(date +%Y%m%d-%H%M%S)"
lock_acquire; trap lock_release EXIT
echo_connect >/dev/null
root_echo
mkdir -p "$OUT"
PARTS=$(adb_echo shell 'for d in /sys/class/block/mmcblk0p*; do grep PARTNAME $d/uevent | cut -d= -f2; done' | tr -d '\r' | grep -v -E '^(userdata|cache)$')
for p in $PARTS; do
  dev=/dev/block/by-name/$p
  adb_echo exec-out "dd if=$dev bs=1048576 2>/dev/null | gzip -1" > "$OUT/$p.img.gz"
  dsum=$(adb_echo shell "sha256sum $dev" | tr -d '\r' | awk '{print $1}')
  lsum=$(gunzip -c "$OUT/$p.img.gz" | shasum -a 256 | awk '{print $1}')
  if [ "$dsum" != "$lsum" ]; then echo "MISMATCH $p (device $dsum, local $lsum)"; exit 1; fi
  echo "$dsum  $p.img" >> "$OUT/SHA256SUMS"
  echo "ok $p $(du -h "$OUT/$p.img.gz" | cut -f1)"
done
adb_echo shell getprop ro.lineage.display.version > "$OUT/BUILD" || true
echo "Backup complete: $OUT"
