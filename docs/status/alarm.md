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
  - lifts the alarm stream to ≥ 60 % for the ring, then restores it;
  - takes transient audio focus (AirPlay music pauses or ducks);
  - holds a screen-bright + ACQUIRE_CAUSES_WAKEUP wake lock;
  - posts a full-screen-intent notification and opens the `alarm` screen.
  
  Works in Do Not Disturb: `USAGE_ALARM` passes DND's alarms exception, and the channel has `setBypassDnd`.
- **Sunrise ramp** (`sunriseMinutes`, default 30, before an alarm with `sunrise = true`):
  - Brightness goes from the current value to 100 % on a `p^1.6` curve, every 10 s through `services.systemControls.setBrightness` (silent since contracts round 1).
  - `SunriseOverlay` (z 250, below the island, never takes touches) draws the alarm board's warm glow over whatever screen is showing, fading in with the progress, so the ringing screen takes over seamlessly.
  - If the ramp is cancelled, brightness is restored.
  - It still runs in Night mode: nothing in the design or NOTES says to skip it, and the warm light is the point.
- **Timer engine + `RealTimerStore`** → `services.timers`: up to 3 timers, persisted in `filesDir/timers.json` with end times on `elapsedRealtime` plus boot count and wall clock, so they survive process death *and* a reboot. Completion uses an exact `ELAPSED_REALTIME_WAKEUP` alarm (plus an in-process handler). A DONE timer chimes (synthesised two-note bell, `USAGE_ALARM`) for 3 min, then goes quiet; the card stays until it's dismissed. Repeat on a finished timer.
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
  - a debug-only receiver (`src/debug`, DUMP permission, so adb shell only): `am broadcast -a com.echobedside.alarm.DEBUG -n com.echobedside/com.echobedside.feature.alarm.AlarmDebugReceiver --es cmd add --es at +2 [--ei sunriseMin 1] | list | clear | snooze | stop | dismiss | timer --ei sec 30 | timers_clear`.
- Tests: `./gradlew :feature:alarm:testDebugUnitTest`, 34 tests: next-trigger math (weekdays, DST gap and overlap, zone change), sunrise, snooze, auto-snooze cap, stop/skip, timeouts, missed alarms, clock set back, persistence round-trip; timers (pause/resume, add time, restart, alert timeout, process death, reboot).

## Left
- No alarm list / editor screen: none is designed yet (NOTES §2.2). Alarms come from `services.alarms.upsert` (assistant, settings), or the debug receiver for now.
- Reboot-survival test on the device (needs the user, see below).
- Volume keys / mic button while ringing (snooze) aren't wired. `:feature:system`'s key handler runs first; ask B to call `services.alarms.snooze()` on a key press while `phase` is Ringing.

## Measured on the Echo
(debug build, AirPlay service also running)

| State | PSS | CPU | Frames |
|---|---|---|---|
| Ringing demo, glow breathing (60 fps animation) | 54.4 MB | 24.1 % of one core | 619 in 10 s, p50/p90/p99 10/11/13 ms, 0.32 % janky |
| Snoozed demo, 1 tick/s | 55.6 MB | 1.6 % | 8 in 8 s |
| Timers, 3 running, 1 tick/s | 64.0 MB | 2.9 % | 11 in 10 s, 20 ms each (before the fix that stopped redrawing the whole screen every second) |

## Screenshots (`docs/status/shots/alarm/`)
Each was compared with a static headless-Chrome render of the board at 960×480:
- `ringing.png` vs Alarm.dc.html ringing: layout, type and sizes match. The button colour matches after adding the saturated glow backdrop.
- `snoozed.png` vs snoozed: matches. The demo's "rings again at" uses the real clock.
- `morning.png` vs good morning: matches. Data comes from the fakes: 54° partly cloudy (cloud icon) and "Farmers market" instead of the board's 52° Sunny / Morning run.
- `timers.png`, `timers-done.png` vs Timers.dc.html.

## Open questions
- "Ring again" on Good morning: the board's script rings again *immediately* (a demo convenience). I made it snooze (rings again in 9 min), which seems like the real intent.
- Sunrise ramp vs Night mode / auto-brightness: the ramp writes brightness every 10 s for 30 min. If `:feature:clock` / `:feature:system` also drive brightness from the light sensor, they should back off while `services.alarms.phase` is `Sunrise` or `Ringing`.

## Needs from the user
- A reboot test: schedule an alarm (debug receiver), reboot the Echo, check it still rings. Wireless adb may not come back on its own after a reboot.

## Device setup
- None so far (no special grants needed).
