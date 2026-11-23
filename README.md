# EchoHome

A Liquid Glass smart-display interface for a jailbroken **Amazon Echo Show 5 (1st gen)**. EchoHome is an Android HOME launcher that replaces the stock Echo software with clock faces, an AirPlay and Bluetooth speaker, an on-device voice assistant, glanceable weather and calendar, and a Dynamic-Island-style live surface. It runs fully on the device and doesn't need Home Assistant or a cloud account.

> **EchoHome is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by Amazon or Apple.** "Echo" in the name only says which hardware it runs on. Amazon, Echo and Echo Show are trademarks of Amazon.com, Inc. or its affiliates. Apple, AirPlay, iPhone, Dynamic Island, StandBy and Liquid Glass are trademarks of Apple Inc. These names are used only to describe the hardware EchoHome runs on and the protocols it works with.

## What it runs on

- Echo Show 5, 1st generation (codename `checkers`): MediaTek MT8163, 1 GB RAM, 960×480 screen.
- An unofficial **LineageOS 18.1** (Android 11, API 30, armeabi-v7a) build, installed after unlocking the device with the public amonet exploit.

**This repository doesn't include the jailbreak, the ROM or Android platform tools,** and it doesn't explain how to unlock the device. You need an Echo Show 5 that already runs LineageOS 18.1 with adb access. Unlocking can brick the device, so it's at your own risk.

## Features

- **Clock faces:** Digital, Analog, World (live day/night map), Solar, Float and Liquid Glass. A long-press edit mode picks faces and colours. Night mode turns any face dim red.
- **StandBy and Widgets pages:** two glass stacks you swipe through: clock, weather, calendar, next alarm, timers, Now Playing, air quality and UV, sun and moon.
- **Echo Island:** a live surface for timers, music, AirPlay, calls, rain alerts and the leave-now time. Volume, brightness and mic/camera messages, plus notification banners, grow out of the island.
- **AirPlay receiver (audio only):** AirPlay 1 by default, with play/pause/skip back to the iPhone. AirPlay 2 is an option. The sender's volume sets the Echo's speaker.
- **Bluetooth speaker:** track info, cover art and controls on Now Playing and the island.
- **Now Playing:** ambient colour from the artwork, seek and volume. Optional live lyrics from LRCLIB.
- **iPhone notifications and calls** over Bluetooth LE (ANCS): glass banners with actions, incoming calls on the island, iPhone battery.
- **Voice assistant:** wake word, speech-to-text and text-to-speech run on the device (sherpa-onnx). Answers and tool calls (alarms, timers, weather, Do Not Disturb, screens, notes and more) come from Claude through the Anthropic API, so this part needs your own API key. Tap-to-talk works too.
- **Reminders and lists:** "remind me to take out the trash at 7pm", "in 20 minutes", "tomorrow morning". Reminders chime (quietly in Night, not in Do Not Disturb), show on the island and as a banner with Done and Snooze, and stay as missed if nobody answers. They survive a reboot. A shopping list (and any other list) by voice, with de-duplication. A Lists screen, and both are editable on the settings web page.
- **Alarms:** sunrise ramp, snooze, leave-by alarms (earlier when the first event with a place needs it) and skipping holidays. A morning brief screen.
- **Glances:** weather, rain soon, air quality, UV and pollen (Open-Meteo), calendar (iCal URLs), travel times (OpenStreetMap Nominatim and OSRM), photo frame (Immich). None of these need an API key.
- **Profiles by face:** an on-device camera pipeline (BlazeFace detection, EdgeFace embeddings) recognises who is in the room and shows their alarm, calendar and colour. The screen turns off when the room is empty. Face templates stay on the device.
- **Control Center:** pull down from the top edge for Wi-Fi, Bluetooth, AirPlay, brightness, volume, Do Not Disturb, Night mode, mic and camera.
- **The mic button mutes the microphone**, and the camera shutter switch is detected.
- **Settings web page** on port 8080 of your local network, with a phone remote and diagnostics.
- **Optional Home Assistant** screen for lights, climate, scenes and locks when you configure it.

