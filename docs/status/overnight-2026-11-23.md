# Overnight work, Nov 23, 2026

## First: the Echo is offline and needs you

At 05:39 I tried "lean SystemUI": SystemUI started in Android's TV setup, which has no phone status bar, navigation bar or lock screen. Memory looked good: SystemUI went from 68 MB to 37 MB. But SystemUI crashed whenever the Echo woke, because the lock-screen code assumes the phone status bar is there. About 3 minutes later the Echo dropped off Wi-Fi and hasn't come back. Most likely, Android's crash protection ("RescueParty") saw a core app crash-looping and restarted into recovery with its **"Can't load Android system"** prompt. Wi-Fi is off on that screen, so I can't reach it.

**What to do:** look at the Echo. If it shows that prompt, tap **Try again**, never **Factory data reset**.

When it's back, a watcher script on the Mac reconnects within a second (`scratchpad/lean-rescue.sh`; the log is `lean-rescue.log`). It turns the lean overlay off, restarts SystemUI, makes EchoHome the Home app again and sets the volume back to 0. If the watcher has stopped, the manual fix is:

```bash
./scripts/echo-connect.sh && ./platform-tools/adb -s "$(cat .echo-serial)" shell cmd overlay disable --user 0 app.vesper.overlay.systemui.lean
```

If it's showing something else, or it keeps going back to recovery, tell me what's on the screen.

**Next attempt (not done):** `./scripts/echo-systemui-lean.sh on` now does three more things:

- It turns RescueParty off first, so a crash loop leaves adb reachable instead of needing someone at the Echo.
- It also disables SystemUI's `KeyguardService`. The window manager then takes Android's supported path for devices without a lock screen.
- It reboots, because that's decided at boot.

The reasoning is in NOTES §7.3. I won't try it again without you there.

## Merged into `main`, never run on the Echo

Everything below builds (`:app:assembleRelease`) and the unit tests pass (`testDebugUnitTest`, about 900 tests). None of it is installed: the Echo went down before I could.

| What | Where | Launch extras to check it |
|---|---|---|
| **Smoother first swipes.** The Widgets and Now Playing pages are built ahead of time after start, and warm-up work waits until nothing is moving. Before: 38 % janky frames, p95 61 ms on the first 6 swipes; 4 % once warm. After: not measured yet. Expected memory cost: +2–5 MB at idle. | `ScreenPager`, `UiQuiet` (`:core:design`), StandBy, Widgets; `docs/status/clock.md` | `scratchpad/measure.sh` (3 runs) |
| **Sleep sounds.** Rain, ocean, white/pink/brown noise, fan and crickets, generated on the Echo with no audio files. Sleep timer of 15/30/60/90 min with a 30 s fade. Shows in Now Playing and the island, and pauses when AirPlay, Bluetooth or Spotify starts. Nothing plays by itself. | new `:feature:sleep`, `docs/status/sleep.md` | `--es screen sleep --es sound rain`; debug builds: `--es state playing` (silent) |
| **Bedtime routine.** The "Good night" button and voice command say when your alarm is and turn on Do Not Disturb until then. They also switch on Night mode and start your default sound with its timer, and undo it all at wake time. There's a settings card on the web page. | `SleepTools.kt`, `docs/status/sleep.md` | `--es screen sleep --es goodnight preview`, `--es screen assistant --es script e` |
| **Internet radio.** Radio Browser (free, no key): search, genres, favourites, recently played, song titles from the stream. Voice: `play_radio`, `stop_radio`. Nothing plays by itself. | new `:feature:radio`, `docs/status/radio.md` | `--es screen radio`, `--es genre jazz`, `--es demo 1` (silent) |
| **Weather radar.** RainViewer (free, no key, credit always on screen) over a map drawn on the Echo from Natural Earth data. No map tiles are fetched. It loops the last hour and opens when you tap the Weather face or widget. | `:feature:home` `radar/`, `docs/status/home.md` | `--es screen radar`, `--es zoom 5` |
| **Spotify Connect name.** It now shows as "EchoHome / Echo Show" instead of librespot. | `:feature:spotify` (rebuilt `.so`) | `curl "http://<echo>:4070/?action=getInfo"` |
| **First-run setup.** Opening another screen now closes it. If setup was never finished, it comes back at the same step at StandBy. | `:feature:settings` `setup/` | `--es screen onboarding --es step 3`, then `--es screen lamp` |
| **Reminders.** The island no longer repeats the banner's Done / Snooze. | `:feature:lists` | (I didn't run it: the chime uses the alarm volume) |
| **Camera light reading.** The camera now gets a real light value after a restart (it read -1 before). | `:feature:camera` | logcat `VesperCamera` |
| **"EchoHome" pill over other apps.** Shown when there's no navigation bar, so you can always get back out of Android Settings. | `:app` `HomeButton.kt` | only shows with lean SystemUI |

One change I made to an agent's work: the Control Center media tile still opens Now Playing. It opens Sleep only while a sleep sound is loaded.

## Volume

Music, system, ring, notification and call volumes were set to 0 at the start of the night. The alarm volume is still 5 (Android's minimum is 3); I wasn't allowed to change it. If the crash protection reset settings, the volumes may have come back. The watcher sets them to 0 again when it reconnects.

## For you to know or decide

- **Push:** `main` is 35+ commits ahead of `origin/main`. Nothing is pushed; say if you want it pushed.
- **A download I didn't clear with you:** the radio agent downloaded Natural Earth map data, four public-domain GeoJSON files from jsDelivr's mirror of the `nvkelso/natural-earth-vector` repo. It's data only, nothing was run, and it was converted into `assets/radar/ne50m.bin` (279 KB, public domain).
- **RainViewer's free API is for personal use only**, max zoom 7. Fine for your own Echo; it would matter if this were ever distributed.
- **Not verified on the Echo at all:** sound quality, the CPU and memory of the new features, how the Sleep and Radio screens look (neither has a design artboard), real radio streams, the radar's draw cost, and the startup-stutter numbers after the fix.
