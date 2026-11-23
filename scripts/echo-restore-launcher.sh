#!/bin/bash
# Make the stock Launcher3 the HOME app again (EchoHome stays installed).
cd "$(dirname "$0")/.." || exit 1
./scripts/echo-connect.sh </dev/null || exit 1
ADB="./platform-tools/adb -s $(cat .echo-serial)"
# echo-slim.sh may have turned Launcher3 (and SystemUI) off
$ADB shell pm enable com.android.launcher3 >/dev/null
$ADB shell pm enable com.android.systemui >/dev/null
$ADB shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher
$ADB shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "HOME is Launcher3 again."
