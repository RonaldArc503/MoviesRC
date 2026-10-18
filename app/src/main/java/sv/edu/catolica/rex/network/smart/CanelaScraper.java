package sv.edu.catolica.rex.network.smart;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.network.AllCalidadScraper;

public class CanelaScraper {

    private static final String TAG = "CanelaScraper";

    private static final String CATALOG_BASE = "https://catalog-service-cdn.cms.api.canela.tv";
    private static final String DATA_STORE_BASE = "https://data-store-cdn.cms.api.canela.tv";
    private static final String SEARCH_BASE = "https://search-cdn.cms.api.canela.tv";
    private static final String IMAGE_RESIZER_BASE = "https://image-resizer-cloud.cms.api.canela.tv";

    private static final String SERIES_TAB_ID = "45FE697E-DAC8-47E3-AE5D-01757A4F1D5D";
    private static final String CLIENT_PARAM = "canela-canela-web";
    private static final String REGION_PARAM = "SV";
    private static final String DT_PARAM = "web";
    private static final String ACL_PARAM = "es";

    private static final int TIMEOUT_MS = 15000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Caché en memoria para slugs, IDs y URLs de episodios
    private static final Map<String, String> slugToIdMap = new ConcurrentHashMap<>();
    private static final Map<String, String> episodePlayUrlMap = new ConcurrentHashMap<>();
    private static final Map<String, List<AllCalidadScraper.Season>> seasonsMemoryCache = new ConcurrentHashMap<>();

    public static class NovelasCatalog {
        public final List<MediaItem> heroItems;
        public final List<Section> sections;

        public NovelasCatalog(List<MediaItem> heroItems, List<Section> sections) {
            this.heroItems = heroItems != null ? heroItems : new ArrayList<>();
            this.sections = sections != null ? sections : new ArrayList<>();
        }
    }

    private static volatile NovelasCatalog cachedNovelasCatalog = null;
    private static volatile long lastNovelasCatalogFetchTime = 0L;
    private static final long SECTIONS_CACHE_TTL_MS = 30 * 60 * 1000L; // 30 min

    public CanelaScraper() {
    }

    private static void logE(String tag, String msg, Throwable tr) {
        try {
            Log.e(tag, msg, tr);
        } catch (Throwable t) {
            System.err.println(tag + ": " + msg);
        }
    }

    private static void logW(String tag, String msg) {
        try {
            Log.w(tag, msg);
        } catch (Throwable t) {
            System.err.println(tag + ": " + msg);
        }
    }

    /**
     * Obtiene el catálogo completo de novelas: carrusel hero y secciones temáticas.
     */
    public NovelasCatalog fetchNovelasCatalog() {
        long now = System.currentTimeMillis();
        if (cachedNovelasCatalog != null && !cachedNovelasCatalog.sections.isEmpty()
                && (now - lastNovelasCatalogFetchTime < SECTIONS_CACHE_TTL_MS)) {
            return cachedNovelasCatalog;
        }

        List<MediaItem> heroItems = new ArrayList<>();
        List<Section> sections = new ArrayList<>();
        Map<String, Boolean> seenHeroIds = new LinkedHashMap<>();

        try {
            String url = CATALOG_BASE + "/catalog/storefront/landingscreen"
                    + "?ipr=true&ivg=false&sfInfo=true&reg=" + REGION_PARAM
                    + "&dt=" + DT_PARAM + "&acl=" + ACL_PARAM
                    + "&client=" + CLIENT_PARAM
                    + "&tid=" + SERIES_TAB_ID
                    + "&cPageNumber=1&cPageSize=30";

            String jsonStr = httpGet(url);
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                return cachedNovelasCatalog != null ? cachedNovelasCatalog : new NovelasCatalog(heroItems, sections);
            }

            JSONObject root = new JSONObject(jsonStr);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return new NovelasCatalog(heroItems, sections);
            }

            JSONArray tabs = data.optJSONArray("t");
            if (tabs == null || tabs.length() == 0) {
                return new NovelasCatalog(heroItems, sections);
            }

            JSONObject seriesTab = tabs.optJSONObject(0);
            if (seriesTab == null) {
                return new NovelasCatalog(heroItems, sections);
            }

            JSONArray containers = seriesTab.optJSONArray("c");
            if (containers == null) {
                return new NovelasCatalog(heroItems, sections);
            }

