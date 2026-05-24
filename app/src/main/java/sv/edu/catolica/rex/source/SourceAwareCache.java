package sv.edu.catolica.rex.source;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public final class SourceAwareCache {
    private static final String PREF = "source_aware_cache";
    private static final long TTL_MS = 6L * 60L * 60L * 1000L;

    private SourceAwareCache() {}

    public static String key(String source, String imdbId, int tmdbId, String title, String year, int season, int episode) {
        String safeSource = norm(source);
        String safeImdb = norm(imdbId);
        String safeTitle = norm(title);
        String safeYear = norm(year);
        return safeSource + "|" + safeImdb + "|" + tmdbId + "|" + safeTitle + "|" + safeYear + "|S" + season + "|E" + episode;
    }

    public static SmartPlaybackResult get(Context context, String key) {
        if (context == null || key == null || key.trim().isEmpty()) return null;
        try {
            SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = p.getString(key, null);
            if (raw == null || raw.isEmpty()) return null;
            String[] parts = raw.split("\\n", 5);
            if (parts.length < 5) return null;
            long ts = Long.parseLong(parts[0]);
            if (System.currentTimeMillis() - ts > TTL_MS) return null;
            SmartPlaybackResult r = new SmartPlaybackResult();
            r.providerName = parts[1];
            r.embedUrl = parts[2];
            if (!parts[3].isEmpty()) for (String u : parts[3].split("\\u001f")) r.hostUrls.add(u);
            if (!parts[4].isEmpty()) for (String u : parts[4].split("\\u001f")) r.streamUrls.add(u);
            return r.hasPlayableUrls() ? r : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void put(Context context, String key, SmartPlaybackResult result) {
        if (context == null || key == null || key.trim().isEmpty() || result == null || !result.hasPlayableUrls()) return;
        try {
            String hosts = String.join("\u001f", result.hostUrls);
            String streams = String.join("\u001f", result.streamUrls);
            String raw = System.currentTimeMillis() + "\n"
                    + safe(result.providerName) + "\n"
                    + safe(result.embedUrl) + "\n"
                    + hosts + "\n"
                    + streams;
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(key, raw).apply();
        } catch (Exception ignored) {
        }
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
