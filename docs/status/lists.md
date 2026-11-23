# Reminders + lists (`:feature:lists`)

There's no board in `design/` for this. The screen is built from the glass design system (Timers' gradient and glass, Island pills).

## Done
- **Contracts** (`:core:model`):
  - `Reminders.kt`: `Reminder` (PENDING / DUE / MISSED / DONE, optional `profileId`, `snoozes`), `ReminderStore`, `ListItem`, `ListStore`, and in-memory defaults on `services.reminders` / `services.lists`.
  - `ListText`: de-dup keys (case, leading quantity, "a dozen", plurals). Re-adding an item says it's already there and un-checks it; "2 lemons" after "lemons" updates the text.
  - `ListNames`: groceries → shopping, to-do variants → todo.
  - `ReminderTime`: a pure parser and formatter.
  - Island: `ActivityKind.REMINDER` (alert) / `REMINDER_MISSED` and `LiveContent.Reminder`. The Profile model is unchanged.
- **Time parsing** (`ReminderTime.parse`):
  - Relative: "in 20 minutes", "in an hour and a half", "half an hour from now", days, weeks.
  - Days and dates: tomorrow, the day after tomorrow, tonight (20:00), this morning / afternoon / evening, weekdays, "Dec 3", "the 5th", ISO dates.
  - Times: noon / midnight, "7pm", "7:30", "at 7" (the next 7 o'clock), "19:00".
  - Parts of day: morning 8:00, afternoon 14:00, evening 18:00, night 20:00. A day with no time is 9:00.
  - It also takes the reminder text out of the phrase ("remind me to …").
  - `ReminderTime.at` accepts what the model sends (ISO local date-time, "HH:mm") and falls back to the parser.
- **Engine** (`engine/ReminderEngine.kt`, pure and unit-tested):
  - PENDING → DUE at the due time. DUE → MISSED after 5 min without an answer.
  - A reminder that fell due while the app or the Echo was off (more than 5 min ago) goes straight to MISSED with no chime.
  - Snooze = +10 min (back to PENDING). Done reminders are kept 24 h, missed ones 7 days.
  - Re-opening a past reminder makes it MISSED (no second chime).
- **`RealReminderStore`** → `services.reminders`:
  - Saved to `filesDir/reminders.json` (AtomicFile).
  - One exact `setExactAndAllowWhileIdle(RTC_WAKEUP)` for the next wake-up (action `app.vesper.reminders.DUE`).
  - `ReminderSystemReceiver` re-evaluates on BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED and MY_PACKAGE_REPLACED, and the feature re-evaluates at start.
- **Alerts** (`ReminderAlerts`):
  - A newly due reminder plays a synthesised three-note bell (1.6 s, USAGE_ALARM, `Chime.kt`) unless Do Not Disturb is on or the "Chime for reminders" setting is off. Volume is 0.55, or 0.18 in Night.
  - Banner `reminder:<id>` (Reminders, bell) with Done (primary) and Snooze 10 min. It's taken down when the reminder is no longer due.
  - Island `reminder:<id>` (REMINDER, Snooze 10 min / Done) while it's due. It's published with `alert = false` while the reminder has a banner (the banner is the alert; an alert activity would expand to 580×112 and repeat Done / Snooze under it), and `alert = true` only when there's no banner (due before the app started). The screen-off overlay still wakes for any REMINDER.
  - One `reminder:missed` (REMINDER_MISSED) with the count and the newest text: Done if there's one, Show (opens `lists`) if there are more. `ownerScreen` is `lists`.
- **Island rendering** (`:feature:system`):
  - Compact 250×44: bell plus text, "Missed: …" or "N missed".
  - Bead: bell.
  - Expanded 580×112: bell disc, title, "Reminder · 7:00 PM" or "Missed for Maya · 9:00 AM", and two pills.
- **`RealListStore`** → `services.lists`: saved to `filesDir/lists.json`. Any list name; at most 200 items per list.
- **Assistant tools** (`ListTools.kt`, plugged into `VesperTools`):
  - `add_reminder`: text, plus `at` (local ISO), `in_minutes` or `when` (natural phrase), and `for_everyone`. By default it belongs to the active person.
  - `list_reminders`, `update_reminder`, `complete_reminder`, `cancel_reminder`: by id, by text, or the only open one.
  - `list_add`, `list_remove`, `list_read`, `list_clear`: `list_clear` needs `confirmed`, and `only_checked` is optional.
  - Errors: NEEDS_TIME, PAST_TIME, BAD_TIME, TOO_FAR, NOT_FOUND, NEEDS_CONFIRMATION, NOTHING_TO_CHANGE, TOO_MANY.
  - Every tool returns a `ResultCard.Action` with the new REMINDER (blue bell) or LIST (green) icon.
  - `show_screen` knows `lists`. The system prompt says to ask when no time was given and to confirm before clearing a list.
  - Demo script `d` ("Remind me about the trash"). It isn't a chip, so the design's three chips stay.
- **Lists screen** (`lists`, pager order 35, after Timers):
  - Title and summary ("3 reminders · 1 missed · 4 to buy") over two glass cards: Reminders on the left, the list on the right.
  - The background is a cool gradient drawn once, with a pre-blurred backdrop. Nothing is allocated per frame, and it ticks once a minute while shown.
  - Tap a row to check it off. Hold a row to show a red Delete pill (horizontal swipes belong to the pager, so it's a hold, not a swipe).
  - Due and missed reminders get a Snooze pill. A person's initial dot shows whose reminder it is. "Clear N checked" sits under the list.
  - Tap the list title to cycle lists when there's more than one. Drag up and down to scroll.
  - Night: black background, red ink and the night glass.
- **Web settings page**: section "Reminders & lists" (order 45) has these settings:
  - "Chime for reminders" (on/off).
  - "Reminders" (TEXT_LIST) shows each open reminder as `Tue Nov 24 · 7:00 PM · Take out the trash`. Delete a row to cancel it; add "tomorrow 8am call the dentist" to create one. Editing a row's time or text replaces that reminder.
  - "Shopping list" (TEXT_LIST) shows the unchecked items, and you can add and remove them.
  - `WebLists` syncs both ways: changes from the store rewrite the setting, and edits on the page are diffed and applied. Commas become ";" because TEXT_LIST is comma-joined.
- **Tests**:
  - `:core:model`: ReminderTimeTest 10, ListTextTest 8.
  - `:feature:lists`: ReminderEngineTest 8 (including the persistence round trip), WebSyncTest 6.
  - `:feature:assistant`: ListToolsTest 7.
  - Existing tests pass, and `:app:assembleRelease` builds.

## Launch extras
- `--es screen lists`: the screen. Add `--es list todo` (or any list name) to choose the right card's list.
- These need a debug build (`services.config.debug`):
  - `--es screen lists --es seed 1`: sample reminders (trash 7 PM, dentist tomorrow 9, plants in 2 days) and a shopping list (Milk, 2 lemons, Eggs, Sourdough bread, Basil, Coffee beans checked). Only what's missing is added.
  - `--es screen lists --es reminder due`: adds "Take out the trash" due in 1.5 s and goes home. You get the chime, the banner and the island over StandBy.
  - `--es screen lists --es reminder missed`: adds "Call the dentist" due 20 min ago and goes home. It turns MISSED silently, so you see the missed island.
- `--es screen assistant --es script d`: the reminder demo turn.

## Left
- A StandBy widget for the next reminder. `WidgetKind` is a closed enum in `:feature:clock`, so this needs that module's owner.
- Repeating reminders ("every Monday") and location reminders aren't supported. The tools say so through BAD_TIME or the model's reply.

## Not verified on the Echo
- Everything on the device: the screen's look and touch handling, the chime's loudness on USAGE_ALARM, and the island and banner together (the island stays compact while the banner shows; fixed Nov 23, unit-tested in `ReminderAlertsTest`).
- The exact alarm firing in Doze and after a reboot.
- The web page's TEXT_LIST round trip.
- Memory and CPU (no `echo-perf.sh` run).
