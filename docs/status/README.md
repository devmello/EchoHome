# Agent status files

One file per agent: `clock.md`, `system.md`, `airplay.md`, `spotify.md`, `media.md`, `alarm.md`, `home.md`, `assistant.md`, `camera.md`, `photos.md`, `lists.md`, plus the lead's `design-review.md` in Phase 2.

Sections: **Done**, **Left**, **Measured on the Echo** (paste `scripts/echo-perf.sh` output: PSS, CPU, frames, with what was running), **Screenshots** (paths under `docs/status/shots/<agent>/`, compared with which design), **Open questions**, **Needs from the user** (secrets, approvals for /system changes, etc.).

## Quiet hours (ENDED Nov 22, 2026, 10:00: sound is allowed again)
The user is asleep next to the Echo. **No sound and no alarms on the device until Nov 22, 2026, 10:00** (`scripts/echo-quiet-hours-end.sh` lifts it automatically then):
- Don't play audio (AirPlay/Bluetooth streams, alarm or timer sounds, TTS, test tones).
- Don't schedule alarms or timers on the device. Test ringing in JVM tests only.
- Don't change volume, Do Not Disturb (it's on total silence) or app ops (`PLAY_AUDIO` is denied for app.vesper, com.android.deskclock, org.lineageos.eleven).
- Screenshots and silent UI checks are fine.
