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
- **Tests** (30, JVM): Immich client against MockWebServer (auth header, parsing, filtering, search fallback + paging, preview download, HTTP errors without the key, URL normalisation, dates), cache eviction / LRU / restart / temp cleanup, metadata round trip, slideshow order / wrap / refresh / pause / timing / prefetch skipping failed and uncached, captions, decode math.
- Debug args: `--es screen photos [--es album NAME] [--es seconds N] [--es controls 1] [--es sheet 1] [--es paused 1] [--es night 1|0]`.

## Left

- Not run against a real Immich server (none available yet). Everything server-side is tested against MockWebServer with Immich's documented response shapes; check on a real server: shared albums appear in `GET /api/albums`, and the preview format (JPEG or WebP, both decode).
- Album sheet has no artboard; worth a design pass.
- No setting UI for the interval besides `photos.seconds` (and the album, via the sheet).

## Measured on the Echo

PLACEHOLDER_PERF

## Screenshots (`docs/status/shots/photos/`)

Compared on screen with `Photos.dc.html` rendered at 960×480 in Chrome (same state: photo 1, controls shown).

- `photo.png` — Big Sur stand-in, time, date, caption. Matches the design: positions, sizes, weights, scrim and shadows line up; the photo is a few % into its zoom.
- `photo-valley.png`, `photo-lanterns.png` — photos 2 and 3 after crossfades.
- `controls.png` — glass capsule shown on tap (paused, so the play icon shows). Matches the design's capsule (size, album pill, icons); glass uses the NOTES §3.2 recipe with the design's .12 fill.
- `album-sheet.png` — album picker grown out of the album button.
- PLACEHOLDER_SHOTS

## Open questions

- Interval: the design's default is 8 s, so the zoom (9 s) never finishes and the frame draws continuously. A longer default (e.g. 30 s) would let it go idle after each zoom. Kept 8 s for fidelity; `photos.seconds` changes it.
- Shuffle vs. album order for Immich albums: shuffles now (one pass, then reshuffle). Demo plays in the design's order.
- Should the frame prefer Immich "memories" (on this day) when there are some? Not built; the caption already marks anniversaries.

## Needs from the user

- Immich URL and API key in `android/secrets.properties` (`immich.url`, `immich.apiKey`); a key with read access to albums and assets is enough. Then turn demo off (`--ez demo false`) or just set the secrets (Immich wins over demo when configured).
- Which album to show by default (else "Family", else the largest).

## Device setup

None. Uses `INTERNET` only (declared in this module's manifest). Cache lives in the app's `cacheDir` (150 MB cap).
