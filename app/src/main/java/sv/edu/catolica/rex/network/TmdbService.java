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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sv.edu.catolica.rex.BuildConfig;
import sv.edu.catolica.rex.models.MediaItem;

public class TmdbService {

    private static final String TAG = "TmdbService";
    private static final String API_BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w500";
    private static final int TIMEOUT_MS = 15000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";

    private static class TmdbResult {
        String posterUrl;
        String overview;
    }

    public static void enrichMediaItems(List<MediaItem> items) {
        if (items == null || items.isEmpty() || BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.isEmpty()) {
            return;
        }

        Map<String, TmdbResult> cache = new HashMap<>();
        for (MediaItem item : items) {
            if (item == null) {
                continue;
            }
            String key = buildKey(item);
            TmdbResult result;
            if (cache.containsKey(key)) {
                result = cache.get(key);
            } else {
                result = findMedia(item);
                cache.put(key, result);
            }
            applyResult(item, result);
        }
    }

    public static void enrichMediaItem(MediaItem item) {
        if (item == null || BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.isEmpty()) {
            return;
        }
        TmdbResult result = findMedia(item);
        applyResult(item, result);
    }

    private static String buildKey(MediaItem item) {
        String title = cleanTitle(item.getTitulo());
        String year = item.getAnio() == null ? "" : item.getAnio().trim();
        String type = item.getMediaType() == null ? "" : item.getMediaType().trim().toLowerCase(Locale.ROOT);
        return title + "|" + year + "|" + type;
    }

    private static void applyResult(MediaItem item, TmdbResult result) {
        if (item == null || result == null) {
            return;
        }

        if (result.posterUrl != null && !result.posterUrl.isEmpty()) {
            item.setImagen(result.posterUrl);
        }

        String currentSynopsis = item.getSynopsis();
        if ((currentSynopsis == null || currentSynopsis.trim().isEmpty())
                && result.overview != null && !result.overview.trim().isEmpty()) {
            item.setSynopsis(result.overview.trim());
        }
    }

    private static TmdbResult findMedia(MediaItem item) {
        String type = item.getMediaType() == null ? "" : item.getMediaType().trim().toLowerCase(Locale.ROOT);
        String title = item.getTitulo();
        String year = item.getAnio();
        boolean isSeries = "tvshows".equals(type) || "animes".equals(type) || "tv".equals(type) || "series".equals(type);

        TmdbResult result = findByEndpoint(title, year, isSeries ? "tv" : "movie");
        if (result == null) {
            result = findByEndpoint(title, year, "multi");
        }
        return result;
    }

    private static TmdbResult findByEndpoint(String title, String year, String endpoint) {
        try {
            String cleanTitle = cleanTitle(title);
            if (cleanTitle.isEmpty()) {
                return null;
            }

            StringBuilder url = new StringBuilder(API_BASE)
                    .append("/search/")
                    .append(endpoint)
                    .append("?api_key=")
                    .append(URLEncoder.encode(BuildConfig.TMDB_API_KEY, "UTF-8"))
                    .append("&language=es-ES&include_adult=false&query=")
                    .append(URLEncoder.encode(cleanTitle, "UTF-8"));

            if (year != null && year.matches("\\d{4}")) {
                if ("tv".equals(endpoint)) {
                    url.append("&first_air_date_year=").append(year);
                } else {
                    url.append("&year=").append(year);
                }
            }

            String response = httpGet(url.toString());
            JSONObject root = new JSONObject(response);
            JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }

            JSONObject first = results.optJSONObject(0);
            if (first == null) {
                return null;
            }

            TmdbResult result = new TmdbResult();
            String posterPath = first.optString("poster_path", "");
            if (!posterPath.isEmpty()) {
                result.posterUrl = IMAGE_BASE + posterPath;
            }
            result.overview = first.optString("overview", "");
            return result;
        } catch (Exception e) {
            Log.w(TAG, "TMDB search failed: " + e.getMessage());
            return null;
        }
    }

    private static String cleanTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        return rawTitle
                .replaceAll("\\(\\d{4}\\)", "")
                .replaceAll("\\[.*?\\]", "")
                .trim();
    }

    private static String httpGet(String urlString) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode >= 400) {
                throw new IOException("HTTP " + responseCode + " for " + urlString);
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
