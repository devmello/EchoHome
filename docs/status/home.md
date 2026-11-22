# Home + glances (agent F)

Branch `worktree-agent-aa3a7577325744d96`, module `android/feature/home`.

The module works with **no Home Assistant and no API keys**. Weather, calendar, commute and stocks all use free, keyless sources. Home Assistant is optional: it only adds the Home screen (tiles, room switcher) and the doorbell.

## Done

- **Weather provider** (`services.weather`, `glance/OpenMeteo.kt`):
  - Uses Open-Meteo for current conditions, 24 hourly steps, today's high and low, the next sunrise and sunset, and precipitation chance.
  - Builds the "Rain tomorrow from 9 AM" summary.
  - **Location** (`glance/Location.kt`) works out of the box, with this precedence:
    1. Manual: `weather.lat`, `weather.lon`, `weather.place`.
    2. Auto-detected from the IP address.
    3. Built-in fallback: **Livermore, CA (37.6819, −121.7680)**.
  - **Auto-detection:**
    - Uses keyless HTTPS IP geolocation: `ipapi.co/json/`, falling back to `ipwho.is/`. Each is a bare GET with the generic User-Agent: no query, no device name, no user data.
    - The place name is the response's city. If the city is missing, Nominatim reverse-geocodes it at town level.
    - Results are stored under separate keys (`weather.auto.lat`, `.lon`, `.place`, `.at`), so they never overwrite manual values.
    - Looks up at most once a day, plus when the network comes back as a different network (ConnectivityManager callback, no polling). Even a forced lookup is rate-limited to one per 10 min.
    - A failed lookup keeps the last cached auto value. With no cached value, Livermore is used.
    - **Sanity check:** a result more than 150 km from the cached auto location (VPN, carrier routing) is held back once in `weather.auto.pending`. It's accepted only if the next lookup lands near it again.
  - Demo mode with no manual location keeps the design's sample weather. Release never shows "not configured".
  - The commute's home location defaults to the same place, unless `commute.lat` and `commute.lon` are set.
  - Picks °F or °C from the locale (US, LR, MM and a few others get °F). The setting `weather.unit=F|C` overrides this.
  - Refreshes every 30 min. After a failure it retries in 5 min and keeps the last good forecast.
  - Restarts once when the `weather.*` settings change (debounced 400 ms).
  - It is always bound and doesn't depend on HA.
- **Calendar provider** (`services.calendar`, `glance/Ics.kt` + `IcsCalendarProvider.kt`):
  - Fetches each URL in `secrets.calendarIcsUrls` (`webcal://` is treated as `https://`) every 15 min. Uses ETag / If-Modified-Since, so an unchanged feed costs a 304.
  - Streams the download to `cacheDir/ics/` and parses it from there. The cache is used at start, so the calendar works offline after a reboot.
  - Expands today and tomorrow, and again at midnight.
  - One colour per calendar: `calendar.color.<n>` (`#RRGGBB`), otherwise the design palette.
  - URLs are never logged, and cache files are named by a hash of the URL.
  - Own RFC 5545 parser:
    - Line unfolding, escapes, VALARM nesting, X-WR-CALNAME.
    - DTSTART/DTEND/DURATION with UTC, TZID (IANA, Windows names such as "Pacific Standard Time", and the Mozilla prefix), floating time and all-day DATE.
    - RRULE: DAILY, WEEKLY, MONTHLY and YEARLY, with INTERVAL, COUNT, UNTIL, BYDAY (including 2TU and -1FR), BYMONTHDAY, BYMONTH and WKST.
    - EXDATE, RECURRENCE-ID overrides and STATUS:CANCELLED.
    - Rules running since long ago skip ahead instead of walking every occurrence, and expansion stops at 5000.
    - A month that has no 31st skips the event rather than moving it.
- **Commute screen** (`commute`, order 40):
  - Picks the next timed calendar event with a physical location in the next 4 h. All-day, virtual (Zoom, Meet, Teams, URLs) and past events are skipped.
  - Otherwise it uses an optional weekday default trip (`commute.destination` plus `commute.arriveBy`).
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
- **Unit tests**: 43 JVM tests in `src/test`, all passing.
  - Location: precedence (manual > auto > Livermore; demo), the daily cache, failure keeping the cache, failure with no cache → Livermore, forced-lookup rate limit, the 150 km hold-back and its repeat, the ipapi.co → ipwho.is fallback with reverse geocoding, and the release provider fetching Livermore → the auto place after a network change → the manual place.
  - ICS: zones, folding, all-day, weekly BYDAY+COUNT+EXDATE, daily UNTIL across DST, monthly 2TU / -1FR / 31st, overrides, skip-ahead.
  - Home model: registry and room grouping, tile mapping, presenter, SVG path parser.
  - HomeController: optimistic → confirmed, rollback with error, debounced thermostat, brightness 0 → off.
  - Open-Meteo: parsing, and the provider against MockWebServer (mirrors the fake, then fetches once after 3 setting changes).
  - iCal provider: fetch, cache, a 304 with the ETag, offline start from the cache.
  - Commute: maths, OSRM parsing and road names, the Nominatim cache including cached misses.
  - Markets: 2026 holidays and early closes, phases and headlines.
  - Yahoo: parsing, and the source against MockWebServer.
  - Doorbell end to end: the real `HomeAssistantClient` against a MockWebServer "HA" over WebSocket plus HTTP camera_proxy. Checks the banner, the activity, repeated snapshots, and Unlock → `call_service lock.unlock`.

