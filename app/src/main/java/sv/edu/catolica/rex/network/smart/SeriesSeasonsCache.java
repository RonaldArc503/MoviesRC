package sv.edu.catolica.rex.network.smart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.source.SourceMatcher;

public final class SeriesSeasonsCache {
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_MS = 60 * 60 * 1000L; // 1 hora de validez en memoria

    private static class CacheEntry {
        final List<AllCalidadScraper.Season> seasons;
        final long timestamp;

        CacheEntry(List<AllCalidadScraper.Season> seasons) {
            this.seasons = seasons;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < TTL_MS;
        }
    }

    private SeriesSeasonsCache() {}

    public static void put(String title, int postId, String detailUrl, List<AllCalidadScraper.Season> seasons) {
        if (seasons == null || seasons.isEmpty()) return;
        CacheEntry entry = new CacheEntry(cloneSeasons(seasons));
        if (postId > 0) {
            CACHE.put("post_" + postId, entry);
        }
        if (title != null && !title.trim().isEmpty()) {
            CACHE.put("title_" + SourceMatcher.normalize(title), entry);
        }
        if (detailUrl != null && !detailUrl.trim().isEmpty()) {
            CACHE.put("url_" + detailUrl.trim().toLowerCase(Locale.ROOT), entry);
        }
    }

    public static List<AllCalidadScraper.Season> get(String title, int postId, String detailUrl) {
        if (postId > 0) {
            CacheEntry e = CACHE.get("post_" + postId);
            if (e != null && e.isValid()) return cloneSeasons(e.seasons);
        }
        if (detailUrl != null && !detailUrl.trim().isEmpty()) {
            CacheEntry e = CACHE.get("url_" + detailUrl.trim().toLowerCase(Locale.ROOT));
            if (e != null && e.isValid()) return cloneSeasons(e.seasons);
        }
        if (title != null && !title.trim().isEmpty()) {
            CacheEntry e = CACHE.get("title_" + SourceMatcher.normalize(title));
            if (e != null && e.isValid()) return cloneSeasons(e.seasons);
        }
        return null;
    }

    public static void clear() {
        CACHE.clear();
    }

    private static List<AllCalidadScraper.Season> cloneSeasons(List<AllCalidadScraper.Season> original) {
        if (original == null) return new ArrayList<>();
        List<AllCalidadScraper.Season> copy = new ArrayList<>(original.size());
        for (AllCalidadScraper.Season s : original) {
            if (s == null) continue;
            AllCalidadScraper.Season sc = new AllCalidadScraper.Season();
            sc.seasonNumber = s.seasonNumber;
            if (s.episodes != null) {
                sc.episodes = new ArrayList<>(s.episodes);
            }
            copy.add(sc);
        }
        return copy;
    }
}
