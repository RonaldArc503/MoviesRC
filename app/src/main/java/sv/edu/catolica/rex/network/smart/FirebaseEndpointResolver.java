package sv.edu.catolica.rex.network.smart;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;

/**
 * Resuelve endpoints de reproducción pre-sincronizados directamente desde Firebase Realtime Database
 * sin necesidad de scrapear sitios web externos en vivo (< 50ms).
 */
public class FirebaseEndpointResolver {

    private static final String TAG = "FirebaseEndpointResolver";
    public static final String DEFAULT_RTDB_URL = "https://anime-rc-default-rtdb.firebaseio.com";
    private static final int TIMEOUT_MS = 2500; // Timeout agresivo de 2.5s para no demorar la UI

    private String databaseUrl = DEFAULT_RTDB_URL;

    public FirebaseEndpointResolver() {}

    public FirebaseEndpointResolver(String customDatabaseUrl) {
        if (customDatabaseUrl != null && !customDatabaseUrl.trim().isEmpty()) {
            this.databaseUrl = customDatabaseUrl.trim().replaceAll("/+$", "");
        }
    }

    /**
     * Resuelve los enlaces de reproducción para una película o serie dada por su TMDb ID.
     *
     * @param tmdbId   ID de TMDb (mayor a 0)
     * @param mediaType "movie" o "series" / "tvshows"
     * @param season   Temporada (si es serie, > 0)
     * @param episode  Episodio (si es serie, > 0)
     * @return SmartPlaybackResult con las URLs de reproducción o null si no se encontró en Firebase.
     */
    /**
     * Resuelve los enlaces de reproducción para una película o serie dada por su TMDb ID.
     */
    public SmartPlaybackResult resolve(int tmdbId, String mediaType, int season, int episode) {
        return resolve(tmdbId, mediaType, season, episode, null);
    }

