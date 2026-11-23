# Settings on your phone (agent K, `:feature:settings`)

The Echo serves a settings page on the home Wi-Fi. Open it in Safari on an iPhone or Mac, tap **Pair**, type the 6-digit code the Echo shows, and every change applies on the Echo right away.

## For the user: how to use it
1. On the Echo, open **Control Center › Settings** (or `adb shell am start -n app.vesper/.MainActivity --es screen settings-pair`). A sheet shows a QR code and the address, e.g. `192.168.10.136:8080`.
2. Scan the QR code with the iPhone camera, or type the address into Safari on any phone or Mac on the same Wi-Fi.
3. Tap **Pair**. A 6-digit code appears on the Echo for 2 minutes. Type it on the page.
4. Optional: in Safari, **Share › Add to Home Screen** for an app icon. On older iOS versions the home-screen app keeps its own cookies, so it may ask you to pair once more.
5. **General › Paired browsers** lists every paired browser with **Forget**. Forgetting one signs it out at once, including open pages.

Keys (Anthropic, calendar links, Immich, Home Assistant) go in **Accounts & keys**. You can set or clear them, but they are never shown again.

## Nov 23: first-run setup (screen `onboarding`)

A paged, skippable setup for a fresh Echo. Code: `feature/settings/.../setup/` (`SetupFeature`, `SetupModel.kt`, `OnboardingView`, `SetupArt.kt`). It's in `:feature:settings` (not a new module) because the phone step reuses the running settings server's address, QR encoder and pairing.

### When it shows
- `SetupFeature` is **first in `FeatureList`** (`:app`). At install it reads the settings store's SharedPreferences (`echo_settings`): no keys at all = fresh install. It writes `setup.done` (BOOLEAN) right away: `false` on a fresh install, `true` on an Echo that already has settings, so the user's Echo is never interrupted and the answer never flips on its own later (other features write keys during their first start).
- Fresh install: the setup opens over StandBy on the first launch of the HOME activity (not when the launch intent names a `screen`, nor when a second intent opened a screen before the posted open ran, e.g. `am start --es screen X` right after the system started HOME by itself), and on every start until Done or "Skip setup".
- **Another screen opens** (a later `--es screen lamp`, an alarm ringing, a banner tap): the shell closes the setup and shows that screen; `setup.done` doesn't change. The **first-run** setup (`setup.done=false`) comes back at the step it was on as soon as the Echo is back at StandBy (`SetupResume`; face enrolment doesn't count as leaving). A **review** on a set-up Echo stays closed. Decided this way because the first-run setup has to be finished or skipped, but must never hide an alarm or a screen someone asked for.
- **Run setup again:** General › "Run setup again" (ACTION `setup.runAgain`) on the settings page sets `setup.done=false` and opens it at Welcome.
- Review on the Echo without touching `setup.done`: `--es screen onboarding` (Welcome), `--es screen onboarding --es step <n>` with `n` = 1..8 or `welcome|wifi|name|location|phone|people|clock|done`. Only Done and "Skip setup" write `setup.done=true`.

