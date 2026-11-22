#!/bin/bash
# Shared helpers for the Echo scripts. Source it: . "$(dirname "$0")/lib.sh"
# Works from the main checkout and from any git worktree (agents' worktrees): device tooling
# (platform-tools/, .echo-ip, .echo-serial) always lives in the MAIN checkout.

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# This checkout (main or worktree): where android/ is built from.
CHECKOUT=$(cd "$SCRIPT_DIR/.." && pwd)
# The main checkout: holds platform-tools/ and the device state files.
ECHO_ROOT=$(cd "$(git -C "$SCRIPT_DIR" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)/.." 2>/dev/null && pwd)
[ -d "$ECHO_ROOT/platform-tools" ] || ECHO_ROOT=$CHECKOUT
ADB="$ECHO_ROOT/platform-tools/adb"
PKG=com.echobedside
HOME_ACTIVITY=$PKG/.MainActivity

export JAVA_HOME=${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}

LOCK_DIR=/tmp/echo-device.lock
LOCK_STALE_SECONDS=${LOCK_STALE_SECONDS:-1200}   # a lock older than 20 min is assumed abandoned
LOCK_WAIT_SECONDS=${LOCK_WAIT_SECONDS:-2400}     # give up waiting after 40 min

serial() { cat "$ECHO_ROOT/.echo-serial"; }
adb_echo() { "$ADB" -s "$(serial)" "$@"; }

# Connects (rediscovering the port after reboots) and refreshes .echo-serial.
echo_connect() { "$ECHO_ROOT/scripts/echo-connect.sh" </dev/null; }

# The device lock: only one agent uses the Echo at a time (PARALLEL_BUILD_PROMPT.md).
# Re-entrant within one process tree via ECHO_LOCK_HELD=1.
# adb root (userdebug) and reconnect: adbd restarts, and over wireless debugging the client drops.
root_echo() {
  [ "$(adb_echo shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && return 0
  adb_echo root >/dev/null 2>&1 || true
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 2
    echo_connect >/dev/null 2>&1 || continue
    [ "$(adb_echo shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && return 0
  done
  echo "adb root failed" >&2; return 1
}

# Wait for the Echo to come back after a reboot (wireless debugging gets a new port).
wait_boot() {
  sleep 25
  for i in $(seq 1 60); do
    if echo_connect >/dev/null 2>&1 && [ "$(adb_echo shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then return 0; fi
    sleep 3
  done
  echo "The Echo didn't come back within ~3.5 min" >&2; return 1
}

lock_acquire() {
  [ "${ECHO_LOCK_HELD:-}" = 1 ] && return 0
  local who="${ECHO_AGENT:-$(basename "$CHECKOUT")} pid $$"
  local waited=0
  until mkdir "$LOCK_DIR" 2>/dev/null; do
    local since; since=$(stat -f %m "$LOCK_DIR" 2>/dev/null || echo 0)
    if [ $(( $(date +%s) - since )) -gt "$LOCK_STALE_SECONDS" ]; then
      echo "lock: breaking stale lock held by: $(cat "$LOCK_DIR/owner" 2>/dev/null)" >&2
      rm -rf "$LOCK_DIR"; continue
    fi
    [ $((waited % 30)) -eq 0 ] && echo "lock: Echo busy ($(cat "$LOCK_DIR/owner" 2>/dev/null)), waiting..." >&2
    sleep 5; waited=$((waited + 5))
    if [ "$waited" -ge "$LOCK_WAIT_SECONDS" ]; then echo "lock: gave up after ${waited}s" >&2; return 1; fi
  done
  echo "$who since $(date '+%H:%M:%S')" > "$LOCK_DIR/owner"
  export ECHO_LOCK_HELD=1
  ECHO_LOCK_MINE=1
  trap lock_release EXIT INT TERM
}

lock_release() {
  if [ "${ECHO_LOCK_MINE:-}" = 1 ]; then
    rm -rf "$LOCK_DIR"
    ECHO_LOCK_MINE=0
    unset ECHO_LOCK_HELD
  fi
}
