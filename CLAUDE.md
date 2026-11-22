# Echo Bedside

Custom Liquid Glass smart-display software for a jailbroken Amazon Echo Show 5 (1st gen, "checkers") running unofficial LineageOS 18.1 (Android 11, API 30, armv7, 1 GB RAM, 960×480 screen).

Read `NOTES.md` first. It has the device facts, the design system, every screen's spec, the architecture decision (a HOME launcher app on LineageOS, not custom firmware), the feature plan and the open questions to check on the device.

- `design/`: source of every designed screen (`*.dc.html` + `canvas.json`). It's a design-tool format, not runnable app code. Treat it as the exact visual spec: sizes are device pixels at 960×480. Live canvas: https://claude.ai/artifact/2bKaYfP9npn9KME9xuMshj
- `amonet/`, `rom/`, `platform-tools/`: jailbreak, the installed ROM and adb/fastboot. Don't flash anything without backing up partitions to `backups/` first, and confirm with the user before any flash.
- Wireless debugging is enabled on the Echo. Run `./scripts/echo-connect.sh` to connect (it rediscovers the port after reboots), then use `./platform-tools/adb -s "$(cat .echo-serial)" …`. First-time pairing is `./scripts/echo-pair.sh`. Device facts are in `device-report.md` (regenerate with `./scripts/echo-probe.sh`). See NOTES.md §1.
- Keep memory and GPU cost low. Android 11 has no RenderEffect blur, so build glass from pre-blurred backgrounds (NOTES.md §5.2).
- AirPlay is audio-only (no screen mirroring). See NOTES.md §6.
- All data in the designs is sample data. "Hey Nova" is a placeholder wake word.
- Device facts (from `device-report.md`): userdebug build with `adb root` but no `su`. 32-bit armeabi-v7a only. ~336 MB RAM free. The light sensor exists. **The mic/camera button sends KEY_POWER** (needs a key-layout remap). The camera shutter is `SW_CAMERA_LENS_COVER`.
- App code is in `android/` (Kotlin, plain Views, no AndroidX). Build + install + set as HOME: `./scripts/echo-deploy.sh`. Back to the stock launcher: `./scripts/echo-restore-launcher.sh`. Build needs JDK 17 (`/opt/homebrew/opt/openjdk@17/...`), not the system JDK 27. Status: NOTES.md §8.1.
- Start with NOTES.md §8 (suggested build order). Ask the user before anything that changes /system, remounts or flashes.