### The steps (all optional except Welcome and Done)
| # | Step | What it writes / does |
|---|---|---|
| 1 | Welcome to EchoHome | Big type over the wallpaper, a 0.9 s rise and fade. "Get started", or "Skip setup" (marks done). |
| 2 | Wi-Fi | Shows on / connected / the SSID (Android hides the SSID without the location permission, so it may just say "Connected to the internet"). "Choose network" opens `Settings.Panel.ACTION_WIFI` (fallback `ACTION_WIFI_SETTINGS`), as Control Center does. |
| 3 | Name this Echo | `device.name` (`DeviceName.KEY`). Tap presets: Echo Show, Kitchen, Bedroom, Living Room, Office, Nursery; "Other…" opens a text dialog. AirPlay / mDNS follow live. |
| 4 | Location | Shows `services.location` (auto / manual / fallback / demo). `weather.unit` "F"/"C" (same key and default rule as `:feature:home`). 12/24 h writes Android's `Settings.System.TIME_12_24`, which every face already reads through `DateFormat.is24HourFormat` (faces pick it up on the next minute tick). |
| 5 | Your phone | QR + address of the settings page (`WebSettings.address`), paired-phone count; Pair shows the usual code overlay. Calendars, the Anthropic key and the rest are set there. |
| 6 | People | Name (dialog), colour (8 swatches), "Add my face" → `services.profiles.upsert(Profile(...))` then `profiles-enrol` with `profile=<id>` and `return=onboarding`, so enrolment comes back to the setup. Asks for CAMERA first if needed. A name without a face is saved on Next. "Add someone else" after the first. |
| 7 | Clock and Night | Live, scaled-down preview of the real face (`FaceSpec.factory`, only while this step is shown), tap or arrows to change: `clock.face` (and the new people's `faceId`). Night mode: `clock.night.mode` auto / schedule / off (the schedule text comes from `clock.night.start|end`). |
| 8 | Done | Three tour cards: swipe sideways, pull down for Control Center, the assistant (says "Hey Nova" only when `assistant.wakeWord` is on and an Anthropic key is set). "Done" marks the setup done and goes to StandBy. |

Back / Next (the tinted button says "Skip" on an optional step you haven't done), page dots, horizontal flings, and the Back key all move between steps. No keyboard except the two name dialogs.

### Look and cost
- One glass sheet (48,40 → 912,400, radius 40) and a glass bar (Back, page dots, tinted Next) over a code-drawn wallpaper (five soft colour fields, RGB_565, 1114×480), pre-blurred once for the glass. Each step shifts the wallpaper 22 px, so the light drifts as you go. Content is plain fills on the sheet (no glass on glass); only Next is tinted.
- Nothing draws at rest. A step change is one 0.45 s spring (slide 44 px + fade). The wallpaper (~1.1 MB + a 140 KB blurred copy) is freed whenever the setup isn't on screen; the face preview exists only on step 7. Wi-Fi / time-tick receivers only while shown. The island is hidden on this screen (`hideOnScreen`).
- Night mode: black background, no wallpaper, red outline glass, every colour mapped to #BE180E.

### Tests
`setup/SetupTest.kt`, 18 JVM tests: fresh vs existing install, the stored answer winning over later keys, Done / Run again, text-stored values, step order and bounds, Skip / Next / Done labels, skip bookkeeping, `--es step` parsing, name cleaning, °F/°C rule, Night modes, schedule text, face index, the shared keys.

### Not verified (no device time for this branch)
- Nothing here has run on the Echo: layout, the face preview's scale and clipping, the Wi-Fi panel, whether `Settings.System.TIME_12_24` is writable for the priv-app (it shows "Couldn't change it" if not), the camera permission round trip, return from enrolment, Night mode styling.
- Text widths were checked against Inter at regular weight on the Mac (bold estimated); long SSIDs, names and city names are ellipsized.

### For the lead
- Merge note: `:app` `FeatureList` now starts with `SetupFeature` (import `app.vesper.feature.settings.setup.SetupFeature`). `EnrolView` (`:feature:camera`) takes an optional `return` arg. Settings manifest adds `ACCESS_WIFI_STATE` and `WRITE_SETTINGS` (already granted through `:feature:system`).
- Contract request filed: `SettingsStore.keys()` so the gate doesn't read the prefs file by name.

## Nov 22 evening: phone remote, people's calendars, alarm fields, diagnostics

The page now has two tabs under the title: **Remote** (default, remembered) and **Settings**.

- **Remote tab** (`api/RemoteApi.kt`, `GET /api/remote` polled every 2 s only while the page is visible and on this tab; timers and the progress bar count down locally each second between polls):
  - Now playing: title, artist, source, progress, previous / play-pause / next over `services.media.current.controls` (buttons dim when `canSkip` / `canPause` say so; 409 when nothing plays).
  - Volume and brightness sliders (throttled to one request per 140 ms while dragging; a poll never fights a finger), Do Not Disturb switch, Night mode Auto / On / Off, via `services.systemControls` (`POST /api/remote/system`, all or nothing).
  - Timers: list with live remaining, Pause / Resume / +1 min / Cancel / Dismiss, presets 1, 5, 10, 15 min, custom minutes (1 to 1440) with a label (`POST /api/remote/timers[/<id>]`, 409 when 3 are running).
  - Next alarm with `NextAlarm.reason`.
  - **Send a note to the Echo:** `POST /api/remote/note` posts a `Banner` (appName "Note", glyph "message", title "Note", green tile, not critical so DND holds it). 1 to 280 characters, at most 20 per minute (429).
  - Every store call runs on the main thread (`onMain`, same marshalling as alarms); state reads don't hop.
- **People** section (Settings tab, before Accounts & keys): a write-only multiline field per profile (the shared profile is left out, its links are the Accounts card's "Calendar links"). `PUT /api/people/<id>/calendar {urls: "…" | null}` stores the links in `services.secrets` under `Secrets.calendarKeyFor(id)`, one per line. Validation: https, http, webcal(s) only, at most 10 links, 2048 characters each, no control characters; error messages never echo the value. The page and `GET /api/people` only say Set / Not set; a Clear button removes it. A new `people` SSE event keeps other open pages in step.
- **Alarms card:** "Leave on time" switch with a "Getting ready" minutes field (0 to 180) and "Skip holidays" switch, in the editor and when adding. Header shows `NextAlarm.reason` under "Next alarm in …". `AlarmsApi` serialises and parses `leaveBy`, `readyMinutes`, `skipHolidays` itself (`AlarmJson` in `:core` doesn't carry them).
- **Diagnostics** section (last): `services.crashLog.entries` newest first (time, thread, Crash / Hang, summary, expandable stack, stack capped at 8000 characters) and a Clear button (`POST /api/diagnostics/clear`, with a confirm). On this branch the log is the empty one.
- **Security:** all new routes are dispatched after the existing pairing check (401 unpaired), and behind the same Host, Origin and JSON-only checks as every write. Secrets stay write-only. Nothing logs values.
- **Tests:** 20 new JVM tests (`RemoteApiTest`, plus two in `AlarmsApiTest`): every new endpoint 401 unpaired, foreign Origin 403, media transport, system validation all or nothing, timer lifecycle and limits, note banner fields and rate limit, calendar write-only (value never in any reply or the schema), calendar validation, diagnostics list and clear, leave-by fields. 80 tests pass (plus the opt-in DevServer); `:app:assembleDebug` passes.

### Checked
- Phone-width (390 px) browser against the dev server with sample data: Remote tab (now playing, sliders, timers, alarm, note), People, Diagnostics, alarm editor with the new switches; sent a note, moved the volume slider, saved and re-read a calendar link (no leak), no console errors.
- Echo: built and installed; unpaired requests to the new routes give 401 and a foreign Origin gives 403. Another agent installed a build right after mine, so the paired flows were not exercised on the device.

### Not verified
- Paired Remote tab against the real services on the Echo (media controls, timers, banner on screen, crash log). The user wasn't at the Echo to read the pairing code.
- The crash log is the empty one until the lead binds the real one in `:app`.

### Contract requests / for the lead
- `:feature:alarm` must persist `leaveBy`, `readyMinutes`, `skipHolidays`. `AlarmJson` (`:core`) does not write them, so if the alarm store serialises through it they are lost on restart. Suggest adding the three fields to `AlarmJson.toJson` / `fromJson` (the page already sends and reads them).
- Optional: `SettingSpec.placeholder` / `multiline` already exist; no new request for them.

## Done
- **Server:** a hand-written HTTP/1.1 server (`http/`, no Ktor or Netty) with one accept thread and a pool that grows to 12 threads and drops idle ones after 30 s. It uses keep-alive and server-sent events (SSE). **Port 8080:** port 80 isn't possible. Kernel 4.9 has no `ip_unprivileged_port_start`, and the app has no root, so binding below 1024 fails (tried in order 80, 8080, 8081, 8088).
- **LAN only:** requests must come from a private, link-local, loopback or ULA source address. The Host header must be an IP or a local name (blocks DNS rebinding). Writes need a same-origin `Origin` header and a JSON body. Responses carry a CSP, X-Frame-Options DENY and nosniff.
- **Pairing:** a 6-digit code from SecureRandom, valid for 120 s. It's bound to the browser that asked (`pairId`), allows 5 tries per code, and a new code at most every 3 s. After 15 failures in 10 minutes, pairing locks for 10 minutes. The session token is 32 random bytes in an HttpOnly, SameSite=Lax cookie `vesper_session`, and only its SHA-256 is stored (prefs `settings_web`). Values are never logged.
- **API:** `GET /api/schema`, `PUT /api/settings` (a batch of keys, all validated first, unknown keys → 400, bad values → 422), `POST /api/action/<key>`, `GET /api/events` (SSE: `change`, `schema`, `pairs`, `alarms`; heartbeat every 25 s only while a client is connected), `GET/PUT/POST/DELETE /api/alarms[/<id>]` over `services.alarms` (strict validation, unknown fields → 422), `/api/pair/start`, `/api/pair`, `/api/pairs`, `DELETE /api/pairs/<id>`, `/api/logout`.
- **How each type is stored:** BOOLEAN putBoolean · INT putInt · FLOAT putFloat · TEXT/CHOICE/TIME/COLOR putString · TEXT_LIST joined with commas · LOCATION `"lat,lon"` plus `<key>.place` · SECRET in the SecretStore. If a feature already stored a key as another type, the page reads and writes that type. COLOR and LOCATION accept empty/null to go back to the feature's default (e.g. `calendar.color.<n>` → the default palette).
- **Secrets are write-only:** the schema only says `set`, `builtin` (compiled into `secrets.properties`) or `unset`. No reply, schema or SSE event contains a value.
- **Web app** (`assets/settings-web/`, no build step, no CDN): ~70 KB, about 16 KB gzipped without the icon. It builds the page from the schema, with a control for every SettingType. Liquid Glass styling with dark, light and auto themes and a sticky frosted header. Sized for a 390 px iPhone, also works on a Mac. It saves as you go and syncs live between open pages. The Advanced switch shows advanced settings. It has apple-mobile-web-app tags, an icon and a manifest.
- **Alarms card** (iOS Clock style): add, edit and delete alarms; time, repeat days, label, on/off, sunrise light; the sound (global `alarm.sound`); a "Next alarm in …" header.
- **Sections I own:** General (order 0) has the device name, Restart app and Paired browsers. Accounts & keys (order 90) has the 7 `Secrets.KEYS` entries with help text. Home Assistant applies after Restart app.
- **On the Echo:** screen `settings-pair` (`order = 95`, not in the pager). It shows the QR code from my own encoder (verified with CoreImage) and the URL. Overlay `settings-code` (z 650) shows the code and countdown, then "Paired". Both follow Night mode. B's Control Center button already opens `settings-pair`.
- **mDNS:** `_http._tcp` is advertised under `DeviceName.get()` ("Echo Show") and re-registered when the name changes.
- **Tests:** 61 JVM tests in 6 classes plus an opt-in `DevServer`: HTTP parsing and limits, schema JSON, validation per type, pairing and rate limits, token hashing, secrets never serialised, unknown keys rejected, SSE framing, alarms API, QR.

## Checked on the Echo (Nov 22, build rebased on main a8c903f)
All checks used curl from the Mac to `http://192.168.10.136:8080` (scripts in the session scratchpad):
- The server starts with the app: `listening on :8080`, then `mDNS _http._tcp registered as Echo Show :8080`.
- Unpaired: `/api/hello` 200, `/api/schema` 401, a foreign Host header 403, `/api/alarms` 401, a write from a foreign Origin 403.
- Pairing: I read the code off the Echo's screen (screencap + OCR). A wrong code gave 403; the right code gave 200 and `Set-Cookie: vesper_session=…; HttpOnly; SameSite=Lax`. The overlay then showed "Paired". The cookie still worked after reinstalling the app.
- **Live rename:** `PUT {"device.name":"Echo Show Vesper Test"}`. Within 4 s, `dns-sd -B _raop._tcp` showed `76A341AF9034@Echo Show Vesper Test` and `_http._tcp` showed `Echo Show Vesper Test`. The SSE stream got the `change` event. Renaming it back to "Echo Show" also worked.
- **Every section renders and saves** (18 sections from all merged features). For one setting per section I changed the value, read it back and restored it, and all passed: general, system.display, clock (TIME night keys present), system.notifications, airplay, media, weather (LOCATION `weather.location`), calendar (COLOR), system.indicators, phone, alarm, commute, stocks, home-assistant, assistant, camera, photos. Every type showed up and was exercised: ACTION, BOOLEAN, CHOICE, COLOR, FLOAT, INT, LOCATION, SECRET, TEXT, TEXT_LIST, TIME. A bad value of each type → 422. An unknown key → 400. `system.night.mode` is no longer in the schema, and writing it → 400.
- **Secrets:** all 7 were `unset`. For `anthropic.apiKey`, `calendar.icsUrls` (2 URLs, multiline), `immich.url`, `immich.apiKey` and `stocks.apiKey`, I wrote a dummy value (state `set`) and cleared it again (state `unset`). The value never appeared in the PUT reply, the schema or the SSE stream.
- **Alarms:** I created a disabled 11:30 weekend alarm, which got an id. A bad time → 422 and an unknown field → 422. Editing the label worked. Deleting it → 200, and the list was back to 0. Nothing could ring.
- **Restart app** (`POST /api/action/settings.restartApp`): the app was back about 2 s later with a new pid, the server answered, and the browser was still paired. Silent.
- Assets come gzipped from the Echo: `/` 1.2 KB, `app.js` 9.9 KB, `app.css` 4.7 KB.
- Found and fixed on the device: (1) the launcher crashed on start because `NsdServiceInfo().apply { port = port }` used the info's own port (0); (2) `calendar.color.*` could never go back to "default palette" once set (COLOR now accepts empty); (3) the code sheet was see-through over the pair sheet (it now has an almost opaque base).

## Measured on the Echo
- **Idle:** 2 settings threads (`settings-http-accept` parked in `accept()`, plus one pool thread until its 30 s timeout). **0 CPU ticks in 30 s.** No polling.
- **With an open page (SSE client):** 0 CPU ticks in 30 s (the heartbeat is one 3-byte write every 25 s).
- **Whole app PSS:** 71.7 MB right after the checks above (budget < 200 MB). The settings code is all plain JVM, so the server's own heap is well under 1 MB.

## Screenshots (`docs/status/shots/settings/`)
- Echo, 960×480: `pair-sheet.png` (QR + URL), `code-overlay.png` (6-digit code, countdown), `code-paired.png`.
- iPhone width (390 pt @2x), from the localhost dev server with sample data: `web-pair.png`, `web-dark-1/2/3.png` (General, Clock with Night TIME keys, Alarms, Weather), `web-alarm-editor.png`, `web-accounts.png` (secrets: Set / Not set / Built in), `web-light-1/2.png`.
- Previews: `SETTINGS_DEVSERVER=8099 ./gradlew :feature:settings:testDebugUnitTest --tests '*DevServer*'`, then open `http://127.0.0.1:8099/dev-login`.

## Plain HTTP: the trade-off
The page uses plain HTTP on the LAN, because a local device can't get a certificate Safari trusts without a warning page. So anyone already on your Wi-Fi could, in principle, read a key while you type it in, or copy the session cookie. Mitigations: LAN-only source check, pairing with a code shown on the Echo's screen, a hashed token you can revoke (Forget), Host and Origin checks, and secrets that never come back out. If that's not acceptable for an API key, put it in `secrets.properties` at build time instead (it then shows as "Built in").

## Open questions
- **No .local name of our own:** Android's mDNS daemon advertises the service at host **`Android.local`** (shared by every Android device on the network, and not settable without /system changes). So the QR code and the screen show the IP address. A DHCP reservation for the Echo keeps that address stable.

## Left
- Night-mode screenshot of the pair sheet (it renders red with no glass, untested on the device).

## Needs from the user
- Nothing required. Optional: a DHCP reservation for the Echo so the address on the QR code stays the same.

## Requests to the lead (docs/contract-requests.md)
- K · `SettingSpec.placeholder` / `multiline` (or a `SECRET_LIST` type) to replace the hints carried in `options`.
- K · optional `Alarm.sound` for a sound per alarm.
- (The Control Center → `settings-pair` button was already wired by B.)

## Nov 23: per-person preferences and alarm owners

- **People**: each person now shows their own Clock face, Wallpaper (Liquid Glass and the Widgets page), Night mode and Alarm sound as selects, with "Same as everyone" for the last three. The options come from the settings they override (`clock.face`, `clock.glass.wallpaper`, `clock.night.mode`, `alarm.sound`). `GET /api/people` adds `face`, `wallpaper`, `nightMode`, `alarmSound` ("" = Everyone's); `PUT /api/people/<id>/prefs` sets any of them, validated against those options (`RemoteDeps.schema`). The page gets a `people` event whenever a profile changes on the Echo (edit mode, the Profiles screen).
- **Alarms**: rows end with the owner's name; the editor has "Whose alarm" (Everyone or a person). `AlarmsApi` accepts `profileId` (`[a-z0-9-]{1,40}` or null).
- Tests: `RemoteApiTest.personPrefsListAndSet` / `personPrefsValidation`, `AlarmsApiTest.whoseAlarm`.
