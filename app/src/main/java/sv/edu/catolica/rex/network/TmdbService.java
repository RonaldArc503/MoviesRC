package sv.edu.catolica.rex.network;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import sv.edu.catolica.rex.BuildConfig;
import sv.edu.catolica.rex.models.MediaItem;

public class TmdbService {

    private static final String TAG        = "TmdbService";
    private static final String API_BASE   = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/original";
    private static final int    TIMEOUT_MS = 15000;
    private static final long   EXTERNAL_IDS_429_COOLDOWN_MS = 60_000L;
    private static final int    MAX_HTTP_ATTEMPTS = 3;
    private static final long   REQUEST_BASE_DELAY_MS = 260L;
    private static final long   REQUEST_JITTER_MS = 180L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";

    /** Número de hilos para enriquecimiento paralelo */
    private static final int TMDB_THREAD_COUNT = 4;

    private static class TmdbResult {
        String posterUrl;
        String overview;
        int tmdbId;
        String imdbId;
        String tmdbMediaType;
        String backdropUrl;
    }

    // ─── Caché en memoria para evitar llamadas duplicadas ─────────────────────
    private static final Map<String, TmdbResult> resultCache = new HashMap<>();
    private static final Map<String, String> externalIdsCache = new HashMap<>();
    private static volatile long externalIdsBlockedUntilMs = 0L;