## Left

- A settings screen for location, commute and watchlist (today: the `home.set` adb extra, see Device setup).
- Room/slot editing UI for the HA Home screen (today: the `home.room.*` settings).
- The doorbell camera feed is snapshots, not live video. Talk depends on an HA script.

## Measured on the Echo

MEASURED

## Screenshots

SHOTS

## Open questions

- **Yahoo's chart endpoint is unofficial.** It works with no key today, but it isn't a documented API and can change or rate-limit without notice. That's why `QuoteSource` is swappable. No other keyless source I tried worked: Stooq now returns "page does not exist".
- **Traffic:** there is no free, keyless live-traffic source, so the commute leaves out the traffic chip and the "+N min" delay. It shows typical drive times and says "no live traffic" in its credit line.
- The weather provider is always bound, wrapping the fake until a location is set. Other screens (clock, standby) get real weather as soon as `weather.lat`/`lon` are set, without HA.
- `ui/SvgPath.kt` (the SVG path parser for design icons) could move to `:core:design` if other features want the design's icons. I haven't requested this.

## Data sources and terms

| Source | Used for | Key | Terms / how we comply |
|---|---|---|---|
| Open-Meteo `api.open-meteo.com/v1/forecast` | Weather, rain chips | none | Free for non-commercial use, < 10 000 calls/day; data CC BY 4.0 ("Weather data by Open-Meteo.com"). We make 1 call / 30 min (48/day). Coordinates rounded to 3 decimals (~100 m). |
| ipapi.co `/json/`, fallback ipwho.is | Approximate location (IP) | none | Free tiers: ipapi.co ~1000/day, ipwho.is non-commercial use. We make at most 1 lookup/day plus 1 after a network change (≥ 10 min apart). Bare GET: no query parameters, no device name, no user data. Only lat/lon/city are kept. |
| Your iCal URLs (`calendar.icsUrls`) | Calendar | the URL itself | The URL is a secret: kept in secrets.properties, never logged, cache files hashed. Fetched every 15 min with conditional GET. |
| Nominatim `nominatim.openstreetmap.org` | Geocoding event locations | none | [Usage policy](https://operations.osmfoundation.org/policies/nominatim/): ≤ 1 req/s, identifying User-Agent, cache results, no bulk use. We send one request at a time ≥ 1.1 s apart, `User-Agent: EchoBedside/0.1 (personal bedside display; Android)`, and cache every result, misses included, in settings forever. Only the event's location text is sent: no title, name or home address. |
| OSRM (FOSSGIS) `routing.openstreetmap.de/routed-car` | Drive time and route | none | Public demo server for light, non-commercial use with an identifying User-Agent. We make one request per destination per 30 min, only while Commute is shown. © OpenStreetMap contributors (shown on screen). No traffic. |
| Yahoo Finance chart `query1.finance.yahoo.com/v8/finance/chart` | Stocks | none | Unofficial. Yahoo's terms allow personal, non-commercial use, and quotes may be delayed. We make 5 requests every 5 min, only while Stocks is shown and the market is open. |
| Home Assistant (optional) | Home screen, doorbell | `ha.url` + `ha.token` | Your own server. |

## Needs from the user

1. **Location: nothing needed.** It's detected from the IP address, with Livermore as the fallback. Set it manually only if the IP location is wrong, e.g. `weather.lat=37.68;weather.lon=-121.77;weather.place=Livermore`. A manual value always wins. The commute uses the same point as "home" unless `commute.lat` and `commute.lon` are set.
2. **iCal URLs** in `android/secrets.properties` → `calendar.icsUrls=` (comma-separated; e.g. Google Calendar → Settings → "Secret address in iCal format"; iCloud public calendar link).
3. Optional, for the commute:
   - `commute.destination` + `commute.arriveBy` (e.g. `Scotts Valley, CA` / `09:00`) and `commute.label` (`work`): a weekday trip shown when no calendar event has a location.
   - `commute.eventHours` (default 4) and `commute.bufferMin` (default 5).
4. Optional: `stocks.symbols` (up to 5, default `AAPL,NVDA,TSLA,MSFT,AMZN`).
5. Optional, for the Home screen and doorbell:
   - `ha.url` + `ha.token` (a long-lived access token) in secrets.properties.
   - If auto-detection picks the wrong entities: `home.doorbell.entity`, `home.doorbell.camera`, `home.doorbell.lock`, and `home.doorbell.talk` (a script for two-way talk).

## Device setup

There's no settings screen yet. Settings are set with the launch intent: `home.set` takes `key=value` pairs separated by `;`, accepts only the `home.`, `weather.`, `calendar.color.`, `commute.` and `stocks.` keys, and removes a key when its value is empty.

```sh
./platform-tools/adb -s "$(cat .echo-serial)" shell am start -n com.echobedside/.MainActivity \
  --es screen commute \
  --es home.set "'weather.lat=36.97;weather.lon=-122.03;weather.place=Santa Cruz;commute.destination=Scotts Valley, CA;commute.arriveBy=09:00;commute.label=work'"
```

Screens: `--es screen home|commute|stocks`. Doorbell demo: `--es screen home --es doorbell ring`.
