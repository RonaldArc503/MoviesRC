package sv.edu.catolica.rex.network.smart;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;
import sv.edu.catolica.rex.source.SourceRegistry;
import sv.edu.catolica.rex.source.SourceRouter;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public class SmartScraperEngine {

    private static final String TAG = "SmartScraperEngine";

    public List<String> resolveMovieUrls(Context context, MediaItem item) {
        SmartPlaybackRequest request = buildRequest(context, item, 0, 0);
        return resolveFromAllCalidad(context, request);
    }

    public List<String> resolveEpisodeUrls(Context context, MediaItem item, int season, int episode) {
        return resolveEpisodeUrls(context, item, season, episode, 0);
    }

    public List<String> resolveEpisodeUrls(Context context, MediaItem item, int season, int episode, int episodePostId) {
        SmartPlaybackRequest request = buildRequest(context, item, season, episode);
        if (request == null) {
            return new ArrayList<>();
        }
        request.episodePostId = Math.max(0, episodePostId);
        return resolveFromAllCalidad(context, request);
    }

    private List<String> resolveFromAllCalidad(Context context, SmartPlaybackRequest request) {
        if (request == null) {
            return new ArrayList<>();
        }

        SmartPlaybackResult cached = SmartCacheStore.getPlayback(context, request.cacheKey());
        if (cached != null && cached.hasPlayableUrls()) {
            List<String> cachedUrls = filterDeprecatedHosts(cached.toPlayableUrls());
            if (!cachedUrls.isEmpty()) {
                return cachedUrls;
            }
        }

        List<String> urls = resolveAllCalidadUrls(request);
        if (urls != null && !urls.isEmpty()) {
            SmartPlaybackResult cacheResult = buildCacheResult(urls);
            SmartCacheStore.putPlayback(context, request.cacheKey(), cacheResult);
            return urls;
        }

        // Fallback: route through the multi-source resolver with cache + priority
        try {
            MediaItem fallbackItem = new MediaItem(request.title, "", "", "", 0);
            SourceRouter router = new SourceRouter(context, SourceRegistry.getInstance());
            SmartPlaybackResult fallback = request.isEpisodeRequest()
                    ? router.resolveEpisode(context, fallbackItem, request.seasonNumber, request.episodeNumber)
                    : router.resolveMovie(context, fallbackItem);
            if (fallback != null && fallback.hasPlayableUrls()) {
                SmartCacheStore.putPlayback(context, request.cacheKey(), fallback);
                List<String> fallbackUrls = filterDeprecatedHosts(fallback.toPlayableUrls());
                if (!fallbackUrls.isEmpty()) return fallbackUrls;
            }
        } catch (Exception ignored) { }

        return new ArrayList<>();
    }

    private SmartPlaybackRequest buildRequest(Context context, MediaItem item, int season, int episode) {
        if (item == null) {
            return null;
        }

        String mediaType = item.getMediaType() == null ? "" : item.getMediaType();
        String identity = SmartCacheStore.identityKey(item.getTitulo(), item.getAnio(), mediaType);

        if ((item.getImdbId() == null || item.getImdbId().trim().isEmpty()) && context != null) {
            SmartCacheStore.CachedIds cachedIds = SmartCacheStore.getIds(context, identity);
            if (cachedIds != null) {
                if (item.getTmdbId() <= 0 && cachedIds.tmdbId > 0) {
                    item.setTmdbId(cachedIds.tmdbId);
                }
                if (item.getImdbId() == null || item.getImdbId().trim().isEmpty()) {
                    item.setImdbId(cachedIds.imdbId);
                }
            }
        }

        TmdbService.ensureExternalIds(item);

        if (context != null) {
            SmartCacheStore.putIds(context, identity, item.getTmdbId(), item.getImdbId(), mediaType);
        }

        SmartPlaybackRequest request = new SmartPlaybackRequest();
        request.title = item.getTitulo();
        request.tmdbId = item.getTmdbId();
        request.imdbId = item.getImdbId();
        request.postId = Math.max(0, item.getPostId());
        request.episodePostId = 0;
        request.mediaType = mediaType;
        request.contentType = detectContentType(item);
        request.seasonNumber = season > 0 ? season : 1;
        request.episodeNumber = episode > 0 ? episode : 1;

        if (request.postId <= 0) {
            return null;
        }

        return request;
    }

    public static SmartContentType detectContentType(MediaItem item) {
        if (item == null) {
            return SmartContentType.MOVIE;
        }

        String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
        String title = item.getTitulo() == null ? "" : item.getTitulo().toLowerCase(Locale.ROOT);

        if (type.contains("dorama") || type.contains("drama") || title.contains("dorama")) {
            return SmartContentType.DRAMA;
        }
        if (type.contains("anime") || type.contains("animes")) {
            return SmartContentType.ANIME;
        }
        if (type.contains("tv") || type.contains("series")) {
            return SmartContentType.SERIES;
        }
        return SmartContentType.MOVIE;
    }

    private List<String> resolveAllCalidadUrls(SmartPlaybackRequest request) {
        if (request == null) {
            return new ArrayList<>();
        }

        int targetPostId = request.episodePostId > 0 ? request.episodePostId : request.postId;
        if (targetPostId <= 0) {
            return new ArrayList<>();
        }

        try {
            AllCalidadScraper.PlayerData playerData = AllCalidadScraper.getPlayer(targetPostId);
            List<String> urls = AllCalidadScraper.getPlayableUrls(playerData);
            return filterDeprecatedHosts(urls);
        } catch (Exception e) {
            Log.w(TAG, "AllCalidad player failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> filterDeprecatedHosts(List<String> urls) {
        List<String> out = new ArrayList<>();
        if (urls == null || urls.isEmpty()) {
            return out;
        }
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            String normalized = url.trim();
            String lc = normalized.toLowerCase(Locale.ROOT);
            if (lc.contains("embed69.org")) {
                continue;
            }
            out.add(normalized);
        }
        return out;
    }

    private SmartPlaybackResult buildCacheResult(List<String> urls) {
        SmartPlaybackResult cached = new SmartPlaybackResult();
        cached.providerName = "AllCalidadApi";
        cached.embedUrl = "";
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.trim().isEmpty()) {
                    cached.hostUrls.add(url.trim());
                }
            }
        }
        return cached;
    }
}