            // 1. Extraer Hero Items de los primeros contenedores (Hero o destacados)
            for (int i = 0; i < Math.min(containers.length(), 6); i++) {
                JSONObject container = containers.optJSONObject(i);
                if (container == null) continue;
                JSONArray itemsArr = container.optJSONArray("cd");
                if (itemsArr == null) continue;

                for (int j = 0; j < itemsArr.length(); j++) {
                    JSONObject itemObj = itemsArr.optJSONObject(j);
                    if (itemObj == null) continue;
                    String cty = itemObj.optString("cty", "");
                    String chty = itemObj.optString("chty", "");
                    if ("channel".equalsIgnoreCase(cty) || "channel".equalsIgnoreCase(chty)) {
                        continue;
                    }
                    String itemId = itemObj.optString("id", "");
                    if (itemId.isEmpty() || seenHeroIds.containsKey(itemId)) {
                        continue;
                    }
                    MediaItem mediaItem = parseMediaItem(itemObj);
                    if (mediaItem != null) {
                        heroItems.add(mediaItem);
                        seenHeroIds.put(itemId, Boolean.TRUE);
                        if (heroItems.size() >= 6) {
                            break;
                        }
                    }
                }
                if (heroItems.size() >= 6) break;
            }

            // 2. Extraer secciones temáticas
            for (int i = 0; i < containers.length(); i++) {
                JSONObject container = containers.optJSONObject(i);
                if (container == null) continue;

                JSONArray itemsArr = container.optJSONArray("cd");
                if (itemsArr == null || itemsArr.length() == 0) {
                    continue;
                }

                String title = extractLocalizedName(container.optJSONArray("lon"));
                if (title == null || title.trim().isEmpty()) {
                    title = container.optString("tl", "Novelas");
                }

                String titleLower = title.toLowerCase(Locale.ROOT);
                if (titleLower.contains("banner") || titleLower.contains("continue watching")
                        || titleLower.contains("canales en vivo") || titleLower.contains("live channel")
                        || "hero".equalsIgnoreCase(titleLower)) {
                    continue;
                }

                List<MediaItem> items = new ArrayList<>();
                for (int j = 0; j < itemsArr.length(); j++) {
                    JSONObject itemObj = itemsArr.optJSONObject(j);
                    if (itemObj == null) continue;

                    String cty = itemObj.optString("cty", "");
                    String chty = itemObj.optString("chty", "");
                    if ("channel".equalsIgnoreCase(cty) || "channel".equalsIgnoreCase(chty)) {
                        continue;
                    }

                    MediaItem mediaItem = parseMediaItem(itemObj);
                    if (mediaItem != null) {
                        items.add(mediaItem);
                        // Respaldar hero items si faltaban
                        if (heroItems.size() < 6 && !seenHeroIds.containsKey(mediaItem.getPostId() + "")) {
                            String mediaId = itemObj.optString("id", "");
                            if (!mediaId.isEmpty() && !seenHeroIds.containsKey(mediaId)) {
                                heroItems.add(mediaItem);
                                seenHeroIds.put(mediaId, Boolean.TRUE);
                            }
                        }
                    }
                }

                if (!items.isEmpty()) {
                    sections.add(new Section(title, items));
                }
            }

