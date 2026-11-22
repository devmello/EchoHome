#!/bin/bash
# Run one adb command against the Echo from any worktree, e.g.
#   ./scripts/echo-adb.sh logcat -d -t 200 | grep EchoBedside
#   ./scripts/echo-adb.sh shell input tap 480 240
# Read-only commands (logcat, dumpsys, getevent) don't need the lock. For anything that changes
# what's on screen or installed, hold the lock: ./scripts/echo-lock.sh run '<commands>'.
. "$(dirname "$0")/lib.sh"
[ -f "$ECHO_ROOT/.echo-serial" ] || echo_connect >/dev/null
adb_echo "$@"
