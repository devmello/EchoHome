#!/bin/bash
# Hold the Echo device lock while running commands, so a multi-step interaction (install, tap,
# swipe, screenshot) isn't interleaved with another agent's.
#   ./scripts/echo-lock.sh run 'CMDS'   run CMDS (bash) with $ADB and $SERIAL set, then release
#   ./scripts/echo-lock.sh status       who holds it
set -e
. "$(dirname "$0")/lib.sh"
case ${1:-} in
  run)
    lock_acquire
    echo_connect >/dev/null
    export ADB SERIAL; SERIAL=$(serial)
    bash -c "$2"
    lock_release ;;
  status)
    if [ -d "$LOCK_DIR" ]; then echo "held by: $(cat "$LOCK_DIR/owner" 2>/dev/null)"; else echo "free"; fi ;;
  *) sed -n '2,6p' "$0"; exit 2 ;;
esac
