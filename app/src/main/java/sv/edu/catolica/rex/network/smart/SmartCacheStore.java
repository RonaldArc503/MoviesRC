package sv.edu.catolica.rex.network.smart;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

class SmartCacheStore {

    private static final String PREF = "smart_scraper_cache";
    private static final String KEY_PREFIX_PLAYBACK = "playback_v2_";
    private static final String KEY_PREFIX_IDS = "ids_";
    private static final long PLAYBACK_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long IDS_TTL_MS = 24L * 60L * 60L * 1000L;

    static SmartPlaybackResult getPlayback(Context context, String cacheKey) {
        if (context == null || cacheKey == null || cacheKey.trim().isEmpty()) {
            return null;
        }
        try {
            SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = pref.getString(KEY_PREFIX_PLAYBACK + cacheKey, null);
            if (raw == null || raw.isEmpty()) return null;

            JSONObject root = new JSONObject(raw);
            long ts = root.optLong("ts", 0L);
            if (ts <= 0L || System.currentTimeMillis() - ts > PLAYBACK_TTL_MS) {
                return null;
            }

            SmartPlaybackResult result = new SmartPlaybackResult();
            result.providerName = root.optString("provider", "");
            result.embedUrl = root.optString("embed", "");

            JSONArray hosts = root.optJSONArray("hosts");
            if (hosts != null) {
                for (int i = 0; i < hosts.length(); i++) {
                    String url = hosts.optString(i, "");
                    if (!url.isEmpty()) result.hostUrls.add(url);
                }
            }

            JSONArray streams = root.optJSONArray("streams");
            if (streams != null) {
                for (int i = 0; i < streams.length(); i++) {
                    String url = streams.optString(i, "");
                    if (!url.isEmpty()) result.streamUrls.add(url);
                }
            }

            return result.hasPlayableUrls() ? result : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static void putPlayback(Context context, String cacheKey, SmartPlaybackResult result) {
        if (context == null || cacheKey == null || result == null || !result.hasPlayableUrls()) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("ts", System.currentTimeMillis());
            root.put("provider", result.providerName == null ? "" : result.providerName);
            root.put("embed", result.embedUrl == null ? "" : result.embedUrl);
            root.put("hosts", new JSONArray(result.hostUrls));
            root.put("streams", new JSONArray(result.streamUrls));

            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PREFIX_PLAYBACK + cacheKey, root.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    static void putIds(Context context, String identityKey, int tmdbId, String imdbId, String mediaType) {
        if (context == null || identityKey == null || identityKey.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("ts", System.currentTimeMillis());
            root.put("tmdb_id", tmdbId);
            root.put("imdb_id", imdbId == null ? "" : imdbId);
            root.put("media_type", mediaType == null ? "" : mediaType);

            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PREFIX_IDS + identityKey, root.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    static CachedIds getIds(Context context, String identityKey) {
        if (context == null || identityKey == null || identityKey.trim().isEmpty()) {
            return null;
        }
        try {
            SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = pref.getString(KEY_PREFIX_IDS + identityKey, null);
            if (raw == null || raw.isEmpty()) return null;

            JSONObject root = new JSONObject(raw);
            long ts = root.optLong("ts", 0L);
            if (ts <= 0L || System.currentTimeMillis() - ts > IDS_TTL_MS) {
                return null;
            }

            CachedIds ids = new CachedIds();
            ids.tmdbId = root.optInt("tmdb_id", 0);
            ids.imdbId = root.optString("imdb_id", "");
            ids.mediaType = root.optString("media_type", "");
            return ids;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String identityKey(String title, String year, String mediaType) {
        String safeTitle = title == null ? "" : title.trim().toLowerCase();
        String safeYear = year == null ? "" : year.trim();
        String safeType = mediaType == null ? "" : mediaType.trim().toLowerCase();
        return safeTitle + "|" + safeYear + "|" + safeType;
    }

    static class CachedIds {
        int tmdbId;
        String imdbId;
        String mediaType;

        List<String> asList() {
            List<String> out = new ArrayList<>();
            out.add(String.valueOf(tmdbId));
            out.add(imdbId == null ? "" : imdbId);
            out.add(mediaType == null ? "" : mediaType);
            return out;
        }
    }
}
