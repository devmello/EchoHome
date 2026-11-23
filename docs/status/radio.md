# Status: radio (`:feature:radio`)

Nov 23, 2026. Branch `worktree-agent-a3022df661c0e67f5`, on main `9e452f4`. **Not run on the Echo yet**: built and JVM-tested only. The Echo stayed silent; nothing in this module plays by itself.

## Done

- **Directory: Radio Browser** (`RadioBrowser.kt`, api.radio-browser.info, free, keyless, community run), used the way its docs ask:
  - Servers: DNS lookup of `all.api.radio-browser.info`, each address reverse-resolved to its `*.api.radio-browser.info` name (HTTPS needs names), shuffled. Only if DNS gives no names: `https://all.api.radio-browser.info/json/servers`. A failed request (I/O error, 5xx, 429) moves to the next server, which then stays current. A 4xx doesn't fail over. When every server fails, the list is looked up again on the next request. No hard-coded server.
  - Every request sends `User-Agent: EchoHome/0.1 (smart display radio)` (also the stream and favicon requests).
  - Search: `/json/stations/search` with `name` / `tag` (whole tags only, `tagExact=true`: "jazz" not "christmas jazz") / `countrycode`, `order=clickcount&reverse=true&hidebroken=true`, limit 30–40. Stations with codecs MediaPlayer can't stream are dropped (MP3, AAC, AAC+, Ogg, Opus, FLAC and HLS stay). Results cached 10 min (24 queries).
  - A play (tap or voice) is reported once with `/json/url/{uuid}` (Radio Browser's popularity counter, counted once per IP per station per day). Setting `radio.reportPlays` turns it off.
  - `byUuid` exists for refreshing saved stations' stream URLs (`RadioLibrary.refresh`), not called on a schedule yet.
- **Playback** (`RadioPlayer.kt`): the platform `MediaPlayer`, no new dependency (the APK already had OkHttp). `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`. 20 s connect timeout, one reconnect if a stream ends, partial wake lock + Wi-Fi lock only while a stream is open.
- **Song titles (ICY)** (`IcyProxy.kt`, `Icy.kt`): MediaPlayer can't request or return ICY metadata, so Shoutcast / Icecast streams go through a loopback relay (`127.0.0.1:<random port>`, loopback only): OkHttp opens the stream with `Icy-MetaData: 1`, `IcyStripper` cuts the metadata blocks out and the audio passes through unchanged. `StreamTitle` is decoded as UTF-8, else Latin-1. Empty / "-" / advert titles count as no song. "Artist - Title" is split for Now Playing. If MediaPlayer fails through the relay, the same station is retried once straight from its URL (no titles then). HLS always goes direct. The relay also handles HTTPS streams through OkHttp.
- **Media source** (`RadioService.kt`): publishes `services.media` source `"radio"` as `MediaSourceKind.OTHER_APP` with `sourceLabel` = the station. That gives, with no change to `:feature:media` or `:feature:system`:
  - Now Playing: "Playing from <station>", the song as title / artist (else the station name and its genre · country), a generated cover (the station's favicon on a colour from its name, or its initials), elapsed time, no seek (duration 0). The route popover reads PLAYING FROM / <station> / chips "Internet radio", "MP3 · 128 kbps", country, with **Stop** = off and out of the hub.
  - The island's music activity and the Control Center media tile, with play / stop.
  - Next / previous = the next / previous favourite (`canSkip` only with 2+ favourites, or the recent list).
  - A stopped station stays on Now Playing (play resumes the stream) for 30 min, then leaves the hub.
- **One source at a time** (`RadioArbitration`, same rules as Spotify's): the radio requests `AUDIOFOCUS_GAIN` when it starts (AirPlay / Spotify / other apps pause), and pauses AirPlay, Bluetooth and Spotify sessions that are playing on the hub. If one of those starts while the radio plays, or focus is lost for good, the radio stops. A transient loss (call, alarm) mutes it; the assistant speaking ducks it (the framework ducks MediaPlayer). The iPhone's own player (AMS, `iphone`) isn't audio on the Echo, so it's left alone.
- **Favourites and recent** (`RadioLibrary.kt`): `files/radio.json` (temp file + rename), favourites newest first (max 40), recent max 12; a play refreshes a favourite's saved copy. Demo stations are never saved.
- **Radio screen** (`RadioScreen.kt`, `ScreenSpec("radio", order = 15)`, after Now Playing): two glass platters over a warm dark gradient (rendered once at 480×240, pre-blurred once), no animation, draws only on changes.
  - Left: cover, LIVE / CONNECTING / STOPPED / NOT PLAYING, genre, country, format, station name, current song (or the error), then favourite (♥), play / stop, next favourite. Empty: "Pick a station" + a voice hint.
  - Right: tabs Favourites / Recent / Genres (+ Results). Genres: 8 tiles (Jazz, Lo-fi, Classical, Ambient, News, Indie, Electronic, Local · <country>). Station rows: favicon or initials, name, genre · country · format, ♥, a red wave on the playing one. Vertical drag scrolls (the pager keeps horizontal swipes). Loading / "Can't reach Radio Browser" (tap to retry) / empty states.
  - Search capsule (top right) opens the keyboard (EditText over the capsule, IME search action): name, genre (filler words dropped: "some jazz radio" → `jazz`) and country (platform country names: "France", "uk") in parallel, merged.
  - Night: black, red ink, red-outline glass, cover multiplied red.
  - Favicons: fetched once per station (≤ 512 KB, decoded at 96 px, LRU 32).
- **Voice** (`feature/assistant/.../llm/RadioTools.kt`, two lines in `VesperTools.kt`): `play_radio(query)` (favourites / recent match first, then Radio Browser by name, by genre + country, by genre; "favourites" / "the radio" plays the first favourite or the last station) and `stop_radio`. Cards: Media (station, detail) / Action "Stopped". Through the new `services.radio: RadioControl` contract in `:core:model` (`Radio.kt`).
- **Settings** (section "Radio", order 32): `radio.reportPlays` (BOOLEAN, on), `radio.country` (TEXT, empty = system region; the Local genre). Both read at use time.
- **Demo** (`--ez demo true`, or offline in demo mode): 7 invented sample stations ("Harbor Lights FM", "Night Swim Jazz", …) with `demo:` URLs show as favourites / recent / genre results. Playing one is **silent and offline**: it just publishes a playing state with sample song titles that change every 45 s.
- **Tests** (`./gradlew :feature:radio:testDebugUnitTest`, 30 tests): station parsing (numbers as strings, HLS, bad URLs, own JSON round trip), search query + User-Agent against MockWebServer, failover and stickiness, no failover on 4xx, rediscovery after a total failure, the 10 min cache, click report + byUuid, `/json/servers` parsing (dedupe, foreign hosts dropped) and shuffling; favourites / recent persistence across instances, caps, corrupt file, demo not saved, name matching; ICY stripping at any chunk size, titles, Latin-1, splitting; the relay end to end (fake Icecast → proxy → client: audio byte-identical, titles, `icy-br`, request headers); arbitration; query helpers. Assistant: `RadioToolsTest` (4).

## Launch extras

- `--es screen radio` (in the pager). Args: `--es tab favourites|recent|genres`, `--es genre jazz|lofi|classical|ambient|news|indie|electronic|local`, `--es search "<text>"`, `--es demo 1` (plays a sample station **silently**: no audio, no network). With `--ez demo true` the lists show sample stations.
- Now Playing / island with a radio station: `--es screen radio --es demo 1`, then `--es screen nowplaying`.

## Left / unverified

- **Nothing heard yet.** MediaPlayer with real streams (MP3 / AAC+ / HLS), the relay with MediaPlayer's real HTTP stack (NuPlayer may open the URL twice or send a Range header; the relay answers 200 without a length each time and closes the previous upstream), ICY titles from real stations, wake / Wi-Fi locks, audio focus with AirPlay / Spotify / the assistant: all untested on the Echo.
- Memory: MediaPlayer's buffers + the relay (16 KB buffers) are expected to add a few MB while playing; not measured.
- The keyboard on the Echo (AOSP IME) over the search capsule: not seen; the screen doesn't resize around it.
- The Radio screen hasn't been compared with a design: there is no Radio design file. Layout follows the Lists screen's two-platter pattern.
- Favourites aren't on the web settings page.
- Now Playing will try LRCLIB lyrics for radio songs (no duration, so its search fallback); fine, but untested.

## Screenshots

None yet (no device run on this branch).
