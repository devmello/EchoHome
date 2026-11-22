#!/bin/bash
# Frame stats of Now Playing with view groups hidden (debug arg np=perf:<flags>; a ambient, r art,
# g glass, t text/transport). Run via build-install.sh --then. Usage: gpu-profile.sh OUTDIR
OUT=$1; mkdir -p "$OUT"
A="$ADB -s $SERIAL"
for f in none a r g t ar arg argt; do
  $A shell am start -n com.echobedside/.MainActivity --es screen nowplaying --es np "perf:$f" >/dev/null 2>&1
  sleep 1; $A shell dumpsys gfxinfo com.echobedside reset >/dev/null; sleep 2.5
  $A shell dumpsys gfxinfo com.echobedside framestats > "$OUT/fs_$f.txt"
done
$A shell am start -n com.echobedside/.MainActivity --es screen nowplaying --es np "perf:" >/dev/null 2>&1
echo profiled