Location for weather and travel: manual coordinates if you set them, otherwise keyless IP geolocation, otherwise San Francisco.

## Repository layout

| Path | What |
|---|---|
| `android/` | The app: Kotlin, plain Views, no AndroidX, multi-module (`:app`, `:core:*`, `:feature:*`). Start with [`docs/architecture.md`](docs/architecture.md). |
| `design/` | Source of every designed screen (`*.dc.html`). It's the visual spec in device pixels at 960×480, not runnable code. All data in the designs is sample data. |
| `device/overlays/` | Optional ROM resource overlays: turn off the SystemUI volume panel and turn on Bluetooth cover art. |
| `scripts/` | Build, install and device helper scripts. |
| `docs/` | Architecture, per-feature status notes and assistant setup. |
| `NOTES.md` | Device facts, the design system, every screen's spec and the project's decisions. |

## Building

You need JDK 17 (newer JDKs are too new for this Gradle setup), the Android SDK (platform android-35, build-tools 35) and an Echo reachable over adb (wireless debugging).

```bash
./scripts/echo-pair.sh          # once: pair adb with the Echo
./scripts/echo-connect.sh       # connect (finds the port again after reboots)
./scripts/build-install.sh      # debug build, install, set as HOME, launch
./scripts/echo-deploy.sh        # release build
./scripts/echo-restore-launcher.sh   # back to the stock launcher
```

- **Voice models and libraries** aren't in the repository. `./scripts/fetch-voice.sh --push` downloads them from the sherpa-onnx releases (checked against pinned SHA-256 sums) and pushes them to the Echo.
- **The AirPlay library** is Rust, built for armv7 with `android/feature/airplay/rust/build.sh` (needs the Android NDK, the `armv7-linux-androideabi` Rust target and `cargo-ndk`).
- **The Anthropic API key** goes in the settings page's secret field (stored on the device), or at build time in `android/secrets.properties`, which git ignores. See `android/secrets.properties.example` and [`docs/assistant-setup.md`](docs/assistant-setup.md).
- Some features need the app installed in `/system/priv-app` (`scripts/echo-system-install.sh`) because of privileged permissions. That script and `scripts/echo-overlays.sh` remount `/system`, so back up your partitions first.

## License

EchoHome is licensed under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE).

### Third-party components

| Component | Licence | Notes |
|---|---|---|
| [shairplay](https://crates.io/crates/shairplay) 0.13.0 (vendored in `android/feature/airplay/rust/vendor/shairplay`) | LGPL-3.0-or-later | One small EchoHome patch, described in `VESPER-PATCH.md`. Upstream notes that its FairPlay code has uncertain provenance; see its `docs/fairplay-provenance.md`. |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) libraries, keyword-spotting and speech-to-text models | Apache-2.0 | Downloaded by `fetch-voice.sh`, not in the repository. |
| Piper voice `en_US-amy-low` (sherpa-onnx package) | See the voice's `MODEL_CARD` | Downloaded by `fetch-voice.sh`, not in the repository. |
| [MediaPipe](https://github.com/google-ai-edge/mediapipe) BlazeFace short-range model | Apache-2.0 | In `android/feature/camera/src/main/assets/models/`. |
| [EdgeFace](https://github.com/otroshi/edgeface) XXS and XS weights (converted to TFLite) | Code BSD-3-Clause; **weights trained on WebFace4M/12M, licensed for non-commercial research only** | EchoHome is non-commercial. If you use EchoHome commercially, replace these weights. Details in `android/feature/camera/MODELS.md`. |
| [TensorFlow Lite](https://www.tensorflow.org/lite) | Apache-2.0 | |
| [OkHttp](https://square.github.io/okhttp/) and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | |
| [Inter](https://rsms.me/inter/) and [Nunito](https://github.com/googlefonts/nunito) fonts | SIL Open Font License 1.1 | The licence texts are next to the font files. |

Online services the app calls directly, keyless unless noted: Open-Meteo, LRCLIB, OpenStreetMap Nominatim, OSRM, an IP-geolocation service, and the Anthropic API (your own key). Their terms apply to your use.
