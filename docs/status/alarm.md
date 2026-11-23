# Agent E — Alarms + timers (`:feature:alarm`)

Spec: NOTES §6 "Alarms", §2.1 Alarm / Timers, `design/Alarm.dc.html`, `design/Timers.dc.html`, timer states in `Island*.dc.html`.

## Done
- **Alarm engine** (`engine/AlarmEngine.kt`, no Android, unit-tested): DST-safe next trigger (instants, not local times: a time in the spring-forward gap rings at the shifted time, a fall-back overlap rings once), weekdays, one-shot alarms switch themselves off when they ring. Phases `Idle → Sunrise → Ringing ⇄ Snoozed → GoodMorning → Idle`:
  - Snooze = the alarm's `snoozeMinutes` (9). Snoozed → Ringing again when it's up.
  - Nobody answers: auto-snooze after 10 min of ringing, at most 3 times, then it goes quiet.
  - `stop()` during Sunrise skips that morning; Good morning times out after 60 min.
  - An occurrence missed by ≤ 10 min (process restart, reboot) still rings; setting an alarm for a time that just passed doesn't ring; the clock jumping back doesn't ring an alarm twice.
- **`RealAlarmStore`** → `services.alarms`: persisted in `filesDir/alarms.json` (AtomicFile). `setAlarmClock` for the next ring (exact, idle-proof, is the system's "next alarm"), `setExactAndAllowWhileIdle` for the other wake-ups (sunrise start, auto-snooze, timeouts). `AlarmSystemReceiver` re-evaluates on `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`.
- **Ringing** (`AlarmService`, a foreground service): the system default alarm sound (`RingtoneManager` TYPE_ALARM, Hassium on this Echo) on `USAGE_ALARM`, looping, volume ramping quadratically from 4 % to 100 % over 45 s. A synthesised fallback tone is used if the URI fails. It also:
  - lifts the alarm stream to ≥ 60 % for the ring and restores it after, but only with the "Make sure alarms are audible" setting, which is off by default;
  - takes transient audio focus (AirPlay music pauses or ducks);
  - holds a screen-bright + ACQUIRE_CAUSES_WAKEUP wake lock;
  - posts a full-screen-intent notification with Snooze / Stop actions and opens the `alarm` screen.
  
  Works in Do Not Disturb: `USAGE_ALARM` passes DND's alarms exception, and the channel has `setBypassDnd`.
- **Sunrise ramp** (`sunriseMinutes`, default 30, before an alarm with `sunrise = true`):
  - Brightness goes from the current value to the "Sunrise brightness" setting (100 %) on a `p^1.6` curve, every 10 s through `services.systemControls.setBrightness` (silent since contracts round 1).
  - `SunriseOverlay` (z 250, below the island, never takes touches) draws the alarm board's warm glow over whatever screen is showing, fading in with the progress, so the ringing screen takes over seamlessly.
  - If the ramp is cancelled, brightness is restored.
  - It still runs in Night mode: nothing in the design or NOTES says to skip it, and the warm light is the point.
- **Timer engine + `RealTimerStore`** → `services.timers`: up to 3 timers, persisted in `filesDir/timers.json` with end times on `elapsedRealtime` plus boot count and wall clock, so they survive process death *and* a reboot. Completion uses an exact `ELAPSED_REALTIME_WAKEUP` alarm (plus an in-process handler). A DONE timer chimes (synthesised two-note bell, `USAGE_ALARM`) for 3 min, then goes quiet; the card stays until it's dismissed. Repeat on a finished timer.
- **Settings** ("Alarms & timers" section, `AlarmSettings.kt`, applied live): sunrise on/off, sunrise length (30 min), sunrise brightness (advanced), snooze (9 min), alarm sound (default alarm sound / soft chime / beeps), volume ramp (45 s, 0 = full at once), "Make sure alarms are audible" (volume floor, off), timer chime length (3 min, advanced). They apply to every alarm through the engine's `adjust` hook; changing one re-evaluates and reschedules at once.
- **Stale state guard**: a persisted Ringing/Snoozed state that nothing handled (process dead, another build installed) is dropped instead of ringing late. The device test hit exactly this case; it's fixed and tested.
- **Island**: `ALARM_RINGING` (Snooze = PRIMARY, Stop), a TIMER "Snoozed" countdown while snoozed (Stop), `TIMER` per running/paused timer (Pause/Resume = PRIMARY, Cancel), `TIMER_DONE` (Stop = PRIMARY, Repeat; stale after the 3-min alert). `ownerScreen` is `alarm` / `timers`.
- **Screens**:
  - `alarm` (not in the pager) follows the phase: Ringing (breathing sunrise glow, giant tinted Snooze left / glass Stop right), Snoozed (live countdown + track, Stop Alarm), Good morning (weather from `services.weather`, next event from `services.calendar`, lamp card only with Home Assistant configured or in demo, Ring again = snooze, Done).
  - Glass buttons use the board's own glass values, plus a pre-rendered saturate/brightness backdrop of the glow (one ~115 KB bitmap per state, never per frame).
  - `timers` (pager order 30): the board's layered gradient rendered once, plus a pre-blurred backdrop for the glass cards. Countdown digits tick once a second; the ring transitions linearly over 1 s only when it moves ≥ 2 px. Finished cards flash green (alpha-only layer).
  - Nothing animates while hidden.
- **Night mode**: Timers go red (black background, red outline glass, red ring/text). On the alarm screen, Snoozed follows Night (red, no glow) because you're back in bed; Ringing and Good morning stay in full colour because it's time to wake up.
- **Debug**:
  - `--es screen alarm --es demo ringing|snoozed|morning` (or `alarm_demo`);
  - `--es screen timers --es timers_demo 1` (the board's Pasta/Oven/Tea);
  - a debug-only receiver (`src/debug`, DUMP permission, so adb shell only): `am broadcast -a app.vesper.alarm.DEBUG -n app.vesper/app.vesper.feature.alarm.AlarmDebugReceiver --es cmd add --es at +2 [--ei sunriseMin 1] | list | clear | snooze | stop | dismiss | timer --ei sec 30 | timers_clear`.
- Tests: `./gradlew :feature:alarm:testDebugUnitTest`, 37 tests: next-trigger math (weekdays, DST gap and overlap, zone change), sunrise, snooze, auto-snooze cap, stop/skip, timeouts, missed alarms, clock set back, stale ringing/snoozed state, global settings, persistence round-trip; timers (pause/resume, add time, restart, alert timeout, process death, reboot).

## Leave-by, skip holidays, alarm list + editor (Nov 22 evening, branch of a08be1a)

- **Engine** (pure, `engine/`): `nextOccurrenceAfter(alarm, after, zone, OccurrenceRules)` gives the trigger and a `reason`; `nextTriggerAfter` is unchanged for plain alarms. The engine keeps two persisted maps in `AlarmData`: `shifts` (leave-by: alarm|date → earlier minutes, event, start, travel) and `dayOffs` (date → title of an all-day holiday / vacation / day off / PTO / OOO event).
  - **Skip holidays** (repeating alarms only; one-shots are never skipped): `Holidays.federalName` computes the 11 US federal holidays with observed dates (Sat → Fri, Sun → Mon, incl. Jan 1 observed on Dec 31 of the previous year, shown as "Independence Day (observed)"), plus `dayOffs`. `NextAlarm` points at the next valid day with reason "Skipping Thanksgiving" (only when the skipped occurrence is still ahead, so it clears once its time passes). DST rules are unchanged (instants).
  - **Leave-by**: `LeaveBy.plan` = min(set time, event start − travel − `readyMinutes`), at most 2 h earlier; null travel or enough time already → set time, no reason. Reason "Earlier for Dentist 8:00 · 25 min drive". The sunrise ramp and the exact system alarm (`setAlarmClock` + the sunrise wake-up) both follow the adjusted time; the shift stays after it rang, so the set time doesn't ring again the same morning.
  - Event: that date's first timed event with a location; with `profileId` set, only that person's plus shared events.
- **When it's computed** (`RealAlarmStore.refreshPlan`, `LeavePlanner`): at start, at every calendar change, at every alarm edit, overnight (00:05) and 3 h before each leave-by alarm (inexact `setAndAllowWhileIdle` → `ACTION_REFRESH`, which forces a fresh `services.travel.minutesTo(location, eventStart − 1 h)`; other triggers reuse an answer < 3 h old). Plans are persisted, so a restart doesn't lose the earlier time. An empty calendar in the first 2 min after start is treated as "not loaded", and a failed or null travel lookup keeps a good earlier estimate for the same event instead of dropping it. A new plan whose ring time has already passed is ignored (the alarm rings at its old time rather than skipping the day).
- **UI**: new `alarms` screen (pager order 25, before Timers): header "Next · Tomorrow 6:15 AM" with the reason line in warm yellow, a list of alarms (time, label, "Weekdays · Leave-by · Skips holidays", on/off switch) and "New alarm". Tapping a row opens the editor sheet: hour/minute steppers (±1 h, ±5 min), AM/PM, repeat day chips, Leave-by switch with 15/30/45/60/90 min ready chips, Skip holidays switch, Save / Cancel / Delete (asks twice). Built from the Timers board's gradient, glass and controls (no board for it exists); night mode follows red. `--es screen alarms [--es alarms_demo editor|editor_new]`.
- **Persistence/JSON**: `Codec` (this module) reads/writes `leaveBy`, `readyMinutes`, `skipHolidays`, `shifts`, `dayOffs`. The web settings page's alarm JSON is `AlarmJson` / `AlarmsApi` in `:feature:settings` and was left to the settings agent.
- **Debug receiver**: `add … --ez leaveBy true --ei ready 45 --ez skipHolidays true`; `--es cmd shift --es id X --ei earlier 10 --es title Dentist --ei travel 25` plants a plan (no travel service is bound on this build).
- **Tests**: 70 in the module (was 37): +33 in `LeaveHolidayTest` (federal holidays and observed rules incl. year boundary, title matching, multi-day events, skipping across DST gap/overlap and Thanksgiving/Veterans Day, reasons, one-shots, engine rings at the shifted time and not again, planner: event choice per profile, cap, null travel, past times, off days, deleted events, uncertain calendar, refresh times, codec round trip).
- **On the Echo** (debug build, Nov 22 19:25, test alarms deleted afterwards, none left in `dumpsys alarm`): with a planted shift of 15 min on a 06:30 weekday alarm, `next` = Mon 06:15 "Earlier for Dentist 8:00 · 25 min drive", and `dumpsys alarm` showed `app.vesper.alarm.WAKE` at 06:15 (the alarm clock) and 05:45 (sunrise, 30 min before the adjusted time), plus the `REFRESH` wake-up at 00:05. The editor toggles work by touch. Alarms list idle: 67 MB PSS, 8 % of one core, 127 frames in 8 s, p99 5 ms, 0 janky; editor open: 69 MB, 7 %, p99 5 ms. Shots: `docs/status/shots/alarm/alarms-list.png`, `alarms-empty.png`, `alarm-editor.png`, `alarm-editor-demo.png`, `alarm-editor-leaveby-off.png`. There was no alarm list or editor before, so no "before" shots.
- **Not verified**: a real travel estimate (no `TravelEstimator` is bound on this build; `home`'s lands separately), a real leave-by ring at the shifted time, the overnight / 3 h refresh actually firing, skipping with a real calendar vacation event.

## Contract requests
- None new. (`AlarmJson` in `:feature:settings` must add `leaveBy`, `readyMinutes`, `skipHolidays`.)

## Left
- The alarm list / editor screen has no board in `design/`: the one built is a first version from the Timers board's elements.
- Reboot-survival test on the device (needs the user, see below).
- Volume keys / mic button while ringing (snooze) aren't wired. `:feature:system`'s key handler runs first; ask B to call `services.alarms.snooze()` on a key press while `phase` is Ringing.

## Real ring test on the Echo (05:02–05:03, Nov 22)
- A one-shot alarm was scheduled 1 min out with a 1-min sunrise, using the debug receiver. Android Settings was put in front to test ringing from the background.
- `dumpsys alarm`: `RTC_WAKEUP … app.vesper.alarm.WAKE when=05:03:00.000`, "Next wake from idle".
- Sunrise: phase `Idle → Sunrise` at once. The warm wash showed over the Digital face (`real-sunrise.png`), and the Digital face's alarm complication showed "5:03 AM" from `services.alarms.next`.
- **It rang on time**: `Sunrise → Ringing (alarm manager)` at 05:03:00.012.
  - `AlarmService` was in the foreground (category=alarm).
  - The `Hassium` default alarm played through MediaPlayer, `USAGE_ALARM`, `state:started`.
  - Audio focus was held: `GAIN_TRANSIENT`, `USAGE_ALARM`.
- **Not shown full-screen over another app** at the time: Android 11 blocked the background activity start and showed a heads-up instead. Fixed since, see the next test.

## Real ring tests after quiet hours (12:54–13:20, Nov 22; alarm stream at 2/7, restored to 5)
Main a8c903f plus the fixes below. The app is a /system/priv-app (updated by the debug build) with `START_ACTIVITIES_FROM_BACKGROUND: granted=true`.
- **Full screen over another app works.** Android Settings was in front. At the ring (`Sunrise → Ringing` at 12:56:00 and again at 13:15:00), `mResumedActivity` became `app.vesper/.MainActivity` on the alarm screen. The default alarm played (`USAGE_ALARM` player started).
- **Tap-through**: Snooze (sound stops, "Snoozed · rings again at 1:05", 8:57 countdown) → Stop Alarm → Good morning (weather, up next, lamp) → Done → `Idle`, back to the clock face. Shots: `real-ringing.png`, `real-snoozed.png`, `real-morning.png`.
- **Bug found and fixed: user actions weren't saved.** The store compared the engine with a snapshot taken after Snooze / Stop / Done / edits had already changed it, so only evaluation-driven changes reached `alarms.json`. The file stayed `RINGING` after Done. When the process restarted (another agent's install), it loaded that state and **rang again** (`Idle → Ringing (start)` at 13:01:15; the alarm stream showed repeated starts until I deleted the file). It now compares with what's on disk. Re-test: the file goes SNOOZED → MORNING → no active state, and the deleted alarm is gone.
- **Bug found and fixed: our heads-up covered the ringing screen.** The ringing notification (high importance, full-screen intent) showed as a heads-up over our own alarm screen. It now starts on the quiet channel and moves to the heads-up / full-screen channel only if the alarm screen isn't in front 1.5 s after the ring starts (or is left while ringing). So the fallback stays for a blocked start or a screen-off FSI. Re-test: no heads-up, notification on `alarm.quiet`.
- **Timer**: three timers (25 s, 12 min, 25 min) on the Timers screen, with three exact `ELAPSED_REALTIME_WAKEUP` wake-ups. Tea finished at 25 s: green Done ring and Repeat button, the chime played on `USAGE_ALARM` (quiet notification, no heads-up), and clearing stopped it. Shots: `real-timers.png`, `real-timer-done.png`.
- End state: no `app.vesper` entries under "Pending alarm batches", `alarms.json` / `timers.json` empty, alarm volume back to 5/7, no audio focus held, app ops and DND untouched.

## Measured on the Echo
(debug build, AirPlay service also running)

| State | PSS | CPU | Frames |
|---|---|---|---|
| Ringing demo, glow breathing (60 fps animation) | 54.4 MB | 24.1 % of one core | 619 in 10 s, p50/p90/p99 10/11/13 ms, 0.32 % janky |
| Snoozed demo, 1 tick/s | 55.6 MB | 1.6 % | 8 in 8 s |
| Timers, 3 running, 1 tick/s (Nov 22 night, before the music island) | 64.0 MB | 2.9 % | 11 in 10 s, 20 ms each (before the fix that stopped redrawing the whole screen every second) |
| **Real ringing**, over Settings, before the heads-up fix (12:56) | 75.9 MB | 36.7 % | 624 in 10 s, p50/p90/p99 18/20/25 ms, 80 % janky |
| **Real ringing**, after the fix (13:15) | 88.2 MB | 54.0 % | 624 in 10 s, p50/p90/p99 16/19/22 ms, 46 % janky |
| Ringing demo, same build, same time (13:00) | 129.8 MB | 64.6 % | 624 in 10 s, p50/p90/p99 9/12/18 ms, 1.4 % janky |
| **Real timers**, 3 running (13:18) | 105.0 MB | 58.1 % | 628 in 10 s, p50/p90/p99 10/22/28 ms, 27 % janky |

The PSS and CPU numbers in the after-quiet-hours rows are the whole app with every feature merged (music island, presence camera at 2 fps, AirPlay), so they don't compare with the night rows. **The app now draws about 60 fps everywhere, even on the Timers screen, which needs one frame a second.** The music island's equalizer bars at the top animate continuously (visible in every shot, with nothing playing on the alarm stream). Each of those frames recomposites the whole window, which on the Ringing screen's large glow layers costs ~16 ms. That island belongs to agent D (media). It should stop animating when nothing is playing, and the alarm screen should hide it (contract request below). The remaining difference from the demo is the alarm audio decoding on the same CPU.

## Screenshots (`docs/status/shots/alarm/`)
Each was compared with a static headless-Chrome render of the board at 960×480:
- `ringing.png` vs Alarm.dc.html ringing: layout, type and sizes match. The button colour matches after adding the saturated glow backdrop.
- `snoozed.png` vs snoozed: matches. The demo's "rings again at" uses the real clock.
- `morning.png` vs good morning: matches. Data comes from the fakes: 54° partly cloudy (cloud icon) and "Farmers market" instead of the board's 52° Sunny / Morning run.
- `timers.png`, `timers-done.png` vs Timers.dc.html. `timers.png` shows a bug since fixed: the cards' backdrop was offset because they drew mid pager-scroll, so they look uniformly red. `timers-done.png` shows the right backdrop, the green Done ring and the Repeat button. Its SystemUI heads-up ("Tea timer done") is also fixed: timers now use a quiet channel and the island shows them.
- `timers-empty.png`: empty state after the fixes.
- `real-sunrise.png`: the night test. `real-ringing.png`, `real-snoozed.png`, `real-morning.png`, `real-timers.png`, `real-timer-done.png`: the real tests after quiet hours (the island at the top is the music island). On `real-snoozed.png` the island covers the "Snoozed · rings again at 1:05" caption.

## Open questions
- "Ring again" on Good morning: the board's script rings again *immediately* (a demo convenience). I made it snooze (rings again in 9 min), which seems like the real intent.
- Sunrise ramp vs Night mode / auto-brightness: the ramp writes brightness every 10 s for 30 min. If `:feature:clock` / `:feature:system` also drive brightness from the light sensor, they should back off while `services.alarms.phase` is `Sunrise` or `Ringing`.

## Needs from the user
- A reboot test: schedule an alarm (debug receiver), reboot the Echo, check it still rings. Wireless adb may not come back on its own after a reboot.

## Device setup
- None. Full screen over other apps relies on the priv-app allow-list for `START_ACTIVITIES_FROM_BACKGROUND`, not an app op.

## Requests to the lead (docs/contract-requests.md)
- `--es demo <string>` and `setBrightness(showHud)`: landed in contracts round 1.
- Alarm list editing from the phone settings page: an `AlarmJson` codec in `:core:model`, plus endpoints for K (open).
- Let full-screen screens hide the island (`ScreenSpec(hidesIsland = true)`), for the alarm screen (open).
- For agent D: the music island animates its bars at 60 fps with nothing playing, so the whole app redraws at 60 fps on every screen.

## Nov 23: per-person alarms

`Alarm.profileId` (null = shared) is now used everywhere. **Every alarm still rings whoever is in the room**; the owner only changes what the screens show and say. Rules in `AlarmPeople` (pure, `AlarmPeopleTest`).
- **Alarms screen**: with someone recognised, their alarms plus the shared ones, and a "Maya · and shared" chip by the title; with nobody recognised, every alarm (so nobody's alarm is out of reach). Personal rows carry a small name chip in the owner's colour after the label. The header's "Next" is the next one in the list (the store's next when it's listed, with its reason).
- **Editor**: a "Whose alarm" row (right column, under Skip holidays) with a chip per person (their colour) and "Everyone". A new alarm made while someone is recognised starts as theirs. Hidden when nobody has a profile.
- **Ringing**: "Maya’s alarm · Wake Up" with the bell and her name in her colour (lightened a quarter for contrast); Snoozed says "Maya’s alarm · Snoozed · rings again at …"; Good morning says "Good morning, Maya" (when it fits beside the clock) and Up next uses her events plus shared ones. The island's ringing label is "Maya · Wake Up"; the notification title starts with "Maya’s alarm".
- **Sound**: a person's own `Profile.alarmSound` (chime / beeps / default) plays for their alarms (`SoundRequest.alarmSound`), else the `alarm.sound` setting.
- **Morning brief** (`:feature:home`): the brief after a person's Good morning is theirs (`brief.profile` screen arg: their greeting and calendar), whoever the camera sees.
- **Voice** (`VesperTools`, set_alarm / list_alarms only): `set_alarm` belongs to the recognised person by default, `shared: true` makes it shared (the old opt-in `mine` is gone). `list_alarms` gives each alarm's `owner` (name or "shared") and `recognized`.
- **Web settings**: alarm rows end with the owner ("Run, Weekdays, Maya’s"); the editor has a "Whose alarm" select; `AlarmsApi` accepts `profileId` (an id or null).
- Debug: `--es screen alarm --es alarm_demo ringing --es alarm_owner maya` (also `snoozed`, `morning`); `--es screen alarms --es alarms_demo maya --es profile maya` adds Maya's 6:15 weekday "Run" alarm (id `debug-maya`, really rings) and shows her view; `alarms_demo clear` deletes it.
- **Unverified on the Echo**: everything above (built and unit-tested only), including the chip sizes, the "Whose alarm" row's fit in the sheet and a real ring of a person's alarm with their sound.
