# Sleep sounds + bedtime routine (`:feature:sleep`)

There's no board in `design/` for this (NOTES §2.2 listed "sleep sounds / wind-down" as still to design). The screen is built from the glass design system: the Lists / Timers glass over a pre-blurred night gradient, Inter at the designs' sizes, one tinted primary action.

**Why its own module:** it owns a player (an AudioTrack thread), a screen, settings and the routine, and the assistant and Control Center reach it only through a `:core:model` contract. That's the architecture's rule for a feature, and `:feature:media` stays the place that *shows* media (Now Playing, island, other apps' sessions) rather than producing it.

## Done

- **Contract** (`core/model/Sleep.kt`, additive; see `docs/contract-requests.md`): `SleepSound` (rain, ocean, white, pink, brown, fan, crickets; `of(name)`), `SleepState`, `BedtimeRequest`, `BedtimeReport`, `SleepSounds` (`state`, `play(sound?, timer?)`, `stop`, `setTimer`, `bedtime`), `NoSleepSounds` as the default `services.sleep`.
- **Synthesis** (`synth/SleepSynth.kt`, pure Kotlin, no Android, no audio files, no dependencies): 22.05 kHz mono float, rendered in 256-sample blocks.
  - White noise from a 32-bit xorshift (with a gentle top-octave roll-off), pink (Kellet's 3-pole filter), brown (leaky integrator).
  - **Rain**: band-limited pink hiss (450 Hz – 5.2 kHz) swelling with a slow random gust LFO, Poisson drops (20–70 a second, filtered noise bursts of 4–14 ms) and a low rumble.
  - **Ocean**: waves of 7–12 s with random size; quick swell then a long draw-back, a brown-noise body and a wash whose low-pass cutoff follows the wave (brighter as it breaks).
  - **Fan**: low-passed pink + brown, a 105 Hz state-variable band-pass hum, 14.5 Hz blade wobble and a slow drift.
  - **Crickets**: three crickets (4.3–4.9 kHz carriers from a sine table, 3–4 pulses per chirp at their own rates, distance drifting) over still night air.
  - LFOs update once per block and are interpolated; a DC blocker and a soft knee above 0.8 keep every sample inside ±1.
  - Calibrated to about −18 dBFS RMS each (rain −17.3, ocean −18.8, white −17.8, pink −17.6, brown −17.6, fan −17.6, crickets −19.3).
- **Mixer** (`synth/SleepMixer.kt`): level × 3 s fade-in × the timer's fade-out (squared, over the last 30 s) × stop ramp (600 ms) × duck (0.25), computed once per block and ramped per sample (no clicks); 1.5 s equal-power crossfade when the sound changes; 16-bit out. It ends itself when the timer's fade reaches silence.
- **Output** (`SleepOutput.kt`): streaming `AudioTrack`, `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`, 22.05 kHz mono 16-bit, 1024-sample writes (46 ms) from one `THREAD_PRIORITY_AUDIO` thread that exists only while a sound plays. Logs its own synthesis cost about once a minute (`VesperSleep`).
- **Player** (`SleepController.kt` → `services.sleep`):
  - Nothing plays at install, after a restart, in demo mode or from a launch extra: only a tap or a voice request starts a sound.
  - Sleep timer 15 / 30 / 60 / 90 / off; changing it restarts it from now; pause freezes the time left and resume continues it; the last 30 s fade out, then it stops and lets go of audio focus.
  - **Media session**: published to `services.media` as source `"sleep"` (`OTHER_APP`, label "Sleep sounds"), title = the sound, artwork drawn per sound (`SleepArt.kt`, 320 px, last two kept), the timer as the track (Now Playing's scrubber shows elapsed / remaining, no seek), route chips "On this Echo · Generated · 22.05 kHz". So the island's MUSIC activity, Now Playing and Control Center's media tile show it with no changes to them. Next / previous change the sound (keeping the timer); Disconnect stops it.
  - **Arbitration**, the same way the other sources take turns: it takes `AUDIOFOCUS_GAIN` when it starts (music pauses); AirPlay / Bluetooth / Spotify taking focus → `LOSS` → it pauses for good; a transient loss (notification, timer chime) pauses and resumes on `GAIN` if it lasted ≤ 30 s, a longer one (an alarm ringing) doesn't bring the rain back; the assistant's may-duck focus ducks it. Plus the hub rule: any other source that starts playing while it plays pauses it (sources that start without focus: AirPlay 2 before PCM, AVRCP resume); the silent demo source is ignored.
- **Bedtime routine** (`BedtimeRoutine.kt`, `services.sleep.bedtime()`):
  1. reads the next alarm (`services.alarms.next`);
  2. Do Not Disturb on until that alarm (or the fallback wake time, 07:00, when none is set; alarms pass DND anyway);
  3. Night mode on;
  4. optionally dims the screen (off by default, to 10 %);
  5. starts the default sound with the default timer.
  Each step follows its setting unless the request overrides it; failed steps are reported. What it turned on is remembered and handed back at the wake time (`WakeRestore`, an exact `RTC_WAKEUP` alarm → `WakeReceiver`, plus a check at every start): DND off and Night mode back to what it was, **unless it was already on before or the user changed it overnight**.
- **Sleep screen** `ScreenSpec("sleep", order = 15)` (pager, after Now Playing):
  - Seven glass sound tiles: Rain, Ocean, Fan, Crickets on top (138 px), White / Pink / Brown noise below (188 px). Selected = brighter fill + a 2 px ring and icon in the sound's accent; "● Playing" / "Paused" under the name. Stopped, a tap only selects; playing, a tap crossfades.
  - Right column: the "Sleep timer" platter (15 / 30 / 60 / 90 / Off as plain fills on the glass, no glass on glass) with a status line ("Stops at 11:42 PM · 28 min left", "Fading out…", "Paused · 12 min left"); "Play Rain", or Pause/Resume + Stop; the tinted (primary) **Good night** pill, which runs the routine, shows "Good night. Your alarm is at 7:00 AM." and goes to StandBy after 3 s.
  - Header: "Sleep", the state line, the clock and the next alarm. Footer: what Good night will do, from the settings.
  - Night mode: black, red text, red outline glass, the primary pill with a faint red fill. Zero frames while idle: it redraws on state changes, touches and the minute.
- **Reachable from**: the pager; Control Center's media tile (when the sleep sound is loaded, or when nothing is loaded and demo data is off; any other source still opens Now Playing); the assistant's `open_screen` ("sleep").
- **Voice** (`:feature:assistant`, `llm/SleepTools.kt`, dispatched from `VesperTools` with 3 added lines):
  - `bedtime` ("good night", "bedtime"; optional sound / timer_minutes / play_sound / dim). It returns the next alarm, DND until, Night mode, the sound and timer and the failed steps, with the card "Good night · Alarm 7:00 AM · Rain 30 min". The system prompt lists it.
  - `sleep_sound` (play / stop / set_timer).
  - Demo script `e` ("Good night", display only: no sound, nothing changed).
- **Settings card "Sleep sounds & bedtime"** (order 38, applied at use time; loudness live):
  - default sound, sleep timer, loudness (0.2–1 on top of the volume);
  - Good night starts the sound / turns on DND / turns on Night mode / dims the screen;
  - advanced: dim to, and the DND end when no alarm is set.
- **Now Playing**: no lyrics button and no LRCLIB lookups for the sleep source (`:feature:media`, 6 lines).
- **Tests** (`./gradlew :feature:sleep:testDebugUnitTest`, 38; plus 6 in `:feature:assistant` `SleepToolsTest`):
  - synthesis per sound: finite, RMS in −28…−10 dBFS, peak < 1, soft knee hit < 1 s in 30 s, no DC, never silent for a second;
  - the ocean swells, white noise is steady, the spectral tilt goes white > pink > brown, seeded output is repeatable;
  - CPU benchmark;
  - fades: the timer curve, fade-in, the stop ramp, the mixer ending silent at the timer, duck and stop without jumps;
  - player: nothing plays until asked, defaults, focus refused, the timer fade then stop, timer off, retime, pause/resume keeps the time left, skip wraps, AirPlay LOSS, short and long transient losses, duck, hub arbitration (demo ignored), disconnect, the silent preview, an early output end restarts;
  - routine: with an alarm, no alarm (fallback), settings off and overrides, failures reported, already-on left alone, wake restore at the right time only, a manual change kept, the controller path;
  - text, settings and sound names;
  - the tool handlers: specs, the report, overrides, no feature, play / retime / stop / errors, the demo.

## Synthesis cost (JVM microbenchmark, `SynthTest`)

60 s of audio per sound after JIT warm-up, on the Mac (Apple silicon, JDK 17):

| Sound | ns / sample | % of one core (JVM) | estimate on an A53 at ~12× |
|---|---|---|---|
| rain | 6.3 | 0.014 % | ~0.17 % |
| ocean | 6.0 | 0.013 % | ~0.16 % |
| white | 4.2 | 0.009 % | ~0.11 % |
| pink | 4.3 | 0.009 % | ~0.11 % |
| brown | 3.9 | 0.009 % | ~0.10 % |
| fan | 7.3 | 0.016 % | ~0.19 % |
| crickets | 6.6 | 0.015 % | ~0.18 % |
| mixer + rain→ocean crossfade | | 0.021 % | ~0.25 % |

The test fails above 0.25 % on the JVM (≈ 3 % of an A53 core at 12×). The real number on the Echo is logged by the audio thread (`VesperSleep: synth rain: x.xx % of one core`). AudioFlinger's resampling from 22.05 kHz to the mixer rate is on top of this, in `audioserver`.

## Not verified (no device run: the Echo stays silent tonight)

- Nothing was played, installed or screenshotted on the Echo. Not checked: how the sounds sound (only measured: levels, spectra, bounds), the AudioTrack at 22.05 kHz on this ROM, the real CPU and PSS, the focus hand-offs with the real AirPlay / Spotify / Bluetooth receivers, the WakeReceiver firing at the alarm time, and the screen's layout against the design system on the panel.
- The screen was written to the 960×480 grid without a render. First thing to check: `./scripts/build-install.sh --screen sleep --extra '--es sound rain' --shot …` (silent).

## Launch extras (all silent)

- `--es screen sleep` · `--es sound rain|ocean|white|pink|brown|fan|crickets` (selects; plays nothing) · `--es timer 15|30|60|90|0`.
- Debug builds: `--es state playing|paused|stopped` (a **silent preview**: the screen, island and Now Playing show the sound playing, no AudioTrack, no audio focus); `--es goodnight preview` (the answer line, without running the routine).
- Now Playing / island with the sleep sound: `--es screen sleep --es sound ocean --es state playing`, then `--es screen nowplaying`.
- Assistant demo: `--es screen assistant --es script e`.
- Night: the clock's `--es screen standby --es nightmode on` first, then `--es screen sleep`.

## Left

- An artboard for the Sleep screen (and the routine's answer) would let it be reviewed against a design.
- The sleep sound is `MediaSourceKind.OTHER_APP` ("Playing from Sleep sounds"); a `SLEEP` kind with its own glyph would need Now Playing and Control Center changes (contract request filed).
- Control Center's empty media tile still says "AirPlay · Spotify"; it now opens the Sleep screen when nothing is loaded (release builds).
- No equaliser or motion on the tiles while playing (on purpose: zero frames idle).
