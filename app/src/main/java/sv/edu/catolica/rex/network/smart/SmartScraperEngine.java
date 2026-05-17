package sv.edu.catolica.rex.network.smart;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.TmdbService;

public class SmartScraperEngine {

    private static final String TAG = "SmartScraperEngine";

    private final List<SmartProvider> providers = Arrays.asList(
            new ProviderMovieEmbed69(),
            new ProviderSeriesEmbed69(),
            new ProviderDramaXupalace()
    );

    public List<String> resolveMovieUrls(Context context, MediaItem item) {
        SmartPlaybackRequest request = buildRequest(context, item, 0, 0);
        if (request == null) {
            return new ArrayList<>();
        }
        request.contentType = SmartContentType.MOVIE;
        return resolveWithProviders(context, request, Collections.singletonList(SmartContentType.MOVIE));
    }

    public List<String> resolveEpisodeUrls(Context context, MediaItem item, int season, int episode) {
        SmartPlaybackRequest request = buildRequest(context, item, season, episode);
        if (request == null) {
            return new ArrayList<>();
        }

        SmartContentType detected = request.contentType;
        List<SmartContentType> order = new ArrayList<>();
        if (detected == SmartContentType.DRAMA) {
            order.add(SmartContentType.DRAMA);
            order.add(SmartContentType.SERIES);
        } else if (detected == SmartContentType.ANIME) {
            order.add(SmartContentType.ANIME);
            order.add(SmartContentType.SERIES);
            order.add(SmartContentType.DRAMA);
        } else {
            order.add(SmartContentType.SERIES);
            order.add(SmartContentType.DRAMA);
        }
        return resolveWithProviders(context, request, order);
    }

    private List<String> resolveWithProviders(Context context, SmartPlaybackRequest request, List<SmartContentType> order) {
        SmartPlaybackResult cached = SmartCacheStore.getPlayback(context, request.cacheKey());
        if (cached != null && cached.hasPlayableUrls()) {
            return cached.toPlayableUrls();
        }

        for (SmartContentType targetType : order) {
            request.contentType = targetType;
            for (SmartProvider provider : providers) {
                if (!provider.supports(targetType)) {
                    continue;
                }
                try {
                    SmartPlaybackResult result = provider.resolve(request);
                    if (result != null && result.hasPlayableUrls()) {
                        SmartCacheStore.putPlayback(context, request.cacheKey(), result);
                        return result.toPlayableUrls();
                    }
                } catch (Exception e) {
                    Log.w(TAG, provider.getName() + " failed: " + e.getMessage());
                }
            }
        }

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
        request.mediaType = mediaType;
        request.contentType = detectContentType(item);
        request.seasonNumber = season > 0 ? season : 1;
        request.episodeNumber = episode > 0 ? episode : 1;

        if (request.imdbId == null || !request.imdbId.matches("tt\\d+")) {
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
}
