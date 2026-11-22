# Photos (agent I) — `:feature:photos`

Immich photo frame, `Photos.dc.html`. Screen `photos`, pager order 70.

## Done

- **Screen** (`ui/PhotosScreen.kt`): full-bleed 960×480 photo, 1.4 s `ease` crossfade + 9 s linear slow zoom to 1.08 around a per-photo origin (design values), all as View `alpha`/`scaleX/Y` on two `PhotoLayer`s (no redraw per frame). Default interval 8 s (the design's `seconds` prop); setting `photos.seconds`.
- **Time + date** top-left (104 px / 600 / −0.045 em, tabular; 20 px / 600 / 90 %) and **memory caption** bottom-left (24 px / 700; 15 px / 600 / 82 %), with the design's text shadows and legibility scrim, via `SpecText`. Both bands are small hardware layers, re-rendered only on the minute / caption change. 12 h or 24 h from the system setting.
- **Caption**: place (Immich `exifInfo.city` → `state` → `country`), then "October 2025 · Immich · Family album". "N years ago today" on the anniversary, "N years ago this week" within ±3 days. "Offline" replaces "Immich" when playing from the cache.
- **Glass controls** on tap (album ▾, pause/play, next): `GlassLayout` capsule right 28 / bottom 26, 56 px, the design's .12 fill, lift in with opacity .3 s ease + translateY 16→0 / scale .9→1 .45 s `cubic-bezier(.3,1.4,.5,1)`, settle away 4 s after the last touch. Contents are plain fills (no glass on glass). The glass samples a pre-blurred copy of the current photo (`PreBlur.blur`, 18 px, sat 1.8, bright 1.1, made once per photo off the main thread), placed where the zoomed photo actually is.
- **Album picker sheet**: glass popover that grows out of the album button (spring), "Albums" heading, rows with check / name / count, current album on a .14 fill, scrolls past 6 rows, quiet status line (loading / offline / error). Choice saved in `photos.album.id` / `photos.album.name`. No artboard exists for it; it follows the system (26 px radius, 8 px padding, concentric 18 px rows). Default album: saved choice → "Family" → largest.
- **Immich client** (`data/ImmichClient.kt`, OkHttp, header `x-api-key`): `GET /api/albums`, `GET /api/albums/{id}?withoutAssets=false` (falls back to paging `POST /api/search/metadata {albumIds}` when the server leaves `assets` out), `GET /api/assets/{id}/thumbnail?size=preview`. Images only (no videos, trashed, archived, hidden/locked). Base URL with or without `/api`. The key is never logged or put in error messages.
- **Disk cache** (`data/PreviewCache.kt`): previews in `cacheDir/photos/previews`, LRU by last use (persisted as mtime), **150 MB cap**, atomic writes (temp + rename), corrupt files dropped and refetched. Album and asset lists saved as JSON (`MetaStore`) for offline start.
- **Decode** (`PhotoDecoder` + `DecodeMath`): bounds first, power-of-two `inSampleSize`, then one 960×480 ARGB_8888 frame (1.8 MB); intermediate recycled at once. Landscape fills (crop biased up); portrait / square photos are shown whole at full height over a blurred, darkened fill of themselves. ARGB_8888 rather than RGB_565: 565 bands visibly in skies and gradients and the saving is 0.9 MB per frame.
- **Prefetch + memory**: at most two decoded frames (current + next; during a crossfade, outgoing + incoming). The next photo is fetched/decoded after the crossfade ends, so the decode never competes with animation frames. The old frame is recycled after the fade.
- **Offline**: on a network error the slideshow switches to cached previews only ("Offline" in the caption) and retries online every 15 min (also on each full pass through the album, which reshuffles and picks up new photos). Start-up errors show one quiet line where the caption goes ("Can't reach Immich · retrying", "Immich didn't accept the API key", "No photos in Family") with backoff 30 s → 10 min.
- **Night mode** (`SystemState.nightActive`): photo drawn as red monochrome (#BE180E) at 0.42 via a `ColorMatrixColorFilter` (no extra pass), text red and dimmed, glass becomes the red outline. A tap brings it to full (red) brightness for 6 s.
- **Off screen**: `onScreenHidden` stops the clock tick, slideshow timer, prefetch, fade, zoom and hides the controls. As a pager neighbour it loads and shows the first photo still, so swiping in doesn't show black.
- **Demo mode** (`services.config.demoData`, no Immich configured): procedural stand-ins, Canvas ports of the design's CSS illustrations (coast at golden hour, granite valley, night-market lanterns) plus mirrored / hue-shifted / reseeded variants, in three sample albums (Family 4, Trips 5, Night walks 3). One is dated three years ago today to show the memory caption. No real or copyrighted photos.
- **Settings card** "Photo frame" (`PhotoSettings.kt`, `host.registerSettings`, order 70): Album (CHOICE of the listed albums with counts once Immich or demo answers; TEXT album name until then), Seconds per photo (INT 3–600, default 8), Shuffle (default on), Show after idle (minutes, INT 0–240, default 0 = off), Never at night (default on). Interval, shuffle and album apply live. Values are read tolerantly (string or typed prefs) so a type mismatch from the web page can't crash the screen.
- **Live source** (`PhotoLibrary.kt`): watches `services.configFlow` for `secrets.immichUrl` / `immichApiKey` / `demoData` and rebuilds the repository when they change (Immich when both are set, else demo, else nothing). The screen resets and restarts on the new source; the album list in the settings card is refreshed.
- **Show after idle** (`IdleWatcher.kt`, an invisible overlay + key handler): after N idle minutes on StandBy it opens the frame; with "Never at night" it won't open during Night mode and returns to the clock when night starts if it had opened the frame itself. Draws nothing, one Handler timer re-armed on input.
- **Legible glyphs on bright photos**: the capsule and sheet sample the pre-blurred backdrop under them (8×3 pixels, once per photo/show); above 0.68 relative luminance the glyphs switch from white to #1C1C1E. Found on the Lake Tahoe stand-in, where the saturated/brightened glass over snow is near white and white glyphs vanished. My own call; the design only shows dark photos under the capsule.
- **Tests** (34, JVM): Immich client against MockWebServer (auth header, parsing, filtering, search fallback + paging, preview download, HTTP errors without the key, URL normalisation, dates), cache eviction / LRU / restart / temp cleanup, metadata round trip, slideshow order / wrap / refresh / pause / timing / prefetch skipping failed and uncached, captions, decode math, settings section shape and show-after-idle rule.
- Debug args: `--es screen photos [--es album NAME] [--es seconds N] [--es controls 1] [--es sheet 1] [--es paused 1] [--es night 1|0]`.

## Left

- Not run against a real Immich server (none available yet). Everything server-side is tested against MockWebServer with Immich's documented response shapes; check on a real server: shared albums appear in `GET /api/albums`, and the preview format (JPEG or WebP, both decode).
- Album sheet has no artboard; worth a design pass.
- Show-after-idle checked by unit test only, not left idle on the device for N minutes.
- Per-surface glass recipe overrides (requested in contract-requests.md, deferred by the lead).

## Measured on the Echo

Debug build, demo album Family, 8 s interval (zoom always running), `build-install.sh --perf 20`:

| | |
|---|---|
| PSS | **50.0 MB** (Java 7.1, Native 14.6, Graphics 8.8); device MemAvailable 297 MB |
| CPU | 18.7 % of one core during crossfade + zoom |
| Frames | 1207 in 20 s (≈ 60 fps); gfxinfo p50 / p90 / p99 16 / 17 / 18 ms |
| Framestats medians | total CPU side 3.1 ms (UI 0.9, issue 1.2, dequeue 0.5); GPU completes ~16 ms after swap (pipelined) |
| PSS, later build (db70657, main b16fbb6 with more features in the app) | 79.9 MB total app after switching albums with the sheet; still under the 200 MB budget |
| Idle (paused, controls hidden) | **0 frames in 6 s, 0 CPU ticks in 10 s** |

gfxinfo calls 50 % "janky" because the GPU finishes a frame just inside the next vsync; frames are still delivered at 60 fps (1207/20 s) and nothing is dropped visibly. Ideas if it matters: longer interval (zoom ends, frame rests), or a 30 fps zoom.

## Screenshots (`docs/status/shots/photos/`)

Compared on screen with `Photos.dc.html` rendered at 960×480 in Chrome (same state: photo 1, controls shown).

- `photo.png` — Big Sur stand-in, time, date, caption. Matches the design: positions, sizes, weights, scrim and shadows line up; the photo is a few % into its zoom.
- `photo-valley.png` — photo 2 after a crossfade.
- `photo-lanterns.png` — Night walks album picked from the sheet (controls still up, white glyphs on the dark glass); lantern centres render clean.
- `controls.png` — glass capsule shown on tap (paused, so the play icon shows). Matches the design's capsule (size, album pill, icons); glass uses the NOTES §3.2 recipe with the design's .12 fill.
- `album-sheet.png` — album picker grown out of the album button.
- `controls-hidden.png` — controls settled away after 4 s.
- `album-switched.png` — Trips album (Lake Tahoe stand-in) with the capsule over snow: dark glyphs on the bright glass. The sheet over the same photo stays under the threshold and keeps white text.
- `night.png`, `night-wake.png` — Night mode: red monochrome photo, red text; after a tap, brighter with the red-outline capsule.

## Open questions

- Interval: the design's default is 8 s, so the zoom (9 s) never finishes and the frame draws continuously. A longer default (e.g. 30 s) would let it go idle after each zoom. Kept 8 s for fidelity; "Seconds per photo" in the settings card changes it.
- Shuffle vs. album order for Immich albums: shuffles now (one pass, then reshuffle). Demo plays in the design's order.
- Dark glyphs over bright glass (above) are my addition; a design call is welcome.
- Should the frame prefer Immich "memories" (on this day) when there are some? Not built; the caption already marks anniversaries.

## Needs from the user

- Immich URL and API key: on the settings page under Accounts (`immich.url`, `immich.apiKey`, SECRETs declared by agent K), or at build time in `android/secrets.properties`. A key with read access to albums and assets is enough. They apply live; Immich wins over demo when both are set.
- Then pick the album in the "Photo frame" card (default "Family", else the largest).

## Device setup

None. Uses `INTERNET` only (declared in this module's manifest). Cache lives in the app's `cacheDir` (150 MB cap).