    /**
     * Resuelve los enlaces de reproducción para una película o serie dada por su TMDb ID y año.
     * Si el año corresponde al año actual, prioriza fuentes de Cuevana 3 (especialmente voesx y doodstream)
     * para evitar grabaciones CAM de AllCalidad.
     *
     * @param tmdbId   ID de TMDb (mayor a 0)
     * @param mediaType "movie" o "series" / "tvshows"
     * @param season   Temporada (si es serie, > 0)
     * @param episode  Episodio (si es serie, > 0)
     * @param year     Año de estreno (ej: "2026")
     * @return SmartPlaybackResult con las URLs de reproducción o null si no se encontró en Firebase.
     */
    public SmartPlaybackResult resolve(int tmdbId, String mediaType, int season, int episode, String year) {
        if (tmdbId <= 0) {
            return null;
        }

        String urlString = databaseUrl + "/catalog/endpoints/" + tmdbId + ".json";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "RexMovies-Android/1.0");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "TMDb " + tmdbId + " no encontrado en Firebase (HTTP " + responseCode + ")");
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String rawJson = sb.toString().trim();
            if (rawJson.isEmpty() || "null".equalsIgnoreCase(rawJson)) {
                return null;
            }

            JSONObject json = new JSONObject(rawJson);
            return parsePlaybackResult(json, season, episode, year);

        } catch (Exception e) {
            Log.w(TAG, "Error consultando endpoint en Firebase para TMDb " + tmdbId + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Obtiene la lista de temporadas y episodios pre-almacenados en Firebase para una serie dada por su tmdbId.
     * Devuelve null si no existe o no tiene temporadas registradas.
     */
    public List<AllCalidadScraper.Season> getSeasons(int tmdbId) {
        if (tmdbId <= 0) return null;
        String urlString = databaseUrl + "/catalog/endpoints/" + tmdbId + ".json";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "RexMovies-Android/1.0");
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            String raw = sb.toString().trim();
            if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return null;

            JSONObject json = new JSONObject(raw);
            if (!json.has("seasons")) return null;

            JSONArray seasonsArr = json.optJSONArray("seasons");
            if (seasonsArr == null || seasonsArr.length() == 0) return null;

            List<AllCalidadScraper.Season> result = new ArrayList<>();
            for (int i = 0; i < seasonsArr.length(); i++) {
                JSONObject sObj = seasonsArr.optJSONObject(i);
                if (sObj == null) continue;
                AllCalidadScraper.Season s = new AllCalidadScraper.Season();
                s.seasonNumber = sObj.optInt("season_number", i + 1);
                JSONArray epArr = sObj.optJSONArray("episodes");
                if (epArr != null) {
                    for (int j = 0; j < epArr.length(); j++) {
                        JSONObject epObj = epArr.optJSONObject(j);
                        if (epObj == null) continue;
                        AllCalidadScraper.Episode ep = new AllCalidadScraper.Episode();
                        ep.seasonNumber = epObj.optInt("season_number", s.seasonNumber);
                        ep.episodeNumber = epObj.optInt("episode_number", j + 1);
                        ep.title = epObj.optString("title", "Episodio " + ep.episodeNumber);
                        ep.stillUrl = epObj.optString("thumbnail", "");
                        ep.code = epObj.optString("detail_url", "");
                        s.episodes.add(ep);
                    }
                }
                if (!s.episodes.isEmpty()) {
                    result.add(s);
                }
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            Log.w(TAG, "Error obteniendo temporadas desde Firebase para TMDb " + tmdbId + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Evalúa si un string de año corresponde al año actual en curso o más reciente.
     */
    public static boolean isCurrentOrUpcomingYear(String yearStr) {
        if (yearStr == null || yearStr.trim().isEmpty()) {
            return false;
        }
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(20\\d{2})\\b").matcher(yearStr);
        if (m.find()) {
            try {
                int parsedYear = Integer.parseInt(m.group(1));
                return parsedYear >= currentYear;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    /**
     * Valida que una URL corresponda a un reproductor o embed de video reproducible directamente
     * y NO a una página web de catálogo/navegación de sitios (ej. /ver-pelicula/, /ver-serie/).
     */
    public static boolean isDirectPlayableEmbed(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String lc = url.trim().toLowerCase(java.util.Locale.ROOT);
        if (!lc.startsWith("http://") && !lc.startsWith("https://")) {
            return false;
        }

        // Descartar explícitamente páginas web de catálogo/navegación
        if (lc.contains("/ver-pelicula/") || lc.contains("/ver-serie/")
                || lc.contains("/pelicula/") || lc.contains("/serie/")
                || lc.contains("/episodio/")
                || (lc.contains("cuevana3.eu") && !lc.contains("player.cuevana3.eu"))) {
            return false;
        }

        // Descartar dominios genéricos o de rastreo
        if (lc.contains("google.") || lc.contains("facebook.") || lc.contains("twitter.")
                || lc.contains("recaptcha") || lc.contains("analytics")) {
            return false;
        }

        return true;
    }

    /**
     * Asigna puntuación de calidad y estabilidad según el servidor y si se trata de un estreno del año actual.
     * En estrenos del año actual, se da máxima prioridad a Cuevana 3 y a los servidores voesx y doodstream.
     * Descarta cualquier endpoint que sea una página web de detalle o no reproducible.
     */
    public static int getEndpointScore(JSONObject endpoint, boolean isCurrentYear) {
        if (endpoint == null) return 0;
        String type = endpoint.optString("type", "").toLowerCase(java.util.Locale.ROOT);
        String server = endpoint.optString("server", "").toLowerCase(java.util.Locale.ROOT);
        String url = endpoint.optString("url", "").toLowerCase(java.util.Locale.ROOT);

        // Descartar páginas de detalle o URLs que no sean reproductores de video directos
        if ("detail_page".equals(type) || "cuevana_detail".equals(server) || !isDirectPlayableEmbed(url)) {
            return 0;
        }

        String provider = endpoint.optString("provider", "").toLowerCase(java.util.Locale.ROOT);
        String quality = endpoint.optString("quality", "").toLowerCase(java.util.Locale.ROOT);

        if (isCurrentYear) {
            // Estrenos año actual: Cuevana3 tiene versiones HD reales; AllCalidad suele subir CAM
            boolean isCuevana = provider.contains("cuevana") || !provider.contains("allcalidad");
            if (isCuevana) {
                // Servidor 1: doodstream (reproducción directa y fluida sin captcha)
                if (server.contains("dood") || url.contains("dood") || url.contains("ds2play") || url.contains("doods")) {
                    return 100; // doodstream: TOP 1
                }
                // Servidor 2: streamwish / vidhide / filemoon (rápido y sin captcha)
                if (server.contains("streamwish") || url.contains("streamwish") || url.contains("wishembed")) {
                    return 90;  // streamwish: TOP 2
                }
                if (server.contains("vidhide") || url.contains("vidhide") || url.contains("minochinos") || url.contains("bysedikamoum")) {
                    return 85;  // vidhide: TOP 2 alternativo
                }
                if (server.contains("filemoon") || server.contains("filelions") || url.contains("filemoon")) {
                    return 82;
                }
                // Servidor 3: voesx (colocado en Servidor 3 para evitar el captcha inicial en WebView)
                if (server.contains("voe") || url.contains("voe.sx") || url.contains("voe.network") || url.contains("voesx")) {
                    return 80; // voesx: TOP 3
                }
                return 60; // otros servidores de Cuevana
            } else {
                // AllCalidad en estrenos recientes suele ser CAM; queda como fallback
                if (quality.contains("cam") || quality.contains("ts")) {
                    return 10;
                }
                return 35;
            }
        } else {
            // Películas de catálogo (años anteriores): AllCalidad Vimeos es HD digital limpio y veloz
            if (provider.contains("allcalidad") || server.contains("vimeos") || url.contains("vimeos.net")) {
                return 100;
            }
            if (server.contains("voe") || url.contains("voe.sx") || url.contains("voesx")) {
                return 85;
            }
            if (server.contains("dood") || url.contains("dood") || url.contains("ds2play")) {
                return 80;
            }
            return 60;
        }
    }

    private static class ScoredCandidate {
        final String url;
        final int score;
        ScoredCandidate(String url, int score) {
            this.url = url;
            this.score = score;
        }
    }

    private SmartPlaybackResult parsePlaybackResult(JSONObject json, int targetSeason, int targetEpisode, String targetYear) {
        if (json == null) return null;

        SmartPlaybackResult result = new SmartPlaybackResult();
        result.providerName = "FirebaseCatalog";

        String kind = json.optString("kind", "movie");
        boolean isSeries = "series".equalsIgnoreCase(kind) || "tvshow".equalsIgnoreCase(kind) || targetSeason > 0;

        Set<String> hostUrls = new LinkedHashSet<>();
        String episodeDetailUrl = null;

        // Si es serie y se especificó temporada y episodio, buscar si existen episodios indexados
        if (isSeries && targetSeason > 0 && targetEpisode > 0 && json.has("seasons")) {
            JSONArray seasons = json.optJSONArray("seasons");
            if (seasons != null) {
                for (int s = 0; s < seasons.length(); s++) {
                    JSONObject seasonObj = seasons.optJSONObject(s);
                    if (seasonObj == null) continue;
                    int sNum = seasonObj.optInt("season_number", 0);
                    if (sNum == targetSeason) {
                        JSONArray episodes = seasonObj.optJSONArray("episodes");
                        if (episodes != null) {
                            for (int e = 0; e < episodes.length(); e++) {
                                JSONObject epObj = episodes.optJSONObject(e);
                                if (epObj == null) continue;
                                int epNum = epObj.optInt("episode_number", 0);
                                if (epNum == targetEpisode) {
                                    String directUrl = epObj.optString("url", "");
                                    if (directUrl.isEmpty()) {
                                        directUrl = epObj.optString("embed_url", "");
                                    }
                                    if (!directUrl.isEmpty() && isDirectPlayableEmbed(directUrl)) {
                                        hostUrls.add(directUrl);
                                        if (result.embedUrl == null || result.embedUrl.isEmpty()) {
                                            result.embedUrl = directUrl;
                                        }
                                    }
                                    JSONArray hostArr = epObj.optJSONArray("host_urls");
                                    if (hostArr != null) {
                                        for (int k = 0; k < hostArr.length(); k++) {
                                            String h = hostArr.optString(k, "");
                                            if (!h.isEmpty() && isDirectPlayableEmbed(h)) {
                                                hostUrls.add(h);
                                                if (result.embedUrl == null || result.embedUrl.isEmpty()) {
                                                    result.embedUrl = h;
                                                }
                                            }
                                        }
                                    }
                                    JSONArray srvArr = epObj.optJSONArray("servers");
                                    if (srvArr != null) {
                                        for (int k = 0; k < srvArr.length(); k++) {
                                            String srvUrl = srvArr.optString(k, "");
                                            if (!srvUrl.isEmpty() && isDirectPlayableEmbed(srvUrl)) {
                                                hostUrls.add(srvUrl);
                                                if (result.embedUrl == null || result.embedUrl.isEmpty()) {
                                                    result.embedUrl = srvUrl;
                                                }
                                            }
                                        }
                                    }
                                    String epDetail = epObj.optString("detail_url", "");
                                    if (!epDetail.isEmpty()) {
                                        episodeDetailUrl = epDetail;
                                    }
                                    break;
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }

        // Determinar si el contenido corresponde al año actual
        String effectiveYear = targetYear;
        if ((effectiveYear == null || effectiveYear.trim().isEmpty()) && json.has("year")) {
            effectiveYear = json.optString("year", "");
        }
        boolean isCurrent = isCurrentOrUpcomingYear(effectiveYear);

        // Si es serie y no hay embeds directos pre-indexados para el episodio, resolver en vivo desde el detailUrl
        if (isSeries && targetSeason > 0 && hostUrls.isEmpty() && episodeDetailUrl != null && !episodeDetailUrl.isEmpty()) {
            try {
                if (episodeDetailUrl.contains("tudorama.com")) {
                    TuDoramaScraper scraper = new TuDoramaScraper();
                    SmartPlaybackResult liveEp = scraper.resolveEpisode(episodeDetailUrl);
                    if (liveEp != null && liveEp.hasPlayableUrls()) {
                        return liveEp;
                    }
                } else {
                    Cuevana3Scraper scraper = new Cuevana3Scraper();
                    String title = json.optString("title", "");
                    MediaItem tempItem = new MediaItem(title, effectiveYear, "", episodeDetailUrl, 0);
                    tempItem.setMediaType("tvshows");
                    SmartPlaybackResult liveEp = scraper.resolveEpisode(tempItem, targetSeason, targetEpisode);
                    if (liveEp != null && liveEp.hasPlayableUrls()) {
                        return liveEp;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Fallback de scraping en vivo para episodio falló: " + e.getMessage());
            }
        }

        // Endpoints generales / de películas con priorización inteligente de servidores
        List<ScoredCandidate> candidates = new ArrayList<>();
        String cuevanaDetailUrl = null;

        // Capturar detail_url de cuevana si está en sources
        if (json.has("sources")) {
            JSONObject sources = json.optJSONObject("sources");
            if (sources != null && sources.has("cuevana")) {
                JSONObject cuevana = sources.optJSONObject("cuevana");
                if (cuevana != null) {
                    cuevanaDetailUrl = cuevana.optString("detail_url", null);
                }
            }
        }

        JSONArray endpoints = json.optJSONArray("endpoints");
        if (endpoints != null && (!isSeries || targetSeason <= 0)) {
            for (int i = 0; i < endpoints.length(); i++) {
                JSONObject ep = endpoints.optJSONObject(i);
                if (ep == null) continue;
                String epUrl = ep.optString("url", "");
                if (epUrl.isEmpty()) continue;

                // Capturar detail_url si existe como endpoint de tipo detail_page
                String type = ep.optString("type", "");
                String srv = ep.optString("server", "");
                if ("detail_page".equalsIgnoreCase(type) || "cuevana_detail".equalsIgnoreCase(srv) || epUrl.contains("/ver-pelicula/")) {
                    if (cuevanaDetailUrl == null || cuevanaDetailUrl.isEmpty()) {
                        cuevanaDetailUrl = epUrl;
                    }
                    continue; // NO agregar como candidato reproducible
                }

                if (!isDirectPlayableEmbed(epUrl)) {
                    continue;
                }

                int score = getEndpointScore(ep, isCurrent);
                if (score > 0) {
                    candidates.add(new ScoredCandidate(epUrl, score));
                }
            }
        }

        String primary = json.optString("primary_endpoint", "");
        if (!primary.isEmpty() && (!isSeries || targetSeason <= 0)) {
            if (primary.contains("/ver-pelicula/") || primary.contains("/ver-serie/")) {
                if (cuevanaDetailUrl == null || cuevanaDetailUrl.isEmpty()) {
                    cuevanaDetailUrl = primary;
                }
            } else if (isDirectPlayableEmbed(primary)) {
                boolean alreadyInList = false;
                for (ScoredCandidate c : candidates) {
                    if (c.url.equalsIgnoreCase(primary)) {
                        alreadyInList = true;
                        break;
                    }
                }
                if (!alreadyInList) {
                    try {
                        JSONObject synth = new JSONObject();
                        synth.put("url", primary);
                        synth.put("provider", primary.contains("vimeos") ? "allcalidad" : "cuevana3");
                        synth.put("server", primary.contains("vimeos") ? "vimeos" : "player");
                        int score = getEndpointScore(synth, isCurrent);
                        if (score > 0) {
                            candidates.add(new ScoredCandidate(primary, score));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // Si no hay servidores de video listos en Firebase pero tenemos la URL de Cuevana, resolver en vivo
        if (candidates.isEmpty() && cuevanaDetailUrl != null && !cuevanaDetailUrl.isEmpty() && (!isSeries || targetSeason <= 0)) {
            try {
                Log.d(TAG, "Resolviendo servidores reales de video en vivo para: " + cuevanaDetailUrl);
                Cuevana3Scraper scraper = new Cuevana3Scraper();
                String title = json.optString("title", "");
                MediaItem tempItem = new MediaItem(title, effectiveYear, "", cuevanaDetailUrl, 0);
                tempItem.setMediaType("movies");
                SmartPlaybackResult liveResult = scraper.resolveMovie(tempItem);
                if (liveResult != null && liveResult.hasPlayableUrls()) {
                    return liveResult;
                }
            } catch (Exception e) {
                Log.w(TAG, "Fallback de scraping en vivo para Cuevana falló: " + e.getMessage());
            }
        }

        // Ordenar candidatos por puntuación descendente
        java.util.Collections.sort(candidates, (c1, c2) -> Integer.compare(c2.score, c1.score));

        for (ScoredCandidate c : candidates) {
            if (isDirectPlayableEmbed(c.url)) {
                hostUrls.add(c.url);
                if (result.embedUrl == null || result.embedUrl.isEmpty()) {
                    result.embedUrl = c.url;
                }
            }
        }

        result.hostUrls.addAll(hostUrls);

        if (result.hasPlayableUrls()) {
            return result;
        }

        return null;
    }
}
