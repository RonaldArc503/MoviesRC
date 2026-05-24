# Multi-Source Architecture for OTT Playback

## Goal

Add a secondary source (`Pelispedia`) without breaking the current `AllCalidad`-first flow. The architecture must stay Java-only, be incremental, and keep `SmartScraperEngine` and `PlayerExoActivity` intact.

## Current Runtime Flow

1. User opens a detail screen.
2. A `MediaItem` is loaded from TMDB or the current source.
3. The user presses play.
4. `SmartScraperEngine` resolves URLs.
5. Providers return a `SmartPlaybackResult` with:
   - `embedUrl`
   - `hostUrls`
   - `streamUrls`
6. `PlayerExoActivity` iterates playable URLs and uses `tryNextStream()` when needed.

## New Source Model

### `ContentSource`
A small contract that allows each source to implement the same capabilities:

- `search()`
- `getBySlug()`
- `getSeasons()`
- `resolveMovie()`
- `resolveEpisode()`
- `getName()`
- `supports()`

This lets the app add future sources like AnimeFLV or Cuevana without changing the player.

## Source Ordering

Recommended priority:

1. `AllCalidad`
2. `Pelispedia`
3. cached result
4. retry / host fallback

This is controlled by `SourcePriorityManager` and consumed by `SourceRouter`.

## Classes Added

### `AllCalidadSource`
Adapter over `AllCalidadScraper`.

Responsibilities:
- search and map `ContentItem` to `MediaItem`
- resolve playback using existing AllCalidad player endpoints
- stay primary

### `PelispediaSource`
Adapter over `PelispediaScraper`.

Responsibilities:
- search Pelispedia URLs
- resolve movie and episode pages
- search by title if the item does not contain a direct detail URL

### `PelispediaScraper`
Low-level HTTP scraper.

Responsibilities:
- fetch HTML
- detect `/vidurl/{id}` and `/serie/{slug}` / `/pelicula/{slug}` paths
- extract iframes, host URLs and direct streams
- normalize URLs and return `SmartPlaybackResult`

### `SourceRouter`
Chooses the source order and handles fallback.

Flow:

1. check source-aware cache
2. try `AllCalidadSource`
3. try `PelispediaSource`
4. return cached or empty result if nothing works

### `SourceAwareCache`
Caches playback results by:
- source
- imdb id
- tmdb id
- title
- year
- season
- episode

This prevents wrong reuse across sources.

### `SourceMatcher`
Smart matching utilities.

Priority:
1. `imdb_id`
2. `tmdb_id`
3. `title + year`
4. fuzzy match

### `TmdbContentMatcher`
Metadata enrichment and content classification.

Responsibilities:
- merge TMDB metadata into source items
- detect anime / drama / dorama

### `MultiSourceRepository`
Container for all registered sources.

Responsibilities:
- register sources
- search across sources
- resolve movie / episode across sources
- keep source instances decoupled from UI

### `UnifiedSearchService`
Thin service layer over the repository.

## Playback Resolution Strategy

### Movie

1. Build request from `MediaItem`.
2. Check cache.
3. Try AllCalidad.
4. If empty, try Pelispedia.
5. If still empty, try host/stream retries.
6. Cache the first valid `SmartPlaybackResult`.

### Episode

1. Build request with `seasonNumber` and `episodeNumber`.
2. Try AllCalidad with the episode post id.
3. If that fails, try Pelispedia episode URL heuristics.
4. If still empty, let `PlayerExoActivity` continue with `tryNextStream()`.

## TMDB Integration

TMDB remains metadata only:

- posters
- backdrops
- discover
- trending
- recommendations
- genres
- year / external ids

TMDB should not decide playback URLs. It only enriches the `MediaItem` before routing.

## Home UI Strategy

Keep the current layout and add source-specific rows.

Suggested rows:
- Trending
- Doramas
- Series nuevas
- Peliculas nuevas
- Anime
- Recommended
- Popular en Pelispedia

The home screen can merge:
- TMDB sections
- AllCalidad sections
- Pelispedia sections

Do not move scraping into the Activity. The Activity should only render sections and call services.

## Headers and Referers

For video hosts, keep the existing behavior:
- custom `User-Agent`
- dynamic `Referer`
- fallback headers per host
- host-specific handling for `streamwish`, `filemoon`, `filelions`, `voe`, `minochinos`, `xupalace`, `embed69`

`PlayerExoActivity` should continue to receive a list of URLs, not source-specific logic.

## Cloudflare / Host Handling

For basic host protection:
- use `HttpURLConnection` or OkHttp with headers
- follow redirects
- keep referer aligned with the source page
- extract iframe `src` values and nested hosts
- normalize `https://` / `//` URLs

For stronger host protections, a dedicated extractor can be added later without changing the player.

## Incremental Integration Plan

1. Keep `SmartScraperEngine` as the main entry point.
2. Add source adapters.
3. Add a source router with cache.
4. Use the router only as fallback first.
5. Add new home rows for Pelispedia.
6. Add future sources by implementing `ContentSource`.

## Future Sources

The architecture can add:
- AnimeFLV
- Cuevana
- DoramasMP4
- TokyVideo
- MixDrop

Each new source only needs:
- its scraper
- its adapter
- optional source priority rule

No player rewrite should be required.
