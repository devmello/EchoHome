#!/bin/bash
# Build the app, then — holding the device lock — install it on the Echo, make it HOME, launch it,
# and optionally screenshot / measure / run more commands before releasing the lock.
#
# Usage: ./scripts/build-install.sh [options]
#   --release            build the minified release APK (default: debug)
#   --no-build           install the last build
#   --screen ID          open this screen (e.g. standby, nowplaying, home)
#   --extra 'ARGS'       more `am start` extras, e.g. --extra '--es face analog --ez night true'
#   --wait SEC           seconds to wait after launch before --shot/--perf/--then (default 3)
#   --shot PATH          save a screenshot (PNG) to PATH
#   --perf [SEC]         record RAM / frames / CPU over SEC seconds (default 10), see echo-perf.sh
#   --then 'CMD'         run CMD (bash) while still holding the lock; $ADB, $SERIAL are exported,
#                        e.g. --then '$ADB -s $SERIAL shell input swipe 800 240 100 240 300'
#
# Works from any agent worktree. Only one agent uses the Echo at a time (lock: /tmp/echo-device.lock).
set -e
. "$(dirname "$0")/lib.sh"

VARIANT=debug; BUILD=1; SCREEN=""; EXTRA=""; WAIT=3; SHOT=""; PERF=""; THEN=""
while [ $# -gt 0 ]; do
  case $1 in
    --release) VARIANT=release ;;
    --no-build) BUILD=0 ;;
    --screen) SCREEN=$2; shift ;;
    --extra) EXTRA=$2; shift ;;
    --wait) WAIT=$2; shift ;;
    --shot) SHOT=$2; shift ;;
    --perf) PERF=10; case ${2:-} in ''|-*) ;; *) PERF=$2; shift ;; esac ;;
    --then) THEN=$2; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

TASK=assembleDebug; [ "$VARIANT" = release ] && TASK=assembleRelease
APK="$CHECKOUT/android/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
if [ "$BUILD" = 1 ]; then
  # The assistant's voice libs are git-ignored: without them the APK has no voice and replaces a working one.
  if ! ls "$CHECKOUT/android/feature/assistant/src/main/jniLibs/armeabi-v7a/libsherpa-onnx-jni.so" >/dev/null 2>&1 && [ -x "$CHECKOUT/scripts/fetch-voice.sh" ]; then
    echo "voice libs missing: fetching (scripts/fetch-voice.sh --libs)"
    "$CHECKOUT/scripts/fetch-voice.sh" --libs >/dev/null || echo "warning: fetch-voice.sh --libs failed; the APK will have no voice" >&2
  fi
  echo "build: $TASK in $CHECKOUT/android"
  (cd "$CHECKOUT/android" && ./gradlew -q ":app:$TASK" 2>&1 | grep -v '^w: ' || true)
fi
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

lock_acquire
echo_connect >/dev/null
export ADB SERIAL; SERIAL=$(serial)
# -d: versionCode is the commit count (app/build.gradle.kts), so a branch behind the installed build
# is "older". Downgrades are allowed on this userdebug ROM; app data stays.
adb_echo install -r -d "$APK" >/dev/null && echo "installed $VARIANT ($(du -h "$APK" | cut -f1 | tr -d ' '))"
adb_echo shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null
# shellcheck disable=SC2086
adb_echo shell am start -W -n "$HOME_ACTIVITY" -a android.intent.action.MAIN -c android.intent.category.HOME \
  ${SCREEN:+--es screen "$SCREEN"} $EXTRA >/dev/null
echo "launched${SCREEN:+ screen=$SCREEN}${EXTRA:+ $EXTRA}"
sleep "$WAIT"
if [ -n "$SHOT" ]; then
  mkdir -p "$(dirname "$SHOT")"
  adb_echo exec-out screencap -p > "$SHOT" && echo "screenshot: $SHOT"
fi
[ -n "$PERF" ] && "$SCRIPT_DIR/echo-perf.sh" "$PERF"
[ -n "$THEN" ] && bash -c "$THEN"
lock_release
