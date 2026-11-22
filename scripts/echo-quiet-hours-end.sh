#!/bin/bash
# End quiet hours on the Echo: undo what the lead set on Nov 22, 2026 (night) while the user slept.
#  - app ops PLAY_AUDIO back to default for the app, Clock and the music player
#  - Do Not Disturb off
#  - volumes back to sensible levels (music 6/15, alarm 5/7, ring/notification 4/7, system 3/7, assistant 6/15)
#  - touch sounds back on (Android's default)
# Scheduled once by ~/Library/LaunchAgents/com.vesper.quiet-hours-end.plist (removes itself after a successful run).
set -e
. "$(dirname "$0")/lib.sh"
lock_acquire; trap lock_release EXIT
echo_connect >/dev/null
adb_echo shell '
for p in com.echobedside com.android.deskclock org.lineageos.eleven; do cmd appops set $p PLAY_AUDIO default; done
cmd notification set_dnd off
settings put global zen_mode 0
v() { cmd media_session volume --stream $1 --set $2 >/dev/null 2>&1; }
v 3 6; v 4 5; v 2 4; v 5 4; v 1 3; v 10 6
settings put system sound_effects_enabled 1
settings put system haptic_feedback_enabled 1
echo "zen_mode=$(settings get global zen_mode) appops=$(cmd appops get com.echobedside PLAY_AUDIO)"'
# Tell agents (docs/status/README.md) that quiet hours are over.
cd "$ECHO_ROOT" && sed -i '' 's/^## Quiet hours (from the user, Nov 22, 2026, night)$/## Quiet hours (ENDED Nov 23, 2026, 10:00: sound is allowed again)/' docs/status/README.md || true
PLIST="$HOME/Library/LaunchAgents/com.vesper.quiet-hours-end.plist"
if [ -f "$PLIST" ]; then rm -f "$PLIST"; launchctl bootout "gui/$(id -u)/com.vesper.quiet-hours-end" 2>/dev/null || true; fi
echo "Quiet hours ended $(date)"
