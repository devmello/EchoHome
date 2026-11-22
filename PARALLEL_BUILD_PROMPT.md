# Prompt: build the whole Echo Bedside app with parallel agents

Use this after milestones 0 and 1 work (the Android project exists and the Digital clock runs as the home screen on the Echo). Paste everything below the line into Claude Code, opened in `~/Projects/echo-root`.

---

You are the **lead engineer** for Echo Bedside. Build the complete app described in `NOTES.md`, using parallel sub-agents for the independent parts. Read `CLAUDE.md`, `NOTES.md` (all of it), `device-report.md` and the screen specs in `design/*.dc.html` before doing anything else. Those files are the source of truth. If something here conflicts with them, follow NOTES.md and tell me.

## Ground rules (every agent)

- **Device safety:** never flash, `adb remount`, write to `/system`, run `adb root` for system changes, or change partitions without asking me first in chat. Back up first (NOTES.md §7). Normal `adb install -r`, `logcat`, `screencap`, `dumpsys` and `shell` reads are fine.
- **Secrets:** I'll give you the Home Assistant URL and token, the Immich URL and API key, an Anthropic API key and a stock quote API key when you ask. Keep them in `local.properties` / an ignored `secrets.properties`. Never commit them and never put them in logs.
- **Budgets:** the whole app stays under ~200 MB RAM (the device has ~336 MB free). Clock faces hold ~59 fps (the panel's rate). No live blur: Android 11 has no RenderEffect, so use pre-blurred backgrounds (NOTES.md §5.2). The target is 32-bit `armeabi-v7a`.
- **Design fidelity:** every screen must match its `design/<Name>.dc.html` at 960×480: sizes, colours, radii, glass recipe (§3.2), Liquid Glass rules (§3.1), motion curves (§3.5) and Night-mode red (#BE180E). Check by taking a screenshot on the device (`adb exec-out screencap -p > shot.png`), looking at it, and comparing it with the design. The designs use sample data. Wire real data sources where NOTES.md §5.6 / §6 say so, and keep demo data behind a debug flag until a source is configured.
- **One device, shared:** only one agent may use the Echo at a time. Take the lock with `mkdir /tmp/echo-device.lock` (wait and retry if it exists), and release it with `rmdir` when done, including on failure. Connect with `./scripts/echo-connect.sh` and always pass `-s "$(cat .echo-serial)"`.
- **Ownership:** each agent edits only the modules it owns. Shared contracts live in `:core:*` and belong to the lead. If an agent needs a contract change, it writes the request to `docs/contract-requests.md` and continues with a local stub. It does not edit `:core` itself.
- **Status:** each agent keeps `docs/status/<agent>.md` up to date: what's done, what's left, what was measured on the device (RAM, fps, CPU), and open questions.

## Phase 0: the lead does this alone, before spawning agents

1. Put the project in git if it isn't already (`git init`, a sensible `.gitignore` including `local.properties`, `secrets.properties`, `.echo-*`, build outputs). Commit the current working milestone-1 state.
2. Restructure into a multi-module Gradle project, keeping what milestone 1 built:
   - `:app`: launcher shell (HOME activity, kiosk/immersive, screen router, swipe between faces/screens), dependency wiring.
   - `:core:design`: Liquid Glass components (glass surface, tinted glass, capsule, tile, slider, sheet), tokens (colours, type, radii, springs), the pre-blur engine, Night-mode palette mapping.
   - `:core:model`: shared types and interfaces (below).
   - `:core:data`: settings store, Home Assistant client (REST + WebSocket), secrets loading.
   - Feature modules, one per agent (Phase 1 table).
3. Define the **contracts** in `:core:model` so agents can work in parallel without waiting on each other:
   - `LiveActivity` {id, kind, priority, compact/expanded content, actions, staleAfter} and `IslandManager` (publish/update/end) (NOTES §5.4).
   - `MediaState` (title, artist, album, artwork, position, duration, playing, source) as a `StateFlow`, plus `MediaControls`.
   - `SystemState`: mic muted (software and hardware), camera off / shutter closed, Do Not Disturb, Night active, brightness, volume.
   - `AlarmStore` / `TimerStore` interfaces, `Profile` + `ProfileManager`, `NotificationFeed` (banners).
   - `FaceSpec` (id, colour options, supports-night) and a face registry.
   - Provide fake implementations of each so every feature can run alone.
4. Record the UI toolkit decision from milestone 1 (Compose or Views) and the measured fps/RAM in NOTES.md. All agents use that toolkit and `:core:design`.
5. Add `scripts/build-install.sh`, which builds the debug APK, takes the device lock, installs, launches and releases the lock. Add a simple perf script that records PSS memory (`dumpsys meminfo <pkg>`) and frame stats (`dumpsys gfxinfo <pkg>`).
6. Make sure everything builds, installs and runs. Commit. Then create one git worktree + branch per agent from this commit.

## Phase 1: spawn these agents in parallel

Use your sub-agent tool with one git worktree per agent. Give each agent its full brief below plus the ground rules, and tell it which NOTES.md sections and design files are its spec.

| # | Agent | Owns | Builds (spec) |
|---|---|---|---|
| A | **Clock faces** | `:feature:clock` | Analog, World (land mask + sun math from `World.dc.html`), Solar, Float, Liquid Glass clock (lensing shader on the GLES 3.1 path or a BitmapShader), Weather and Calendar faces (UI against fakes), edit mode (`Customize.dc.html`), Night mode on every face (`Night*.dc.html`) with light-sensor auto switching and hysteresis (§5.3). Spec: §2.1, §5.7, the face designs. |
| B | **System layer** | `:feature:system` | Volume keys handled in-app without the system panel. Mic/camera button: plan the `gating` KEY_POWER remap (§5.3) and **ask me before writing any /system key layout**. Until then, intercept it in-app where possible. Shutter via `SW_CAMERA_LENS_COVER`. Indicators (`HUDs.dc.html`), Control Center (`ControlCenter.dc.html`, top-edge pull-down; toggles per §5.5, flagging any that need the priv-app install), notification banners (`Banners.dc.html`, NotificationListenerService + NotificationFeed), and the **Echo Island** renderer and priority rules (`Island*.dc.html`, §5.4). |
| C | **AirPlay audio** | `:feature:airplay` | Audio-only AirPlay 2 receiver (§6): try the shairplay Rust library first, cross-compiled for `armv7-linux-androideabi` with cargo-ndk and called over JNI. Fall back to the other options in §6 and tell me if you switch. Foreground service, mDNS advertisement as "Echo Bedside", playback, metadata + artwork into `MediaState`, remote control, volume sync. Measure CPU and RAM while streaming. |
| D | **Now Playing + media** | `:feature:media` | Now Playing screen (`NowPlaying.dc.html`): ambient artwork colours (Palette + pre-blurred blobs), seek, volume, AirPlay popover, art scaling on pause, lift-into-glass transport. Media island states. Also read other apps' sessions (MediaSessionManager via NotificationListener) into `MediaState`. Build against the `MediaState` fake until C lands. |
| E | **Alarms + timers** | `:feature:alarm` | Exact alarms that survive reboot, a sunrise ramp before the alarm (brightness + warm colour), Ring / Snooze (9 min, left) / Stop (right), Good-morning screen (`Alarm.dc.html`), Timers screen (`Timers.dc.html`), timer + alarm LiveActivities for the island. Works with Do Not Disturb and Night mode. |
| F | **Home + glances** | `:feature:home` | Home Assistant–backed Home screen (`Home.dc.html`: climate, lights, scenes, locks, covers, room switcher actually switching rooms), Weather and Calendar data providers for A's faces, Commute (`Commute.dc.html`), Stocks (`Stocks.dc.html`, quote API, delayed), doorbell events into NotificationFeed + island. Spec §5.6. |
| G | **Voice assistant** | `:feature:assistant` | Assistant screen and droplet (`Assistant.dc.html`) and an Assist-pipeline client to Home Assistant: stream mic audio (wake word runs on the server), show transcript, pipeline steps and the answer with result cards, TTS playback, an assistant LiveActivity. Handle mic muted. Document the HA-side setup (openWakeWord, STT, Claude conversation agent, Piper) in `docs/assistant-setup.md` for me. Spec §6. |
| H | **Camera: presence + profiles** | `:feature:camera` | First test that the camera actually captures. If it fails, stop and report (the patch in §6.5 needs my OK). Then presence (Y-plane frame difference, ~2 fps) → wake/dim, and the face pipeline in §6.6 with **measurements before each stage is turned on**. Profiles screen (`Profiles.dc.html`), the "Auto by face" switch, enrolment flow, "Not me". No frames saved, embeddings encrypted, indicator always shown, off when the shutter is closed or the mic/camera button is on. |
| I | **Photos** | `:feature:photos` | Immich photo frame (`Photos.dc.html`): album choice, 960-px previews cached on disk, prefetch, crossfade + slow zoom with transforms only, time overlay, glass controls on tap. |

Each agent, before reporting done: the module builds; its unit tests pass; it ran on the Echo through `scripts/build-install.sh` with the lock held; it took screenshots and compared them with its designs; it recorded RAM/fps/CPU in its status file; and it lists anything it couldn't finish or needs from me.

## Phase 2: the lead integrates

1. While agents run, answer `docs/contract-requests.md`. Update `:core` on main and tell affected agents to rebase.
2. Merge each finished branch into main one at a time. Build, install and smoke-test on the Echo after each merge. Fix conflicts in `:app` routing.
3. Wire the real implementations in place of the fakes: AirPlay → MediaState → Now Playing + island; alarms/timers → island; HA → Home/Weather/Calendar/Commute/banners; assistant ↔ mic state; camera ↔ shutter/mic state ↔ profiles.
4. Run a full-system check on the device with everything enabled at once: AirPlay streaming + a timer + the assistant listening + presence detection. Record total RAM, CPU and clock-face fps. If over budget, find the biggest consumer and fix it before adding anything else.
5. Go through every `design/*.dc.html` screen on the device. Screenshot it, compare it, and list any mismatches in `docs/status/design-review.md`.
6. Update NOTES.md: tick what's built and what was measured, and list the remaining open questions. Then give me a short summary: what works, what doesn't, anything that needs my decision (system remount for the key remap, priv-app install, camera patch), and the secrets or HA setup still needed.

Start with Phase 0 now. Tell me when Phase 0 is committed and the agents are launched.
