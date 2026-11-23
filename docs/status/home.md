# Home + glances (agent F)

Branch `worktree-agent-aa3a7577325744d96`, module `android/feature/home`.

The module works with **no Home Assistant and no API keys**. Weather, calendar, commute and stocks all use free, keyless sources. Home Assistant is optional: it only adds the Home screen (tiles, room switcher) and the doorbell.

## Done

- **Weather provider** (`services.weather`, `glance/OpenMeteo.kt`):
  - Uses Open-Meteo for current conditions, 48 hourly steps (the contract asks for ≥ 36 h), today's high and low, the next sunrise and sunset, and precipitation chance.
  - Builds the "Rain tomorrow from 9 AM" summary.
  - **Location** (`glance/Location.kt`, bound to `services.location` as a `HomeLocation`) works out of the box, with this precedence:
    1. MANUAL: `weather.location` ("lat,lon", the settings page's LOCATION field) plus `weather.location.place`. The older `weather.lat`/`lon`/`place` keys are still read for one release.
    2. DEMO: Santa Cruz (the designs' sample), only while demo data is on and nothing is manual.
    3. AUTO: detected from the IP address.
    4. FALLBACK: **San Francisco, CA (37.7749, −122.4194)**.
  - Weather and commute both read `services.location`.
  - **Auto-detection:**
    - Uses keyless HTTPS IP geolocation: `ipapi.co/json/`, falling back to `ipwho.is/`. Each is a bare GET with the generic User-Agent: no query, no device name, no user data.
    - The place name is the response's city. If the city is missing, Nominatim reverse-geocodes it at town level.
    - Results are stored under separate keys (`weather.auto.lat`, `.lon`, `.place`, `.at`), so they never overwrite manual values.
    - Looks up at most once a day, plus when the network comes back as a different network (ConnectivityManager callback, no polling). Even a forced lookup is rate-limited to one per 10 min.
    - A failed lookup keeps the last cached auto value. With no cached value, San Francisco is used.
    - **Sanity check:** a result more than 150 km from the cached auto location (VPN, carrier routing) is held back once in `weather.auto.pending`. It's accepted only if the next lookup lands near it again.
  - While the location is DEMO, weather mirrors the fake (the design's sample weather). Release never shows "not configured".
  - The commute starts from the same place unless `commute.home` is set (advanced).
  - Picks °F or °C from the locale (US, LR, MM and a few others get °F). The `weather.unit` setting (Automatic / °F / °C) overrides this.
  - Refreshes every 30 min. After a failure it retries in 5 min and keeps the last good forecast.
  - Refetches when the location changes (debounced 400 ms) or the unit changes.
  - It is always bound and doesn't depend on HA.
- **Calendar provider** (`services.calendar`, `glance/Ics.kt` + `IcsCalendarProvider.kt`):
  - Always bound. Follows `services.configFlow`, so iCal URLs set or changed on the settings page take effect at once. With no URLs it mirrors the fake (the sample calendar in demo mode, empty otherwise).
  - Fetches each URL in `secrets.calendarIcsUrls` (`webcal://` is treated as `https://`) every 15 min. Uses ETag / If-Modified-Since, so an unchanged feed costs a 304.
  - Streams the download to `cacheDir/ics/` and parses it from there. The cache is used at start, so the calendar works offline after a reboot.
  - Expands today and tomorrow, and again at midnight.
  - One colour per calendar: `calendar.color.<n>` (`#RRGGBB`, applied live), otherwise the design palette.
  - URLs are never logged, and cache files are named by a hash of the URL.
  - Own RFC 5545 parser:
    - Line unfolding, escapes, VALARM nesting, X-WR-CALNAME.
    - DTSTART/DTEND/DURATION with UTC, TZID (IANA, Windows names such as "Pacific Standard Time", and the Mozilla prefix), floating time and all-day DATE.
    - RRULE: DAILY, WEEKLY, MONTHLY and YEARLY, with INTERVAL, COUNT, UNTIL, BYDAY (including 2TU and -1FR), BYMONTHDAY, BYMONTH and WKST.
    - EXDATE, RECURRENCE-ID overrides and STATUS:CANCELLED.
    - Rules running since long ago skip ahead instead of walking every occurrence, and expansion stops at 5000.
    - A month that has no 31st skips the event rather than moving it.
- **Commute screen** (`commute`, order 40):
  - "Where to" (`commute.source`):
    - `auto` (default): the next timed calendar event with a physical location in the next 4 h, otherwise the fixed destination.
    - `event`: calendar events only.
    - `fixed`: always the fixed destination.
  - All-day, virtual (Zoom, Meet, Teams, URLs) and past events are skipped.
  - The fixed destination is a weekday trip: `commute.destination`, plus `commute.arriveBy` (default 09:00).
  - Settings apply live.
  - Geocodes with Nominatim, then routes with OSRM (FOSSGIS) from the home location to the destination.
  - Shows "Leave in N min" (arriving 5 min early, set by `commute.bufferMin`), the drive time, the main road ("Hwy 17", "I-280"), the arrival time, up to 2 alternative routes and a rain chip from the forecast.
  - Draws the real route shape fitted into the design's map area.
  - Recomputes every minute, only while the screen is shown. Routes are cached for 30 min per destination.
  - **No traffic chip**: no free, keyless source gives live traffic, so OSRM times are typical free-flow times. The design's orange "Slow near Scotts Valley · +6 min" appears only in demo mode.
  - In demo mode with no manual location set, the screen shows the design's sample (18 min / 24 min via Hwy 17 / Soquel Dr 31 / Bus 35 41).
  - The old HA / Waze commute is gone.
- **Stocks screen** (`stocks`, order 50):
  - Quotes come from Yahoo Finance's public chart endpoint, with no key (`stocksApiKey` is not used).
  - Shows a hero chart of the featured symbol's day against the previous close, plus a 5-row watchlist with sparklines. Tapping a row features it.
  - A new watchlist from the settings page fetches at once.
  - Polls every 5 min only while the screen is shown and the market is open. It makes one fetch after the close, then sleeps until the next open.
  - NYSE hours and holidays are computed by rule: Good Friday from Easter, observed-date rules, a Saturday New Year's Day not observed, and the 1 pm early closes.
  - Closed headline: "MARKETS CLOSED · FRIDAY CLOSE".
  - The data source sits behind the swappable `QuoteSource` interface.
  - In demo mode it shows the design's sample prices. The footer says "Demo data" or "Yahoo Finance · quotes may be delayed".
- **Home screen** (`home`, order 20), registered only when `secrets.haConfigured`:
  - Areas come from the HA area, device and entity registries. Hidden, disabled and config/diagnostic entities are left out.
  - A room is an area with lights, switches, fans or input_booleans. A climate-only area counts only when the house has more than one thermostat.
  - Each room gets 5 slots: thermostat, lamp (the first dimmable light), toggles, 4 scenes, and lock/garage. Climate, scenes and secure fall back to house-wide entities.
  - Per-room overrides: `home.room.<area>.<slot>`. Room order: `home.rooms`.
  - Optimistic UI: the tile changes at once. It rolls back with "Couldn't change X" if HA refuses, or after 5 s if HA never reports a change.
  - Thermostat +/- taps are debounced into one `set_temperature` (700 ms). heat_cool modes send both ends.
  - The lamp tile drags in 5 % steps, and 0 % turns it off. The active scene is the most recently activated one.
  - Night mode uses the red outline and ink.
- **Demo Home**: in demo mode without HA, `home` is still registered with an in-memory home that matches the design (Bedroom / Living / Kitchen). It is marked "Demo data · connect Home Assistant for your home". Taps work and change the demo state.
- **Doorbell**, only with HA (or in the demo via a launch extra):
  - Watches the doorbell for a ring. It auto-detects a `binary_sensor` going off → on, or a new state on an `event.*` doorbell. `home.doorbell.entity` overrides the detection.
  - On a ring:
    - It posts a critical banner: "Someone’s at the door", "Show camera".
    - It shows a DOORBELL live activity with Talk (only if `home.doorbell.talk` names a script), Unlock and Dismiss.
    - It refreshes the `camera_proxy` snapshot every 3 s for 30 s, decoded at ≤ 480 px in RGB_565.
  - Repeat rings within 10 s are ignored.
  - Demo: `--es screen home --es doorbell ring`.
- **Settings page** (`HomeSettingsSchema`, registered with `host.registerSettings`). All settings apply live; secrets (iCal URLs, HA url/token) are declared by `:app`.
  - **Weather & location** (30): location (LOCATION), unit (CHOICE).
  - **Calendars** (35): colour per calendar (COLOR, advanced).
  - **Commute** (40): where to, destination, arrive by, name, arrive early by; advanced: look-ahead hours, start from (LOCATION).
  - **Stocks** (50): watchlist (TEXT_LIST).
  - **Home Assistant** (60): room order; advanced: doorbell entity, camera, lock, talk script.
- **Unit tests**: 46 JVM tests in `src/test`, all passing.
  - Location: precedence (manual > demo > auto > San Francisco), `weather.location` over the old keys, the resolver flow, the daily cache, failure keeping the cache, failure with no cache → San Francisco, forced-lookup rate limit, the 150 km hold-back and its repeat, the ipapi.co → ipwho.is fallback with reverse geocoding, and the release provider fetching San Francisco → the auto place after a network change → the manual place.
  - ICS: zones, folding, all-day, weekly BYDAY+COUNT+EXDATE, daily UNTIL across DST, monthly 2TU / -1FR / 31st, overrides, skip-ahead.
  - Home model: registry and room grouping, tile mapping, presenter, SVG path parser.
  - HomeController: optimistic → confirmed, rollback with error, debounced thermostat, brightness 0 → off.
  - Open-Meteo: parsing, and the provider against MockWebServer (mirrors the fake, then fetches once after 3 setting changes).
  - iCal provider: fetch, cache, a 304 with the ETag, offline start from the cache, and URLs changing live (fake → feed → fake).
  - Commute: maths, OSRM parsing and road names, the Nominatim cache including cached misses.
  - Markets: 2026 holidays and early closes, phases and headlines.
  - Yahoo: parsing, and the source against MockWebServer.
  - Doorbell end to end: the real `HomeAssistantClient` against a MockWebServer "HA" over WebSocket plus HTTP camera_proxy. Checks the banner, the activity, repeated snapshots, and Unlock → `call_service lock.unlock`.

## Left

- Room and slot editing UI for the HA Home screen. Today it's done through the `home.room.*` settings (room order is on the settings page).
- The doorbell camera feed is snapshots, not live video. Talk depends on an HA script.
- The doorbell's banner and island aren't checked on the device:
  - Agent B's island and banner renderer isn't on main yet.
  - Quiet hours (the user is asleep) rule out a doorbell test on the device.
  - The flow is covered end to end by `DoorbellTest` (the real HA client against a mock HA).
- HA screens are registered at install. If HA secrets are added later on the settings page, the Home screen appears after the next app start, because `:app` binds the HA client at startup.

## Measured on the Echo

Debug build, demo data, whole app (all features on main at b16fbb6 plus this branch), 2026-11-22 05:45–05:46. The run was silent: taps only, no sound.

| What was running | PSS total | CPU | Frames (p50 / p90 / p99) | Janky |
|---|---|---|---|---|
| Home: lamp drag 380→580 + 3 room switches (6 s) | 67.7 MB (Java 7.4, Native 14.0, Graphics 6.6) | 22 % | 13 / 32 / 77 ms (70 frames) | 11 % |
| Home: 4 toggle taps, 0.25 s fill animations (6 s) | 71.3 MB | 22 % | 14 / 31 / 46 ms (72 frames) | 32 % |
| Home idle (10 s) | 70.2 MB | 0.4 % | 1 frame | 0 |
| Stocks: 2 watchlist taps (6 s) | 73.8 MB | 5 % | 42 / 97 ms (3 frames) | 2 of 3 |
| Commute / Stocks idle (8 s each, earlier run) | 58.1 / 58.9 MB | 0.2 / 0.6 % | 0 frames | 0 |

How the Home screen got here, on the same lamp-drag + room-switch input:

| Version | Frame p50 / p90 | Janky |
|---|---|---|
| First build | 101 / 117 ms | 100 % |
| Glass shadows baked into the wallpaper once | 38 / 57 ms | 100 % |
| Hardware layer per tile (reverted: −6 ms, but +25 MB PSS) | 32 / 53 ms | 100 % |
| **Now**: shadows baked, tiles redraw only when their data changes, lamp drag stays local until release | **13 / 32 ms** | **11 %** |

- `GlassPainter` re-blurs its shadow every frame. That's filed in `docs/contract-requests.md`.
- No screen polls: idle CPU is under 1 %.
- Weather: 1 request / 30 min.
- IP location: at most 1 / day, plus 1 after a network change.
- Calendar: every 15 min, with a conditional GET.
- Stocks: only while the screen is shown and the market is open.
- Commute: only while the screen is shown.

## Screenshots

All in `docs/status/shots/home/`, demo data, taken on the Echo:

| Shot | Compared with | Result |
|---|---|---|
| `home.png` (Bedroom) | `design/Home.dc.html` | Matches the layout: thermostat 24,76 300×380 with the 135°+270° arc (#FF9F0A → #FF453A, 18/40 filled), lamp 340,76 with the amber fill, toggles, 4 scenes, lock and garage, and the switcher top right. The lamp shows 90 % because the measurement run dragged it (the design's sample is 60 %). There's a small demo note at the bottom. |
| `home-living.png` | the same design, Living selected | Room switch: the title "Living Room", the floor lamp at 35 %, Speakers and TV light. |
| `commute.png` | `design/Commute.dc.html` | Matches: "Leave in / 18 min", "24 min via Hwy 17 · arrive …" (real clock), the slow and rain chips, 2 alternative cards, the stylised map and route. |
| `stocks.png` | `design/Stocks.dc.html` | Matches: "MARKETS CLOSED · FRIDAY CLOSE" (it's Sunday), the NVDA hero chart, and the 5-row watchlist with NVDA featured. |

Fixed after comparing: the thermostat arc, lamp fill, commute route and stocks chart area were drawn at 16 % opacity, because the paint's alpha was scaling their gradients. The demo "TV backlight" name was shortened so it doesn't need an ellipsis.

## Open questions

- **Yahoo's chart endpoint is unofficial.** It works with no key today, but it isn't a documented API and can change or rate-limit without notice. That's why `QuoteSource` is swappable. No other keyless source I tried worked: Stooq now returns "page does not exist".
- **Traffic:** there is no free, keyless live-traffic source, so the commute leaves out the traffic chip and the "+N min" delay. It shows typical drive times and says "no live traffic" in its credit line.
- `ui/SvgPath.kt` (the SVG path parser for design icons) could move to `:core:design` if other features want the design's icons.
- Toggle taps still have 30 % janky frames (p90 31 ms). Each animated glass tile blends the full backdrop again. A cheaper glass recipe in `:core:design` would help every screen.

## Data sources and terms

| Source | Used for | Key | Terms / how we comply |
|---|---|---|---|
| Open-Meteo `api.open-meteo.com/v1/forecast` | Weather, rain chips | none | Free for non-commercial use, < 10 000 calls/day; data CC BY 4.0 ("Weather data by Open-Meteo.com"). We make 1 call / 30 min (48/day). Coordinates rounded to 3 decimals (~100 m). |
| ipapi.co `/json/`, fallback ipwho.is | Approximate location (IP) | none | Free tiers: ipapi.co ~1000/day, ipwho.is non-commercial use. We make at most 1 lookup/day plus 1 after a network change (≥ 10 min apart). Bare GET: no query parameters, no device name, no user data. Only lat/lon/city are kept. |
| Your iCal URLs (`calendar.icsUrls`) | Calendar | the URL itself | The URL is a secret: kept in secrets.properties, never logged, cache files hashed. Fetched every 15 min with conditional GET. |
| Nominatim `nominatim.openstreetmap.org` | Geocoding event locations | none | [Usage policy](https://operations.osmfoundation.org/policies/nominatim/): ≤ 1 req/s, identifying User-Agent, cache results, no bulk use. We send one request at a time ≥ 1.1 s apart, `User-Agent: EchoHome/0.1 (personal bedside display; Android)`, and cache every result, misses included, in settings forever. Only the event's location text is sent: no title, name or home address. |
| OSRM (FOSSGIS) `routing.openstreetmap.de/routed-car` | Drive time and route | none | Public demo server for light, non-commercial use with an identifying User-Agent. We make one request per destination per 30 min, only while Commute is shown. © OpenStreetMap contributors (shown on screen). No traffic. |
| Yahoo Finance chart `query1.finance.yahoo.com/v8/finance/chart` | Stocks | none | Unofficial. Yahoo's terms allow personal, non-commercial use, and quotes may be delayed. We make 5 requests every 5 min, only while Stocks is shown and the market is open. |
| Home Assistant (optional) | Home screen, doorbell | `ha.url` + `ha.token` | Your own server. |

## Needs from the user

Nothing is required: weather, location, commute and stocks all work with no settings or keys. All settings are on the web settings page.

1. **Location (optional):** detected from the IP address, with San Francisco as the fallback. Set Settings › Weather & location › Location only if the detected one is wrong. A manual value always wins.
2. **Calendars:** add your private iCal URLs under Secrets › `calendar.icsUrls` (comma-separated), e.g. Google Calendar's "Secret address in iCal format" or an iCloud public calendar link. They take effect at once.
3. **Commute (optional):** Settings › Commute: a destination and arrive-by time for weekdays. Calendar events with a place are used automatically.
4. **Stocks (optional):** Settings › Stocks › Watchlist (up to 5 symbols).
5. **Home Assistant (optional):** `ha.url` + `ha.token` under Secrets. These need an app restart, see Left. If auto-detection picks the wrong doorbell entities, set them under Settings › Home Assistant (advanced).

## Device setup

Nothing beyond installing the app. For debugging, the same settings can also be set with the launch intent: `home.set` takes `key=value` pairs separated by `;`. It accepts only the `home.`, `weather.`, `calendar.color.`, `commute.` and `stocks.` keys, and removes a key when its value is empty.

```sh
./platform-tools/adb -s "$(cat .echo-serial)" shell am start -n app.vesper/.MainActivity \
  --es screen commute \
  --es home.set "'weather.location=37.77,-122.42;weather.location.place=San Francisco;commute.destination=Oakland, CA'"
```

Screens: `--es screen home|commute|stocks`. Doorbell demo (makes no sound itself, but don't run it during quiet hours): `--es screen home --es doorbell ring`.

## Nov 22 evening batch (air/UV/pollen, nowcast, alerts, travel, brief, per-person calendars)

Done (JVM-tested; `:app:assembleDebug` passes):
- **Weather.air / Weather.nowcast** (`glance/OpenMeteoProvider.kt`, `OpenMeteo.kt`): minutely_15 precipitation rides the forecast call; a nowcast loop refreshes it every ~12 min only while something collects `weather`. Air quality from `air-quality-api.open-meteo.com` (`us_aqi`, `uv_index`, six `*_pollen`, daily `uv_index_max`; checked against the live API). Pollen map empty outside Europe. Air failure keeps the old value up to 3 h.
- **Rain soon** (`alerts/RainAlert.kt`): `WEATHER_ALERT`/`RainSoon` when rain starts within 30 min and it's dry now; updates each minute, ends at rain start or forecast drop (hysteresis 40 min); 2 h cool-down (persisted). Setting `weather.rainAlert`. Log tag `VesperRain`. Debug: `--es rain_demo 15` (`off` clears).
- **services.travel** (`commute/HomeTravel.kt`): Nominatim + OSRM via the commute code, 3 h cache, 5 min failure cache, serialised, null on failure.
- **Leave now** (`alerts/LeaveNow.kt`): start - travel - `commute.bufferMin`(5); shows `leave.leadMin`(15) before the leave time; ends at start or leave+10 min. Settings `leave.enabled`, `leave.leadMin`. Log tag `VesperLeave`. Debug: `--es leave_demo 30`.
- **Morning brief** (`brief/`): screen `brief` (not in pager). Triggers per `BriefRules`; closes on tap or 60 s. Setting `brief.enabled`. Debug: `--es screen brief --es brief demo` (`demo-day` forces the day palette). Screenshot: `docs/status/shots/home/brief.png`.
- **Per-person calendars**: `IcsCalendarProvider(personal = PersonalCalendars.flow(...))`; events get `profileId`; reloads on profile/secret changes.
- Settings section "Alerts & morning brief".

Echo: brief demo, 8 s: PSS 59 MB, 0 janky of 95 frames.

Open: rain/leave alerts not seen on the Echo (island renderer not on this branch; use `logcat -s VesperRain VesperLeave`); brief trigger on real presence/alarm only unit-tested; per-profile calendar secret fields (`calendar.icsUrls.<profileId>`) need exposing in the settings page; leave pill in the brief may ellipsize.
