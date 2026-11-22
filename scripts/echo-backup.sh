#!/bin/bash
# Back up every Echo partition except userdata and cache to backups/<timestamp>/ (gzip'd images),
# copied in 64 MB chunks, each verified against an on-device SHA-256 and retried on mismatch
# (wireless adb can drop or corrupt a long stream). The full image hash goes in SHA256SUMS, and
# COMPLETE is written last: scripts only trust a backup directory that has COMPLETE.
# Restore one: gunzip -c backups/<ts>/<name>.img.gz | adb shell 'dd of=/dev/block/by-name/<name>'
# (or from TWRP / amonet). Needs adb root (LineageOS: Developer options > Rooted debugging).
set -e
. "$(dirname "$0")/lib.sh"
OUT="$ECHO_ROOT/backups/$(date +%Y%m%d-%H%M%S)"
CHUNK_MB=64
lock_acquire; trap lock_release EXIT
echo_connect >/dev/null
root_echo
mkdir -p "$OUT/chunks"
PARTS=$(adb_echo shell 'for d in /sys/class/block/mmcblk0p*; do echo "$(grep PARTNAME $d/uevent | cut -d= -f2) $(cat $d/size)"; done' | tr -d '\r' | grep -v -E '^(userdata|cache) ')
echo "$PARTS" | while read -r p sectors; do
  [ -z "$p" ] && continue
  dev=/dev/block/by-name/$p
  bytes=$((sectors * 512)); chunks=$(( (bytes + CHUNK_MB*1048576 - 1) / (CHUNK_MB*1048576) ))
  : > "$OUT/$p.img.gz"
  for ((i = 0; i < chunks; i++)); do
    f="$OUT/chunks/$p.$i.gz"; ok=
    for try in 1 2 3 4; do
      echo_connect >/dev/null 2>&1 || true
      dsum=$(adb_echo shell "dd if=$dev bs=1048576 skip=$((i*CHUNK_MB)) count=$CHUNK_MB 2>/dev/null | sha256sum" | tr -d '\r' | awk '{print $1}')
      adb_echo exec-out "dd if=$dev bs=1048576 skip=$((i*CHUNK_MB)) count=$CHUNK_MB 2>/dev/null | gzip -1" > "$f" || true
      lsum=$(gunzip -c "$f" 2>/dev/null | shasum -a 256 | awk '{print $1}')
      if [ -n "$dsum" ] && [ "$dsum" = "$lsum" ]; then ok=1; break; fi
      echo "  retry $p chunk $i (try $try)"; root_echo >/dev/null 2>&1 || true
    done
    [ -n "$ok" ] || { echo "FAILED $p chunk $i"; exit 1; }
    cat "$f" >> "$OUT/$p.img.gz"; rm -f "$f"
  done
  dsum=$(adb_echo shell "sha256sum $dev" | tr -d '\r' | awk '{print $1}')
  lsum=$(gunzip -c "$OUT/$p.img.gz" | shasum -a 256 | awk '{print $1}')
  [ "$dsum" = "$lsum" ] || { echo "MISMATCH $p (device $dsum, local $lsum)"; exit 1; }
  echo "$dsum  $p.img" >> "$OUT/SHA256SUMS"
  echo "ok $p $(du -h "$OUT/$p.img.gz" | cut -f1)"
done
rmdir "$OUT/chunks"
adb_echo shell getprop ro.lineage.display.version > "$OUT/BUILD" || true
date > "$OUT/COMPLETE"
echo "Backup complete: $OUT"
