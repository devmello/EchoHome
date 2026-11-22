#!/bin/bash
# Build the Echo Bedside app, install it on the Echo and make it the HOME app.
# Usage: ./scripts/echo-deploy.sh [debug|release]   (default: release, minified, signed with the debug key)
set -e
cd "$(dirname "$0")/.." || exit 1
VARIANT=${1:-release}
export JAVA_HOME=${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
ADB=./platform-tools/adb
HOME_ACTIVITY=com.echobedside/.MainActivity

./scripts/echo-connect.sh </dev/null
SERIAL=$(cat .echo-serial)

case $VARIANT in debug) TASK=assembleDebug ;; *) VARIANT=release; TASK=assembleRelease ;; esac
(cd android && ./gradlew -q ":app:$TASK")
APK=android/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk
"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" shell cmd package set-home-activity "$HOME_ACTIVITY"
"$ADB" -s "$SERIAL" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Installed $VARIANT and set as HOME: $HOME_ACTIVITY"