            if (!sections.isEmpty()) {
                cachedNovelasCatalog = new NovelasCatalog(heroItems, sections);
                lastNovelasCatalogFetchTime = now;
            }

        } catch (Exception e) {
            logE(TAG, "Error fetching novelas catalog: " + e.getMessage(), e);
        }

        return cachedNovelasCatalog != null ? cachedNovelasCatalog : new NovelasCatalog(heroItems, sections);
    }

    /**
     * Obtiene todas las secciones temáticas del catálogo de series y novelas.
     */
    public List<Section> fetchNovelasSections() {
        return fetchNovelasCatalog().sections;
    }

    /**
     * Busca novelas y series en Canela TV mediante su API de búsqueda CDN.
     */
    public List<MediaItem> search(String query, int limit) {
        List<MediaItem> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return results;

        try {
            String encoded = URLEncoder.encode(query.trim(), "UTF-8");
            int pageSize = Math.max(1, Math.min(limit, 30));
            String url = SEARCH_BASE + "/content/search"
                    + "?mode=detail&st=published"
                    + "&term=" + encoded
                    + "&cty=tvseries"
                    + "&pageNumber=1&pageSize=" + pageSize
                    + "&client=" + CLIENT_PARAM
                    + "&dt=" + DT_PARAM
                    + "&reg=" + REGION_PARAM
                    + "&acl=" + ACL_PARAM;

            String jsonStr = httpGet(url);
            if (jsonStr == null || jsonStr.trim().isEmpty()) return results;

            JSONObject root = new JSONObject(jsonStr);
            JSONArray dataArr = root.optJSONArray("data");
            if (dataArr == null) return results;

            for (int i = 0; i < dataArr.length() && results.size() < limit; i++) {
                JSONObject itemObj = dataArr.optJSONObject(i);
                if (itemObj == null) continue;

                MediaItem mediaItem = parseMediaItem(itemObj);
                if (mediaItem != null) {
                    results.add(mediaItem);
                }
            }
        } catch (Exception e) {
            logE(TAG, "Error searching Canela TV: " + e.getMessage(), e);
        }

        return results;
    }

    /**
     * Obtiene las temporadas y episodios de una serie / novela de Canela TV.
     */
    public List<AllCalidadScraper.Season> getSeasons(String seriesIdOrUrl) {
        List<AllCalidadScraper.Season> seasons = new ArrayList<>();
        if (seriesIdOrUrl == null || seriesIdOrUrl.trim().isEmpty()) return seasons;

        String seriesId = extractSeriesId(seriesIdOrUrl);
        if (seriesId == null || seriesId.isEmpty()) {
            logW(TAG, "No se pudo extraer seriesId de: " + seriesIdOrUrl);
            return seasons;
        }

        List<AllCalidadScraper.Season> memCached = seasonsMemoryCache.get(seriesId);
        if (memCached != null && !memCached.isEmpty()) {
            return memCached;
        }

        try {
            String seasonsUrl = DATA_STORE_BASE + "/content/series/" + seriesId + "/seasons"
                    + "?pageNumber=1&pageSize=30"
                    + "&client=" + CLIENT_PARAM
                    + "&dt=" + DT_PARAM
                    + "&reg=sv&acl=" + ACL_PARAM;

            String seasonsJsonStr = httpGet(seasonsUrl);
            if (seasonsJsonStr == null || seasonsJsonStr.trim().isEmpty()) return seasons;

            JSONObject seasonsRoot = new JSONObject(seasonsJsonStr);
            JSONArray seasonsData = seasonsRoot.optJSONArray("data");

            List<JSONObject> seasonList = new ArrayList<>();
            if (seasonsData != null && seasonsData.length() > 0) {
                for (int i = 0; i < seasonsData.length(); i++) {
                    JSONObject sObj = seasonsData.optJSONObject(i);
                    if (sObj != null) seasonList.add(sObj);
                }
            } else {
                // Fallback: si no hay lista explícita de temporadas, tratar como temporada única
                JSONObject dummySeason = new JSONObject();
                dummySeason.put("id", seriesId);
                dummySeason.put("sn", 1);
                seasonList.add(dummySeason);
            }

            // Invertir si las temporadas vienen ordenadas descendentemente (ej. 101-111 primero)
            if (seasonList.size() > 1) {
                JSONObject first = seasonList.get(0);
                String firstTitle = extractLocalizedName(first.optJSONArray("lon"));
                if (firstTitle != null && (firstTitle.contains("101") || firstTitle.contains("81") || firstTitle.contains("61"))) {
                    Collections.reverse(seasonList);
                }
            }

            int seasonNumberCounter = 1;
            for (JSONObject seasonObj : seasonList) {
                String seasonId = seasonObj.optString("id", "");
                if (seasonId.isEmpty()) continue;

                int sNum = seasonObj.optInt("sn", seasonNumberCounter);
                if (sNum <= 0) sNum = seasonNumberCounter;

                AllCalidadScraper.Season seasonModel = new AllCalidadScraper.Season();
                seasonModel.seasonNumber = sNum;

                // Cargar episodios de este bloque / temporada
                String episodesUrl = DATA_STORE_BASE + "/content/series/" + seriesId + "/episodes"
                        + "?seasonId=" + seasonId
                        + "&pageNumber=1&pageSize=60"
                        + "&sortBy=epz&sortOrder=asc"
                        + "&client=" + CLIENT_PARAM
                        + "&dt=" + DT_PARAM
                        + "&reg=sv&acl=" + ACL_PARAM;

                String episodesJsonStr = httpGet(episodesUrl);
                if (episodesJsonStr != null && !episodesJsonStr.trim().isEmpty()) {
                    JSONObject epRoot = new JSONObject(episodesJsonStr);
                    JSONArray epData = epRoot.optJSONArray("data");
                    if (epData != null) {
                        for (int k = 0; k < epData.length(); k++) {
                            JSONObject epObj = epData.optJSONObject(k);
                            if (epObj == null) continue;

                            int epz = epObj.optInt("epz", k + 1);
                            String epTitle = extractLocalizedName(epObj.optJSONArray("lon"));
                            if (epTitle == null || epTitle.trim().isEmpty()) {
                                epTitle = "Episodio " + epz;
                            }
                            String epDesc = extractLocalizedDescription(epObj);
                            String epSlug = epObj.optString("nu", "");
                            String epId = epObj.optString("id", "");

                            String stillUrl = "";
                            if (!epId.isEmpty()) {
                                stillUrl = IMAGE_RESIZER_BASE + "/image/" + epId + "/0-16x9.png?width=400";
                            }

                            AllCalidadScraper.Episode ep = new AllCalidadScraper.Episode();
                            ep.seasonNumber = sNum;
                            ep.episodeNumber = epz;
                            ep.title = epTitle;
                            ep.overview = epDesc;
                            ep.stillUrl = stillUrl;

                            // Generar ID numérico único y determinista
                            String uniqueKey = seriesId + "_" + epSlug;
                            int numericId = Math.abs(uniqueKey.hashCode() % 1000000) + 200000;
                            ep.id = numericId;

                            // Cachear la URL de reproducción para este episodio
                            String playUrl = buildPlayerUrl(epSlug);
                            episodePlayUrlMap.put(String.valueOf(numericId), playUrl);
                            episodePlayUrlMap.put(seriesId + "_" + sNum + "_" + epz, playUrl);
                            if (!epSlug.isEmpty()) {
                                episodePlayUrlMap.put(epSlug, playUrl);
                            }

                            seasonModel.episodes.add(ep);
                        }
                    }
                }

                if (!seasonModel.episodes.isEmpty()) {
                    seasons.add(seasonModel);
                    seasonNumberCounter++;
                }
            }

            if (!seasons.isEmpty()) {
                seasonsMemoryCache.put(seriesId, seasons);
            }

        } catch (Exception e) {
            logE(TAG, "Error fetching Canela seasons: " + e.getMessage(), e);
        }

        return seasons;
    }

    /**
     * Resuelve la URL de reproducción para un episodio de Canela TV.
     */
    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode, int episodeId) {
        SmartPlaybackResult result = new SmartPlaybackResult();
        if (item == null) return result;

        // 1) Intentar recuperar por episodeId numérico
        if (episodeId > 0) {
            String cached = episodePlayUrlMap.get(String.valueOf(episodeId));
            if (cached != null && !cached.isEmpty()) {
                result.embedUrl = cached;
                result.providerName = "Novelas";
                result.hostUrls.add(cached);
                return result;
            }
        }

        String seriesId = extractSeriesId(item.getDetailUrl());
        if (seriesId != null && !seriesId.isEmpty()) {
            // 2) Intentar recuperar por clave seriesId_season_episode
            String key = seriesId + "_" + season + "_" + episode;
            String cached = episodePlayUrlMap.get(key);
            if (cached != null && !cached.isEmpty()) {
                result.embedUrl = cached;
                result.providerName = "Novelas";
                result.hostUrls.add(cached);
                return result;
            }

            // 3) Si no está en caché, cargar temporadas para poblar el mapa
            getSeasons(seriesId);
            cached = episodePlayUrlMap.get(key);
            if (cached != null && !cached.isEmpty()) {
                result.embedUrl = cached;
                result.providerName = "Novelas";
                result.hostUrls.add(cached);
                return result;
            }
        }

        return result;
    }

    public static String buildPlayerUrl(String episodeSlug) {
        if (episodeSlug == null || episodeSlug.trim().isEmpty()) return "";
        return "https://canela.tv/player/tvepisode/" + episodeSlug.trim();
    }

    public static String buildMoviePlayerUrl(String movieSlugOrId) {
        if (movieSlugOrId == null || movieSlugOrId.trim().isEmpty()) return "";
        return "https://canela.tv/player/movie/" + movieSlugOrId.trim();
    }

    public static String extractMovieSlugOrId(String urlOrSlug) {
        if (urlOrSlug == null || urlOrSlug.trim().isEmpty()) return "";
        String input = urlOrSlug.trim();

        // 1. URL player: /player/movie/<slug> o /player/tvepisode/<slug>
        Matcher playerMatcher = Pattern.compile("/player/(?:movie|tvepisode)/([a-zA-Z0-9_\\-]+)").matcher(input);
        if (playerMatcher.find()) {
            return playerMatcher.group(1);
        }

        // 2. URL movie detail: /movie/<slug> o /tvepisode/<slug>
        Matcher movieMatcher = Pattern.compile("/(?:movie|tvepisode)/([a-zA-Z0-9_\\-]+)").matcher(input);
        if (movieMatcher.find()) {
            return movieMatcher.group(1);
        }

        // 3. URL series: /series/<slug>
        Matcher seriesMatcher = Pattern.compile("/series/([a-zA-Z0-9_\\-]+)").matcher(input);
        if (seriesMatcher.find()) {
            return seriesMatcher.group(1);
        }

        // 4. Query param: canela_id=UUID
        Matcher idMatcher = Pattern.compile("canela_id=([0-9a-fA-F\\-]+)").matcher(input);
        if (idMatcher.find()) {
            return idMatcher.group(1);
        }

        // 5. UUID directo
        if (input.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            return input;
        }

        // 6. Slug directo en texto plano
        if (input.matches("^[a-zA-Z0-9_\\-]+$")) {
            return input;
        }

        return input;
    }

    public SmartPlaybackResult resolveMovie(MediaItem item) {
        SmartPlaybackResult result = new SmartPlaybackResult();
        if (item == null) return result;

        String detailUrl = item.getDetailUrl();
        if (detailUrl != null) {
            String trimmed = detailUrl.trim();
            if (trimmed.contains("/player/tvepisode/") || trimmed.contains("/player/movie/")) {
                int qIdx = trimmed.indexOf('?');
                String cleanUrl = qIdx != -1 ? trimmed.substring(0, qIdx) : trimmed;
                result.embedUrl = cleanUrl;
                result.providerName = "Novelas";
                result.hostUrls.add(cleanUrl);
                return result;
            }
            if (trimmed.contains("/tvepisode/")) {
                Matcher epMatcher = Pattern.compile("/tvepisode/([a-zA-Z0-9_\\-]+)").matcher(trimmed);
                if (epMatcher.find()) {
                    String epUrl = buildPlayerUrl(epMatcher.group(1));
                    result.embedUrl = epUrl;
                    result.providerName = "Novelas";
                    result.hostUrls.add(epUrl);
                    return result;
                }
            }
        }

        String slugOrId = extractMovieSlugOrId(detailUrl);
        if (slugOrId == null || slugOrId.isEmpty()) {
            slugOrId = extractSeriesId(detailUrl);
        }
        if (slugOrId == null || slugOrId.isEmpty()) {
            logW(TAG, "No se pudo extraer movie slug o id de: " + detailUrl);
            return result;
        }

        String playerUrl = buildMoviePlayerUrl(slugOrId);
        result.embedUrl = playerUrl;
        result.providerName = "Novelas";
        result.hostUrls.add(playerUrl);
        return result;
    }

    private MediaItem parseMediaItem(JSONObject obj) {
        if (obj == null) return null;
        String id = obj.optString("id", "");
        if (id.isEmpty()) return null;

        String title = extractLocalizedName(obj.optJSONArray("lon"));
        if (title == null || title.trim().isEmpty()) {
            title = obj.optString("tl", "Sin título");
        }

        String slug = obj.optString("nu", "");
        if (!slug.isEmpty()) {
            slugToIdMap.put(slug, id);
        }

        String overview = extractLocalizedDescription(obj);
        String year = extractYear(obj);

        String poster = IMAGE_RESIZER_BASE + "/image/" + id + "/0-7x10.png?width=300";
        String backdrop = IMAGE_RESIZER_BASE + "/image/" + id + "/0-16x9.png?width=700";

        String cty = obj.optString("cty", "").trim().toLowerCase(Locale.ROOT);
        boolean isSingleEpisode = "tvepisode".equals(cty);
        boolean isMovie = "movie".equals(cty) || "film".equals(cty) || isSingleEpisode;

        String slugOrId = slug.isEmpty() ? id : slug;
        String detailUrl;
        if (isSingleEpisode) {
            detailUrl = "https://canela.tv/player/tvepisode/" + slugOrId + "?canela_id=" + id;
        } else if (isMovie) {
            detailUrl = "https://canela.tv/movie/" + slugOrId + "?canela_id=" + id;
        } else {
            detailUrl = "https://canela.tv/series/" + slugOrId + "?canela_id=" + id;
        }

        MediaItem item = new MediaItem(title, year, poster, detailUrl, 0);
        item.setBackdrop(backdrop);
        item.setSynopsis(overview);
        item.setFuente("novelas");
        item.setMediaType(isMovie ? "movies" : "tvshows");

        return item;
    }

    private String extractSeriesId(String seriesIdOrUrl) {
        if (seriesIdOrUrl == null || seriesIdOrUrl.trim().isEmpty()) return "";
        String input = seriesIdOrUrl.trim();

        // Si ya es un UUID directamente
        if (input.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            return input;
        }

        // Si contiene ?canela_id=UUID
        Matcher idParamMatcher = Pattern.compile("canela_id=([0-9a-fA-F\\-]+)").matcher(input);
        if (idParamMatcher.find()) {
            return idParamMatcher.group(1);
        }

        // Si es una URL con slug: https://canela.tv/series/el-slug
        Matcher slugMatcher = Pattern.compile("/series/([a-zA-Z0-9_\\-]+)").matcher(input);
        if (slugMatcher.find()) {
            String slug = slugMatcher.group(1);
            String cachedId = slugToIdMap.get(slug);
            if (cachedId != null && !cachedId.isEmpty()) {
                return cachedId;
            }
            // Si el slug mismo tiene formato de UUID
            if (slug.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                return slug;
            }
        }

        // Buscar en el mapa de slugs directamente
        String directId = slugToIdMap.get(input);
        if (directId != null && !directId.isEmpty()) {
            return directId;
        }

        // Si no coincide con UUID ni URL, buscar la serie en Canela TV para resolver su UUID
        try {
            List<MediaItem> searchResults = search(input, 1);
            if (searchResults != null && !searchResults.isEmpty()) {
                MediaItem first = searchResults.get(0);
                if (first != null && first.getDetailUrl() != null) {
                    Matcher m = Pattern.compile("canela_id=([0-9a-fA-F\\-]+)").matcher(first.getDetailUrl());
                    if (m.find()) {
                        String resolvedId = m.group(1);
                        slugToIdMap.put(input, resolvedId);
                        return resolvedId;
                    }
                }
            }
        } catch (Exception ignored) {}

        return input;
    }

    private String extractLocalizedName(JSONArray lon) {
        if (lon == null || lon.length() == 0) return "";
        String fallback = "";
        for (int i = 0; i < lon.length(); i++) {
            JSONObject obj = lon.optJSONObject(i);
            if (obj == null) continue;
            String lang = obj.optString("lang", "");
            String n = obj.optString("n", "");
            if (n.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = n;
            if ("es-MX".equalsIgnoreCase(lang) || "es".equalsIgnoreCase(lang) || "es-ES".equalsIgnoreCase(lang)) {
                return n;
            }
        }
        return fallback;
    }

    private String extractLocalizedDescription(JSONObject obj) {
        if (obj == null) return "";
        JSONArray lod = obj.optJSONArray("lod");
        if (lod != null && lod.length() > 0) {
            String desc = extractLocalizedName(lod);
            if (!desc.isEmpty()) return desc;
        }
        JSONArray lold = obj.optJSONArray("lold");
        if (lold != null && lold.length() > 0) {
            String desc = extractLocalizedName(lold);
            if (!desc.isEmpty()) return desc;
        }
        return "";
    }

    private String extractYear(JSONObject obj) {
        if (obj == null) return "";
        String[] fields = {"st_dt", "ct", "ed_dt"};
        for (String field : fields) {
            String val = obj.optString(field, "");
            if (val.length() >= 4 && val.matches("^\\d{4}.*")) {
                return val.substring(0, 4);
            }
        }
        return "";
    }

    private static String httpGet(String urlString) throws IOException {
        if (urlString == null || urlString.trim().isEmpty()) return "";
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString.trim());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");

            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code + " for " + urlString);
            }

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
