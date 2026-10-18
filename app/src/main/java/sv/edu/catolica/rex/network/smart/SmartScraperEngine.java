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
    private final FirebaseEndpointResolver firebaseResolver = new FirebaseEndpointResolver();

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

    public List<String> getCachedUrls(Context context, MediaItem item, int season, int episode) {
        if (context == null || item == null) return null;
        SmartPlaybackRequest request = buildRequest(context, item, season, episode);
        if (request == null) return null;
        SmartPlaybackResult cached = SmartCacheStore.getPlayback(context, request.cacheKey());
        if (cached != null && cached.hasPlayableUrls()) {
            List<String> cachedUrls = filterDeprecatedHosts(cached.toPlayableUrls());
            if (!cachedUrls.isEmpty()) {
                return cachedUrls;
            }
        }
        return null;
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

        // 1. Consulta directa a Firebase (< 50ms) antes de cualquier scraper en vivo
        int tmdbId = request.tmdbId;
        if (tmdbId <= 0 && request.title != null && !request.title.trim().isEmpty()) {
            MediaItem temp = new MediaItem(request.title, request.year != null ? request.year : "", "", "", 0);
            temp.setMediaType(request.mediaType);
            try {
                TmdbService.enrichMediaItem(temp);
                if (temp.getTmdbId() > 0) {
                    tmdbId = temp.getTmdbId();
                    request.tmdbId = tmdbId;
                }
            } catch (Exception ignored) { }
        }

        if (tmdbId > 0) {
            try {
                SmartPlaybackResult fbResult = firebaseResolver.resolve(
                        tmdbId,
                        request.mediaType,
                        request.seasonNumber,
                        request.episodeNumber,
                        request.year
                );
                if (fbResult != null && fbResult.hasPlayableUrls()) {
                    List<String> fbUrls = filterDeprecatedHosts(fbResult.toPlayableUrls());
                    if (!fbUrls.isEmpty()) {
                        Log.i(TAG, "Reproducción resuelta instantáneamente desde Firebase para TMDb " + tmdbId + " (" + fbUrls.size() + " servidores)");
                        SmartCacheStore.putPlayback(context, request.cacheKey(), fbResult);
                        return fbUrls;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error consultando Firebase para TMDb " + tmdbId + ": " + e.getMessage());
            }
        }

        boolean isCurrent = FirebaseEndpointResolver.isCurrentOrUpcomingYear(request.year);

        if (isCurrent) {
            // Para estrenos del año actual: priorizar fuentes de Cuevana 3 para evitar grabaciones CAM de AllCalidad
            Log.i(TAG, "Estreno de este año (" + request.year + "): priorizando fuentes de Cuevana 3 sobre AllCalidad");
            List<String> routerUrls = resolveThroughRouter(context, request);
            if (!routerUrls.isEmpty()) {
                return routerUrls;
            }

            // Fallback secundario si Cuevana no devolvió resultados
            List<String> allCalidadUrls = resolveAllCalidadUrls(request);
            if (allCalidadUrls != null && !allCalidadUrls.isEmpty()) {
                SmartPlaybackResult cacheResult = buildCacheResult(allCalidadUrls);
                SmartCacheStore.putPlayback(context, request.cacheKey(), cacheResult);
                return allCalidadUrls;
            }
        } else {
            // Contenidos de catálogo de años anteriores: AllCalidad Vimeos es HD oficial y veloz
            boolean isAllCalidadSource = request.source == null
                    || request.source.trim().isEmpty()
                    || "allcalidad".equalsIgnoreCase(request.source.trim());

            if (!isAllCalidadSource) {
                // Si el ítem proviene explícitamente de otra fuente (ej. Cuevana3 o Pelispedia),
                // resolver primero a través de esa fuente
                List<String> routerUrls = resolveThroughRouter(context, request);
                if (!routerUrls.isEmpty()) {
                    return routerUrls;
                }
            }

            List<String> urls = resolveAllCalidadUrls(request);
            if (urls != null && !urls.isEmpty()) {
                SmartPlaybackResult cacheResult = buildCacheResult(urls);
                SmartCacheStore.putPlayback(context, request.cacheKey(), cacheResult);
                return urls;
            }

            if (isAllCalidadSource) {
                // Fallback multi-fuente cuando AllCalidad no responde
                return resolveThroughRouter(context, request);
            }
        }

        return new ArrayList<>();
    }

    private List<String> resolveThroughRouter(Context context, SmartPlaybackRequest request) {
        try {
            MediaItem fallbackItem = new MediaItem(request.title, request.year != null ? request.year : "", "", "", 0);
            fallbackItem.setMediaType(request.mediaType);
            fallbackItem.setDetailUrl(request.detailUrl != null ? request.detailUrl : "");
            fallbackItem.setTmdbId(request.tmdbId);
            fallbackItem.setImdbId(request.imdbId);
            fallbackItem.setFuente(request.source != null ? request.source : "");
            fallbackItem.setAnio(request.year != null ? request.year : "");
            if (fallbackItem.getTmdbId() <= 0 || fallbackItem.getImdbId() == null || fallbackItem.getImdbId().isEmpty()) {
                TmdbService.ensureExternalIds(fallbackItem);
            }
            SourceRouter router = new SourceRouter(context, SourceRegistry.getInstance());
            SmartPlaybackResult fallback = request.isEpisodeRequest()
                    ? router.resolveEpisode(context, fallbackItem, request.seasonNumber, request.episodeNumber)
                    : router.resolveMovie(context, fallbackItem);
            if (fallback != null && fallback.hasPlayableUrls()) {
                SmartCacheStore.putPlayback(context, request.cacheKey(), fallback);
                List<String> fallbackUrls = filterDeprecatedHosts(fallback.toPlayableUrls());
                if (!fallbackUrls.isEmpty()) return fallbackUrls;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error en resolveThroughRouter: " + e.getMessage());
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

        if (context != null && item.getTmdbId() > 0) {
            SmartCacheStore.putIds(context, identity, item.getTmdbId(), item.getImdbId(), mediaType);
        }

        SmartPlaybackRequest request = new SmartPlaybackRequest();
        request.title = item.getTitulo();
        request.detailUrl = item.getDetailUrl();
        request.tmdbId = item.getTmdbId();
        request.imdbId = item.getImdbId();
        request.postId = Math.max(0, item.getPostId());
        request.episodePostId = 0;
        request.mediaType = mediaType;
        request.year = item.getAnio();
        request.source = item.getFuente();
        request.contentType = detectContentType(item);
        request.seasonNumber = season;
        request.episodeNumber = episode;

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

        // Si es episodio, únicamente podemos usar episodePostId.
        // NUNCA usar postId (id de serie/película) para reproducir un episodio en AllCalidad.
        int targetPostId;
        if (request.isEpisodeRequest()) {
            targetPostId = request.episodePostId;
        } else {
            targetPostId = request.postId;
            if (targetPostId <= 0 && request.title != null && !request.title.trim().isEmpty()) {
                try {
                    AllCalidadScraper.ContentItem found = AllCalidadScraper.findByTitleAndYear(
                            request.title, request.year, request.mediaType != null ? request.mediaType : "movies");
                    if (found != null && found.id > 0) {
                        targetPostId = found.id;
                        request.postId = targetPostId;
                    }
                } catch (Exception ignored) { }
            }
        }

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
            // Descartar páginas de catálogo/sitio web (no son reproductores de video)
            if (lc.contains("/ver-pelicula/") || lc.contains("/ver-serie/")
                    || lc.contains("/pelicula/") || lc.contains("/serie/")
                    || lc.contains("/episodio/")
                    || (lc.contains("cuevana3") && !lc.contains("player.cuevana3"))) {
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