    /**
     * Enriquece todos los ítems EN PARALELO usando un pool de hilos.
     * Es ~8x más rápido que el método secuencial original.
     * Llama a este método desde un hilo de background (nunca desde UI thread).
     */
    public static void enrichMediaItemsParallel(List<MediaItem> items) {
        if (items == null || items.isEmpty()) return;
        if (BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(TMDB_THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(items.size());

        for (MediaItem item : items) {
            if (item == null) {
                latch.countDown();
                continue;
            }
            pool.submit(() -> {
                try {
                    String key = buildKey(item);
                    TmdbResult result;
                    synchronized (resultCache) {
                        result = resultCache.get(key);
                    }
                    if (result == null) {
                        result = findMedia(item);
                        if (result != null) {
                            synchronized (resultCache) {
                                resultCache.put(key, result);
                            }
                        }
                    }
                    applyResult(item, result);
                } catch (Exception e) {
                    Log.w(TAG, "Enrich error for " + item.getTitulo() + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        pool.shutdown();

        try {
            latch.await(); // Esperar a que todos terminen
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Versión de compatibilidad (usa la nueva implementación paralela).
     * @deprecated Usar enrichMediaItemsParallel directamente desde background thread.
     */
    public static void enrichMediaItems(List<MediaItem> items) {
        enrichMediaItemsParallel(items);
    }

    public static void enrichMediaItem(MediaItem item) {
        if (item == null || BuildConfig.TMDB_API_KEY == null ||
                BuildConfig.TMDB_API_KEY.isEmpty()) return;
        TmdbResult result = findMedia(item);
        applyResult(item, result);
    }

    public static void ensureExternalIds(MediaItem item) {
        if (item == null) return;
        String imdb = item.getImdbId();
        if (item.getTmdbId() > 0 && imdb != null && imdb.matches("tt\\d+")) {
            return;
        }
        enrichMediaItem(item);
    }

    public static List<AllCalidadScraper.Season> getTvSeasonsFallback(MediaItem item) {
        List<AllCalidadScraper.Season> seasons = new ArrayList<>();
        if (item == null || BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.isEmpty()) {
            return seasons;
        }

        ensureExternalIds(item);
        int tmdbId = item.getTmdbId();
        if (tmdbId <= 0) {
            return seasons;
        }

        try {
            String url = API_BASE + "/tv/" + tmdbId + "?api_key="
                    + URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8")
                    + "&language=es-ES";
            JSONObject root = new JSONObject(httpGet(url));
            JSONArray seasonArray = root.optJSONArray("seasons");
            if (seasonArray == null || seasonArray.length() == 0) {
                return seasons;
            }

            for (int i = 0; i < seasonArray.length(); i++) {
                JSONObject seasonObj = seasonArray.optJSONObject(i);
                if (seasonObj == null) {
                    continue;
                }

                int seasonNumber = seasonObj.optInt("season_number", 0);
                int episodeCount = seasonObj.optInt("episode_count", 0);
                if (seasonNumber <= 0 || episodeCount <= 0) {
                    continue;
                }

                AllCalidadScraper.Season season = new AllCalidadScraper.Season();
                season.seasonNumber = seasonNumber;
                for (int ep = 1; ep <= episodeCount; ep++) {
                    AllCalidadScraper.Episode episode = new AllCalidadScraper.Episode();
                    episode.id = buildSyntheticEpisodeId(tmdbId, seasonNumber, ep);
                    episode.seasonNumber = seasonNumber;
                    episode.episodeNumber = ep;
                    episode.title = "Episodio " + ep;
                    season.episodes.add(episode);
                }
                seasons.add(season);
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB season fallback failed: " + e.getMessage());
        }

        return seasons;
    }

    public static List<MediaItem> getPopularHomeItems(int limit) {
        List<MediaItem> movies = fetchHomeItemsByEndpoint("/movie/popular", "movie", limit);
        List<MediaItem> tv = fetchHomeItemsByEndpoint("/tv/popular", "tv", limit);
        return mergeInterleavedAndTrim(movies, tv, limit);
    }

    public static List<MediaItem> getTrendingHomeItems(int limit) {
        if (limit <= 0 || BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.isEmpty()) {
            return new ArrayList<>();
        }

        List<MediaItem> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        try {
            String url = API_BASE + "/trending/all/week?api_key="
                    + URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8")
                    + "&language=es-ES&page=1";

            JSONObject root = new JSONObject(httpGet(url));
            JSONArray results = root.optJSONArray("results");
            if (results == null) {
                return out;
            }

            for (int i = 0; i < results.length() && out.size() < limit; i++) {
                JSONObject raw = results.optJSONObject(i);
                if (raw == null) {
                    continue;
                }

                String tmdbType = normalizeTmdbMediaType(raw.optString("media_type", ""));
                MediaItem item = toHomeMediaItem(raw, tmdbType, false);
                if (item == null) {
                    continue;
                }

                String key = item.getMediaType() + "|" + item.getTmdbId();
                if (seen.containsKey(key)) {
                    continue;
                }
                seen.put(key, true);
                out.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB trending failed: " + e.getMessage());
        }
        return out;
    }

    public static List<MediaItem> getNowPlayingMovies(int limit) {
        return fetchHomeItemsByEndpoint("/movie/now_playing", "movie", limit);
    }

    public static List<MediaItem> getUpcomingMovies(int limit) {
        return fetchHomeItemsByEndpoint("/movie/upcoming", "movie", limit);
    }

    public static List<MediaItem> getTopRatedMovies(int limit) {
        return fetchHomeItemsByEndpoint("/movie/top_rated", "movie", limit);
    }

    public static List<MediaItem> getTopRatedTvShows(int limit) {
        return fetchHomeItemsByEndpoint("/tv/top_rated", "tv", limit);
    }

    public static List<MediaItem> getOnTheAirTvShows(int limit) {
        return fetchHomeItemsByEndpoint("/tv/on_the_air", "tv", limit);
    }

    public static List<MediaItem> getDiscoverItems(int limit) {
        List<MediaItem> movies = fetchHomeItemsByEndpoint("/discover/movie?sort_by=popularity.desc&vote_count.gte=100", "movie", limit);
        List<MediaItem> tv = fetchHomeItemsByEndpoint("/discover/tv?sort_by=popularity.desc&vote_count.gte=50", "tv", limit);
        return mergeInterleavedAndTrim(movies, tv, limit);
    }

    public static List<MediaItem> searchMedia(String query, int limit) {
        List<MediaItem> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty() || limit <= 0
                || BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.isEmpty()) {
            return out;
        }

        Map<String, Boolean> seen = new LinkedHashMap<>();
        try {
            String url = API_BASE + "/search/multi?api_key="
                    + URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8")
                    + "&language=es-ES&include_adult=false&query="
                    + URLEncoder.encode(query.trim(), "UTF-8")
                    + "&page=1";

            JSONObject root = new JSONObject(httpGet(url));
            JSONArray results = root.optJSONArray("results");
            if (results == null) {
                return out;
            }

            for (int i = 0; i < results.length() && out.size() < limit; i++) {
                JSONObject raw = results.optJSONObject(i);
                if (raw == null) {
                    continue;
                }
                String tmdbType = normalizeTmdbMediaType(raw.optString("media_type", ""));
                MediaItem item = toHomeMediaItem(raw, tmdbType, false);
                if (item == null) {
                    continue;
                }

                String key = item.getMediaType() + "|" + item.getTmdbId();
                if (seen.containsKey(key)) {
                    continue;
                }
                seen.put(key, true);
                out.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB search failed: " + e.getMessage());
        }

        return out;
    }

    // ─── Internos ─────────────────────────────────────────────────────────────

    private static String buildKey(MediaItem item) {
        String title = cleanTitle(item.getTitulo());
        String year  = item.getAnio()      == null ? "" : item.getAnio().trim();
        String type  = item.getMediaType() == null ? "" :
                item.getMediaType().trim().toLowerCase(Locale.ROOT);
        return title + "|" + year + "|" + type;
    }

    private static void applyResult(MediaItem item, TmdbResult result) {
        if (item == null || result == null) return;
        if (result.posterUrl != null && !result.posterUrl.isEmpty()) {
            item.setImagen(result.posterUrl);
        }
        if (result.backdropUrl != null && !result.backdropUrl.isEmpty()) {
            item.setBackdrop(result.backdropUrl);
        }
        String currentSynopsis = item.getSynopsis();
        if ((currentSynopsis == null || currentSynopsis.trim().isEmpty())
                && result.overview != null && !result.overview.trim().isEmpty()) {
            item.setSynopsis(result.overview.trim());
        }
        if (result.tmdbId > 0) {
            item.setTmdbId(result.tmdbId);
        }
        if (result.imdbId != null && !result.imdbId.trim().isEmpty()) {
            item.setImdbId(result.imdbId.trim());
        }
    }

    private static TmdbResult findMedia(MediaItem item) {
        String type = item.getMediaType() == null ? "" :
                item.getMediaType().trim().toLowerCase(Locale.ROOT);
        String title = item.getTitulo();
        String year  = item.getAnio();
        boolean isSeries = "tvshows".equals(type) || "animes".equals(type) ||
                "tv".equals(type) || "series".equals(type);

        TmdbResult result = findByEndpoint(title, year, isSeries ? "tv" : "movie");
        if (result == null) {
            result = findByEndpoint(title, year, "multi");
        }
        return result;
    }

    private static List<MediaItem> fetchHomeItemsByEndpoint(String path, String forcedTmdbType, int limit) {
        List<MediaItem> out = new ArrayList<>();
        if (limit <= 0 || BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.isEmpty()) {
            return out;
        }

        try {
            String url = API_BASE + path + "?api_key="
                    + URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8")
                    + "&language=es-ES&page=1";
            JSONObject root = new JSONObject(httpGet(url));
            JSONArray results = root.optJSONArray("results");
            if (results == null) {
                return out;
            }

            for (int i = 0; i < results.length() && out.size() < limit; i++) {
                JSONObject raw = results.optJSONObject(i);
                if (raw == null) {
                    continue;
                }
                MediaItem item = toHomeMediaItem(raw, forcedTmdbType, false);
                if (item != null) {
                    out.add(item);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB list failed for " + path + ": " + e.getMessage());
        }
        return out;
    }

    private static MediaItem toHomeMediaItem(JSONObject raw, String tmdbType, boolean fetchExternalId) {
        String type = normalizeTmdbMediaType(tmdbType);
        if (!"movie".equals(type) && !"tv".equals(type)) {
            return null;
        }

        int tmdbId = raw.optInt("id", 0);
        if (tmdbId <= 0) {
            return null;
        }

        String title = firstNonEmpty(raw.optString("title", ""), raw.optString("name", ""));
        if (title.isEmpty()) {
            return null;
        }

        String date = firstNonEmpty(raw.optString("release_date", ""), raw.optString("first_air_date", ""));
        String year = "";
        if (date.length() >= 4) {
            year = date.substring(0, 4);
        }

        String posterPath = normalizeTmdbImagePath(raw.optString("poster_path", ""));
        String posterUrl = posterPath.isEmpty() ? "" : IMAGE_BASE + posterPath;
        String backdropPath = normalizeTmdbImagePath(raw.optString("backdrop_path", ""));
        String backdropUrl = backdropPath.isEmpty() ? "" : IMAGE_BASE + backdropPath;
        String overview = raw.optString("overview", "");
        double rating = raw.optDouble("vote_average", 0.0);

        MediaItem item = new MediaItem(title, year, posterUrl, "", 0);
        item.setSynopsis(overview);
        item.setRating(rating);
        item.setTmdbId(tmdbId);
        item.setMediaType("movie".equals(type) ? "movies" : "tvshows");
        item.setFuente("tmdb");
        item.setBackdrop(backdropUrl);
        if (fetchExternalId) {
            item.setImdbId(fetchExternalImdbId(type, tmdbId));
        }
        return item;
    }

    private static String normalizeTmdbMediaType(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("movie".equals(value)) return "movie";
        if ("tv".equals(value)) return "tv";
        return "";
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return "";
    }

    private static List<MediaItem> mergeInterleavedAndTrim(List<MediaItem> first, List<MediaItem> second, int limit) {
        List<MediaItem> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();

        int i = 0;
        while (out.size() < limit) {
            boolean progressed = false;
            if (first != null && i < first.size()) {
                progressed = appendUnique(out, seen, first.get(i), limit) || progressed;
            }
            if (second != null && i < second.size()) {
                progressed = appendUnique(out, seen, second.get(i), limit) || progressed;
            }
            if (!progressed && (first == null || i >= first.size()) && (second == null || i >= second.size())) {
                break;
            }
            i++;
        }
        return out;
    }

    private static boolean appendUnique(List<MediaItem> out, Map<String, Boolean> seen, MediaItem item, int limit) {
        if (item == null || out.size() >= limit) {
            return false;
        }
        String key = item.getMediaType() + "|" + item.getTmdbId();
        if (seen.containsKey(key)) {
            return false;
        }
        seen.put(key, true);
        out.add(item);
        return true;
    }

    private static TmdbResult findByEndpoint(String title, String year, String endpoint) {
        try {
            String cleanTitle = cleanTitle(title);
            if (cleanTitle.isEmpty()) return null;

            StringBuilder url = new StringBuilder(API_BASE)
                    .append("/search/").append(endpoint)
                    .append("?api_key=")
                    .append(URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8"))
                    .append("&language=es-ES&include_adult=false&query=")
                    .append(URLEncoder.encode(cleanTitle, "UTF-8"));

            if (year != null && year.matches("\\d{4}")) {
                url.append("tv".equals(endpoint) ?
                        "&first_air_date_year=" : "&year=").append(year);
            }

            String response = httpGet(url.toString());
            JSONObject root = new JSONObject(response);
            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) return null;

            JSONObject first = results.optJSONObject(0);
            if (first == null) return null;

            TmdbResult result = new TmdbResult();
            String posterPath = normalizeTmdbImagePath(first.optString("poster_path", ""));
            if (!posterPath.isEmpty()) result.posterUrl = IMAGE_BASE + posterPath;
            String backdropPath = normalizeTmdbImagePath(first.optString("backdrop_path", ""));
            if (!backdropPath.isEmpty()) result.backdropUrl = IMAGE_BASE + backdropPath;
            result.overview = first.optString("overview", "");
            result.tmdbId = first.optInt("id", 0);
            result.tmdbMediaType = detectEndpointFromMediaType(endpoint, first.optString("media_type", ""));
            if (result.tmdbId > 0 && result.tmdbMediaType != null) {
                result.imdbId = fetchExternalImdbId(result.tmdbMediaType, result.tmdbId);
            }
            return result;

        } catch (Exception e) {
            Log.w(TAG, "TMDB search failed: " + e.getMessage());
            return null;
        }
    }

    private static String detectEndpointFromMediaType(String endpoint, String mediaType) {
        if ("movie".equals(endpoint) || "tv".equals(endpoint)) {
            return endpoint;
        }
        String mt = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        if ("movie".equals(mt)) return "movie";
        if ("tv".equals(mt)) return "tv";
        return null;
    }

    private static String fetchExternalImdbId(String endpoint, int tmdbId) {
        if (tmdbId <= 0 || (!"movie".equals(endpoint) && !"tv".equals(endpoint))) {
            return "";
        }
        long now = System.currentTimeMillis();
        if (now < externalIdsBlockedUntilMs) {
            return "";
        }

        String cacheKey = endpoint + ":" + tmdbId;
        synchronized (externalIdsCache) {
            String cached = externalIdsCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        try {
            String url = API_BASE + "/" + endpoint + "/" + tmdbId + "/external_ids?api_key="
                    + URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8");
            JSONObject root = new JSONObject(httpGet(url));
            String imdb = normalizeImdbId(root.optString("imdb_id", ""));
            if (!imdb.isEmpty()) {
                synchronized (externalIdsCache) {
                    externalIdsCache.put(cacheKey, imdb);
                }
            }
            return imdb;
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("HTTP 429")) {
                externalIdsBlockedUntilMs = System.currentTimeMillis() + EXTERNAL_IDS_429_COOLDOWN_MS;
            }
            Log.w(TAG, "TMDB external_ids failed: " + e.getMessage());
            return "";
        }
    }

    private static String normalizeImdbId(String raw) {
        if (raw == null) return "";
        String imdb = raw.trim();
        if (imdb.matches("tt\\d+")) {
            return imdb;
        }
        return "";
    }

    private static String normalizeTmdbImagePath(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return "";
        }
        return value;
    }

    private static String cleanTitle(String rawTitle) {
        if (rawTitle == null) return "";
        return rawTitle
                .replaceAll("\\(\\d{4}\\)", "")
                .replaceAll("\\[.*?\\]", "")
                .trim();
    }

    private static int buildSyntheticEpisodeId(int tmdbId, int season, int episode) {
        long value = ((long) tmdbId * 10000L) + ((long) season * 100L) + episode;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE - 1 : (int) value;
    }

    private static final class HttpStatusException extends IOException {
        final int statusCode;
        final long retryAfterMs;

        HttpStatusException(int statusCode, String urlString, long retryAfterMs) {
            super("HTTP " + statusCode + " for " + urlString);
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static String httpGet(String urlString) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            RequestGuard.waitForSlot(urlString, REQUEST_BASE_DELAY_MS, REQUEST_JITTER_MS, attempt);
            try {
                return httpGetOnce(urlString);
            } catch (HttpStatusException e) {
                lastException = e;
                boolean retryableStatus = e.statusCode == 429
                        || e.statusCode == 403
                        || (e.statusCode >= 500 && e.statusCode <= 599);
                if (!retryableStatus || attempt >= MAX_HTTP_ATTEMPTS) {
                    throw e;
                }
                sleepBeforeRetry(RequestGuard.computeRetryDelayMs(attempt, e.statusCode, e.retryAfterMs));
            } catch (IOException e) {
                lastException = e;
                if (attempt >= MAX_HTTP_ATTEMPTS) {
                    throw e;
                }
                sleepBeforeRetry(RequestGuard.computeRetryDelayMs(attempt, -1, -1));
            }
        }
        throw lastException;
    }

    private static String httpGetOnce(String urlString) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            long retryAfterMs = parseRetryAfterMs(conn.getHeaderField("Retry-After"));
            RequestGuard.onResponse(urlString, responseCode, retryAfterMs);
            if (responseCode >= 400) {
                throw new HttpStatusException(responseCode, urlString, retryAfterMs);
            }

            br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) { }
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static void sleepBeforeRetry(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long parseRetryAfterMs(String retryAfterHeader) {
        if (retryAfterHeader == null) {
            return -1L;
        }
        String value = retryAfterHeader.trim();
        if (value.isEmpty()) {
            return -1L;
        }
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0) {
                return -1L;
            }
            return Math.min(seconds * 1000L, 60_000L);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
