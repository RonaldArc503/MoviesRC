package sv.edu.catolica.rex.network.smart;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;

/**
 * Scraper especializado para TuDorama (https://tudorama.com), enfocado en doramas
 * con doblaje al Español Latino ("lang": "es").
 */
public class TuDoramaScraper {

    private static final String TAG = "TuDoramaScraper";
    public static final String BASE_URL = "https://tudorama.com";
    private static final String AJAX_URL = "https://tudorama.com/wp-admin/admin-ajax.php";
    private static final int TIMEOUT_MS = 15000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Map<String, List<AllCalidadScraper.Season>> seasonsMemoryCache = new ConcurrentHashMap<>();

    public TuDoramaScraper() {
    }

    public static int stableEpisodeId(String episodeUrl) {
        if (episodeUrl == null || episodeUrl.trim().isEmpty()) return 0;
        int id = episodeUrl.trim().hashCode() & 0x7fffffff;
        return id == 0 ? 1 : id;
    }

    /**
     * Búsqueda en TuDorama.
     */
    public List<MediaItem> search(String query, int limit) {
        List<MediaItem> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return results;
        }

        try {
            String encodedQuery = URLEncoder.encode(query.trim(), "UTF-8");
            String searchUrl = BASE_URL + "/?s=" + encodedQuery;
            String html = httpGet(searchUrl, BASE_URL);

            Pattern articlePattern = Pattern.compile("<article class=\"ipst\">([\\s\\S]*?)</article>");
            Matcher articleMatcher = articlePattern.matcher(html);

            while (articleMatcher.find()) {
                String block = articleMatcher.group(1);
                Matcher linkM = Pattern.compile("href=\"([^\"]+)\"").matcher(block);
                Matcher titleM = Pattern.compile("<h3 class=\"ipst__title\"><a [^>]+>([^<]+)</a>").matcher(block);
                Matcher imgM = Pattern.compile("src=\"([^\"]+)\"").matcher(block);
                Matcher scoreM = Pattern.compile("class=\"avg-score[^\"]*\">\\s*<i[^>]*></i>\\s*([0-9.]+)").matcher(block);

                if (linkM.find() && titleM.find()) {
                    String detailUrl = linkM.group(1).trim();
                    String title = titleM.group(1).trim();
                    String posterUrl = imgM.find() ? imgM.group(1).trim() : "";
                    double score = scoreM.find() ? Double.parseDouble(scoreM.group(1)) : 0.0;

                    MediaItem item = new MediaItem(title, "", posterUrl, detailUrl, 0);
                    item.setMediaType("tvshows");
                    item.setFuente("tudorama");
                    item.setRating(score);
                    results.add(item);

                    if (limit > 0 && results.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error en búsqueda de TuDorama para '" + query + "': " + e.getMessage());
        }

        return results;
    }

    /**
     * Obtiene la lista completa de temporadas y episodios de un Dorama en TuDorama.
     */
    public List<AllCalidadScraper.Season> getSeasons(String seriesUrlOrSlug) {
        if (seriesUrlOrSlug == null || seriesUrlOrSlug.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String url = seriesUrlOrSlug.startsWith("http") ? seriesUrlOrSlug.trim() : (BASE_URL + "/serie/" + seriesUrlOrSlug.trim() + "/");
        if (seasonsMemoryCache.containsKey(url)) {
            return seasonsMemoryCache.get(url);
        }

        List<AllCalidadScraper.Season> result = new ArrayList<>();
        try {
            String html = httpGet(url, BASE_URL);
            Map<Integer, AllCalidadScraper.Season> seasonsMap = new LinkedHashMap<>();

            Matcher epsDivMatcher = Pattern.compile("class=\"eps\"[^>]*data-tmdb-id=\"([^\"]+)\"[^>]*data-season-number=\"([^\"]+)\"[^>]*data-nonce=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html);
            int tmdbId = 0;
            int defaultSeason = 1;
            String nonce = "";
            if (epsDivMatcher.find()) {
                try {
                    tmdbId = Integer.parseInt(epsDivMatcher.group(1));
                    defaultSeason = Integer.parseInt(epsDivMatcher.group(2));
                } catch (NumberFormatException ignored) {}
                nonce = epsDivMatcher.group(3);
            }

            // Parsear episodios iniciales del HTML
            Pattern epPattern = Pattern.compile("<li class=\"lep\"([^>]*)>([\\s\\S]*?)</li>");
            Matcher epMatcher = epPattern.matcher(html);

            while (epMatcher.find()) {
                String attr = epMatcher.group(1);
                String block = epMatcher.group(2);

                Matcher aM = Pattern.compile("href=\"([^\"]+)\"").matcher(block);
                Matcher titleM = Pattern.compile("<h3 class=\"lep__title\">([^<]+)</h3>").matcher(block);
                Matcher epNumM = Pattern.compile("Episodio\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(block);
                Matcher sNumM = Pattern.compile("S(\\d+)", Pattern.CASE_INSENSITIVE).matcher(block);
                Matcher thumbM = Pattern.compile("<img[^>]+src=\"([^\"]+)\"").matcher(block);

                if (aM.find() && epNumM.find()) {
                    int epNum = Integer.parseInt(epNumM.group(1));
                    int sNum = sNumM.find() ? Integer.parseInt(sNumM.group(1)) : defaultSeason;

                    AllCalidadScraper.Season s = seasonsMap.computeIfAbsent(sNum, k -> {
                        AllCalidadScraper.Season newS = new AllCalidadScraper.Season();
                        newS.seasonNumber = k;
                        newS.episodes = new ArrayList<>();
                        return newS;
                    });

                    AllCalidadScraper.Episode ep = new AllCalidadScraper.Episode();
                    ep.seasonNumber = sNum;
                    ep.episodeNumber = epNum;
                    ep.title = titleM.find() ? titleM.group(1).trim() : ("Episodio " + epNum);
                    ep.stillUrl = thumbM.find() ? thumbM.group(1).trim() : "";
                    ep.code = aM.group(1).trim(); // Almacenar permalink del episodio
                    ep.id = stableEpisodeId(ep.code);
                    s.episodes.add(ep);
                }
            }

            // Paginación AJAX para series con más de 8 episodios
            if (tmdbId > 0 && nonce != null && !nonce.isEmpty()) {
                int offset = 8;
                boolean hasMore = true;

                while (hasMore) {
                    String postBody = "action=corvus_get_episodes&nonce=" + URLEncoder.encode(nonce, "UTF-8")
                            + "&post_id=" + tmdbId
                            + "&season=" + defaultSeason
                            + "&results=8&offset=" + offset
                            + "&order=DESC";

                    String jsonStr = httpPost(AJAX_URL, postBody, url);
                    if (jsonStr == null || jsonStr.trim().isEmpty() || !jsonStr.startsWith("{")) {
                        break;
                    }

                    JSONObject json = new JSONObject(jsonStr);
                    if (json.optBoolean("success", false) && json.has("data")) {
                        JSONObject data = json.getJSONObject("data");
                        JSONArray resultsArray = data.optJSONArray("results");
                        if (resultsArray != null && resultsArray.length() > 0) {
                            for (int i = 0; i < resultsArray.length(); i++) {
                                JSONObject epJson = resultsArray.getJSONObject(i);
                                int epNum = epJson.optInt("episode_number", 0);
                                int sNum = epJson.optInt("season_number", defaultSeason);

                                AllCalidadScraper.Season s = seasonsMap.computeIfAbsent(sNum, k -> {
                                    AllCalidadScraper.Season newS = new AllCalidadScraper.Season();
                                    newS.seasonNumber = k;
                                    newS.episodes = new ArrayList<>();
                                    return newS;
                                });

                                // Evitar duplicados
                                boolean exists = false;
                                for (AllCalidadScraper.Episode existingEp : s.episodes) {
                                    if (existingEp.episodeNumber == epNum) {
                                        exists = true;
                                        break;
                                    }
                                }

                                if (!exists && epNum > 0) {
                                    AllCalidadScraper.Episode ep = new AllCalidadScraper.Episode();
                                    ep.id = 0;
                                    ep.seasonNumber = sNum;
                                    ep.episodeNumber = epNum;
                                    ep.title = epJson.optString("title", "Episodio " + epNum);
                                    ep.stillUrl = epJson.optString("episode_image", "");
                                    ep.code = epJson.optString("permalink", "");
                                    ep.id = stableEpisodeId(ep.code);
                                    s.episodes.add(ep);
                                }
                            }
                            hasMore = data.optBoolean("hasMore", false);
                            offset += 8;
                        } else {
                            hasMore = false;
                        }
                    } else {
                        hasMore = false;
                    }
                }
            }

            // Ordenar temporadas y episodios
            for (AllCalidadScraper.Season s : seasonsMap.values()) {
                Collections.sort(s.episodes, (a, b) -> Integer.compare(a.episodeNumber, b.episodeNumber));
                result.add(s);
            }
            Collections.sort(result, (a, b) -> Integer.compare(a.seasonNumber, b.seasonNumber));

            if (!result.isEmpty()) {
                seasonsMemoryCache.put(url, result);
            }

        } catch (Exception e) {
            Log.w(TAG, "Error obteniendo temporadas de TuDorama (" + url + "): " + e.getMessage());
        }

        return result;
    }

    /**
     * Resuelve los enlaces de reproducción de un episodio, priorizando audio en Español Latino.
     */
    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode, int episodePostId) {
        if (item == null) return null;

        String episodeUrl = item.getDetailUrl();
        // Si el detailUrl es de la serie completa, buscar el permalink del episodio específico
        if (episodeUrl != null && !episodeUrl.contains("/ver/")) {
            List<AllCalidadScraper.Season> seasons = getSeasons(episodeUrl);
            for (AllCalidadScraper.Season s : seasons) {
                if (s.seasonNumber == season) {
                    for (AllCalidadScraper.Episode ep : s.episodes) {
                        if (ep.episodeNumber == episode && ep.code != null && !ep.code.isEmpty()) {
                            episodeUrl = ep.code;
                            break;
                        }
                    }
                }
            }
        }

        if (episodeUrl == null || !episodeUrl.contains("tudorama.com")) {
            return null;
        }

        return resolveEpisode(episodeUrl);
    }

    /**
     * Resuelve los enlaces de reproducción directamente desde el permalink del episodio.
     */
    public SmartPlaybackResult resolveEpisode(String episodeUrl) {
        if (episodeUrl == null || episodeUrl.trim().isEmpty()) {
            return null;
        }

        try {
            String html = httpGet(episodeUrl, BASE_URL);
            Matcher dropdownMatcher = Pattern.compile("class=\"ep__dropdown\"[^>]*data-id=\"([^\"]+)\"[^>]*data-nonce=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html);
            if (!dropdownMatcher.find()) {
                Log.w(TAG, "No se encontró ep__dropdown en " + episodeUrl);
                return null;
            }

            String postId = dropdownMatcher.group(1);
            String nonce = dropdownMatcher.group(2);

            String postBody = "action=corvus_get_servers&nonce=" + URLEncoder.encode(nonce, "UTF-8")
                    + "&post_id=" + URLEncoder.encode(postId, "UTF-8");

            String jsonStr = httpPost(AJAX_URL, postBody, episodeUrl);
            if (jsonStr == null || jsonStr.trim().isEmpty() || !jsonStr.startsWith("[")) {
                Log.w(TAG, "Respuesta vacía o inválida de servidores para " + episodeUrl);
                return null;
            }

            JSONArray servers = new JSONArray(jsonStr);
            List<JSONObject> latinoServers = new ArrayList<>();
            List<JSONObject> fallbackServers = new ArrayList<>();

            for (int i = 0; i < servers.length(); i++) {
                JSONObject s = servers.getJSONObject(i);
                String lang = s.optString("lang", "").trim().toLowerCase(java.util.Locale.ROOT);
                String name = s.optString("name", "").trim().toLowerCase(java.util.Locale.ROOT);

                if ("es".equalsIgnoreCase(lang) || name.startsWith("esp:")) {
                    latinoServers.add(s);
                } else {
                    fallbackServers.add(s);
                }
            }

            // Usar servidores en Latino si existen, de lo contrario fallback
            List<JSONObject> targets = !latinoServers.isEmpty() ? latinoServers : fallbackServers;
            Set<String> directUrls = new LinkedHashSet<>();
            Set<String> hostUrls = new LinkedHashSet<>();

            for (JSONObject sObj : targets) {
                String rawUrl = sObj.optString("url", "").trim();
                if (rawUrl.isEmpty()) continue;

                // Extraer el embed directo según el tipo de servidor
                String embedUrl = resolveDirectEmbed(rawUrl);
                if (embedUrl != null && !embedUrl.isEmpty()) {
                    if (embedUrl.startsWith("https://pixeldrain.com/api/file/")) {
                        directUrls.add(embedUrl);
                    } else {
                        hostUrls.add(embedUrl);
                    }
                }
            }

            // A direct MP4 plays with Rex's native controls and needs no provider prompt.
            directUrls.addAll(hostUrls);
            hostUrls = directUrls;

            if (hostUrls.isEmpty()) {
                return null;
            }

            SmartPlaybackResult result = new SmartPlaybackResult();
            result.providerName = "TuDorama";
            result.embedUrl = hostUrls.iterator().next();
            result.hostUrls.addAll(hostUrls);
            return result;

        } catch (Exception e) {
            Log.w(TAG, "Error resolviendo episodio de TuDorama (" + episodeUrl + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Mapea URLs intermedias de cdn.tudorama.com a los reproductores reales correspondientes
     * (Filemoon, StreamHG, Minochinos, Abyss Player, PixelDrain).
     */
    public static String resolveDirectEmbed(String cdnUrl) {
        if (cdnUrl == null || cdnUrl.trim().isEmpty()) {
            return null;
        }

        // Si ya es un embed directo externo
        Matcher pixelDrain = Pattern.compile("^https?://(?:www\\.)?pixeldrain\\.com/u/([a-zA-Z0-9]+)(?:[?#].*)?$", Pattern.CASE_INSENSITIVE).matcher(cdnUrl);
        if (pixelDrain.matches()) {
            return "https://pixeldrain.com/api/file/" + pixelDrain.group(1);
        }
        if (cdnUrl.contains("pixeldrain.com") || cdnUrl.contains("filemoon.") || cdnUrl.contains("abyssplayer.com")) {
            return cdnUrl;
        }

        // Mapeo por ID1 y servidor
        Matcher idMatcher = Pattern.compile("[?&]id1=([a-zA-Z0-9_-]+)").matcher(cdnUrl);
        String id1 = idMatcher.find() ? idMatcher.group(1) : null;

        if (id1 != null) {
            if (cdnUrl.contains("server-1.php")) {
                return "https://hgcloud.to/e/" + id1; // StreamHG / HGCloud
            } else if (cdnUrl.contains("server-2.php")) {
                return "https://bysesukior.com/e/" + id1; // Filemoon
            } else if (cdnUrl.contains("server-3.php")) {
                return "https://minochinos.com/embed/" + id1; // Minochinos / EarnVids
            } else if (cdnUrl.contains("abyss.php")) {
                return "https://abyssplayer.com/" + id1; // Abyss Player
            }
        }

        // Fallback: consultar el HTML del PHP intermedio y extraer iframe.src
        try {
            String html = httpGet(cdnUrl, BASE_URL);
            Matcher iframeSrc = Pattern.compile("iframe\\.src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (iframeSrc.find()) {
                return iframeSrc.group(1).trim();
            }
            Matcher iframeTag = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (iframeTag.find()) {
                return iframeTag.group(1).trim();
            }
        } catch (Exception ignored) {}

        return cdnUrl;
    }

    public SmartPlaybackResult resolveMovie(MediaItem item) {
        if (item == null || item.getDetailUrl() == null) return null;
        return resolveEpisode(item.getDetailUrl());
    }

    private static String httpGet(String urlStr, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        if (referer != null) conn.setRequestProperty("Referer", referer);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPost(String urlStr, String formBody, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (referer != null) {
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Origin", BASE_URL);
        }

        byte[] postData = formBody.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData);
            os.flush();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
