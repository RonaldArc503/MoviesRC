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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.source.SourceMatcher;

public class Cuevana3Scraper extends BaseSmartProvider {

    private static final String TAG = "Cuevana3Scraper";
    public static final String DEFAULT_BASE = "https://wv3.cuevana3.eu";
    private static final int TIMEOUT_MS = 15000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private String baseUrl = DEFAULT_BASE;

    public Cuevana3Scraper() {}

    public Cuevana3Scraper(String customBaseUrl) {
        if (customBaseUrl != null && !customBaseUrl.trim().isEmpty()) {
            this.baseUrl = customBaseUrl.trim().replaceAll("/+$", "");
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        }
    }

    @Override
    public boolean supports(SmartContentType contentType) {
        return true;
    }

    @Override
    public String getName() {
        return "Cuevana3";
    }

    @Override
    public SmartPlaybackResult resolve(SmartPlaybackRequest request) throws Exception {
        if (request == null) return new SmartPlaybackResult();
        String title = request.title == null ? "" : request.title;
        MediaItem m = new MediaItem(title, "", "", request.detailUrl != null ? request.detailUrl : "", 0);
        m.setMediaType(request.mediaType);
        if (request.isEpisodeRequest()) {
            return resolveEpisode(m, request.seasonNumber, request.episodeNumber);
        }
        return resolveMovie(m);
    }

    /**
     * Busca películas y series en Cuevana 3.
     */
    public List<MediaItem> search(String query, int limit) throws IOException {
        List<MediaItem> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;

        String encoded = URLEncoder.encode(query.trim(), "UTF-8");
        String url = baseUrl + "/search?q=" + encoded;
        String html = httpGet(url);
        if (html == null || html.isEmpty()) return out;

        // 1. Intentar parsear Next.js data (wv3.cuevana3.eu)
        JSONObject nextData = extractNextData(html);
        if (nextData != null) {
            try {
                JSONObject props = nextData.optJSONObject("props");
                if (props != null) {
                    JSONObject pageProps = props.optJSONObject("pageProps");
                    if (pageProps != null) {
                        JSONArray movies = pageProps.optJSONArray("movies");
                        if (movies != null) {
                            for (int i = 0; i < movies.length() && out.size() < limit; i++) {
                                JSONObject mObj = movies.optJSONObject(i);
                                if (mObj == null) continue;

                                JSONObject titles = mObj.optJSONObject("titles");
                                String title = titles != null ? titles.optString("name", "") : "";
                                if (title.isEmpty()) continue;

                                String releaseDate = mObj.optString("releaseDate", "");
                                String year = "";
                                if (releaseDate.length() >= 4) {
                                    year = releaseDate.substring(0, 4);
                                }

                                JSONObject images = mObj.optJSONObject("images");
                                String posterUrl = images != null ? images.optString("poster", "") : "";

                                JSONObject urlObj = mObj.optJSONObject("url");
                                String slugPath = urlObj != null ? urlObj.optString("slug", "") : "";

                                JSONObject slugObj = mObj.optJSONObject("slug");
                                String slugName = slugObj != null ? slugObj.optString("name", "") : "";

                                boolean isSeries = slugPath.startsWith("series/") || slugPath.contains("/seasons/");
                                String mediaType = isSeries ? "tvshows" : "movies";
                                String detailUrl = isSeries
                                        ? baseUrl + "/ver-serie/" + slugName
                                        : baseUrl + "/ver-pelicula/" + slugName;

                                MediaItem item = new MediaItem(title, year, posterUrl, detailUrl, 0);
                                item.setMediaType(mediaType);
                                item.setFuente("cuevana3");

                                String tmdbId = mObj.optString("TMDbId", "");
                                if (!tmdbId.isEmpty()) {
                                    try {
                                        item.setTmdbId(Integer.parseInt(tmdbId));
                                    } catch (Exception ignored) {}
                                }

                                out.add(item);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parseando __NEXT_DATA__ en búsqueda: " + e.getMessage());
            }
        }

        if (!out.isEmpty()) {
            return out;
        }

        // 2. Fallback: Parseo HTML clásico (WordPress / ToroThemes)
        Pattern cardPattern = Pattern.compile(
                "<li class=[\"']?xxx TPostMv[\"']?>.*?<a href=[\"']?(/[^\"'\\s>]+)[\"']?>.*?<h2 class=[\"']?Title[\"']?>([^<]+)</h2>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = cardPattern.matcher(html);

        Set<String> seenPaths = new LinkedHashSet<>();
        while (matcher.find() && out.size() < limit) {
            String path = matcher.group(1);
            String rawTitle = matcher.group(2);
            if (path == null || path.isEmpty() || seenPaths.contains(path)) continue;
            seenPaths.add(path);

            String fullCard = matcher.group();
            String title = (rawTitle != null) ? rawTitle.trim() : "";

            // Año
            String year = "";
            Matcher yearMatcher = Pattern.compile("<span class=[\"']?Year[\"']?>(\\d{4})</span>", Pattern.CASE_INSENSITIVE).matcher(fullCard);
            if (yearMatcher.find()) {
                year = yearMatcher.group(1);
            }

            // Imagen / Póster
            String posterUrl = "";
            Matcher posterMatcher = Pattern.compile("(?:data-src|src)=[\"']?(/poster/[^\"'\\s>]+)[\"']?", Pattern.CASE_INSENSITIVE).matcher(fullCard);
            if (posterMatcher.find()) {
                posterUrl = baseUrl + posterMatcher.group(1);
            } else {
                Matcher anySrc = Pattern.compile("(?:data-src|src)=[\"']?(https?://[^\"'\\s>]+)[\"']?", Pattern.CASE_INSENSITIVE).matcher(fullCard);
                while (anySrc.find()) {
                    String candidate = anySrc.group(1);
                    if (!candidate.contains("loading.gif") && !candidate.contains("cuevana3.png") && !candidate.contains("rating_")) {
                        posterUrl = candidate;
                        break;
                    }
                }
                if (posterUrl.isEmpty()) {
                    Matcher relativeImg = Pattern.compile("(?:data-src|src)=[\"']?(/[^\"'\\s>]+\\.(?:jpe?g|png|webp))[\"']?", Pattern.CASE_INSENSITIVE).matcher(fullCard);
                    while (relativeImg.find()) {
                        String rel = relativeImg.group(1);
                        if (!rel.contains("loading.gif") && !rel.contains("cuevana3.png") && !rel.contains("rating_")) {
                            posterUrl = baseUrl + rel;
                            break;
                        }
                    }
                }
            }

            boolean isSeries = path.startsWith("/serie/");
            String mediaType = isSeries ? "tvshows" : "movies";
            String detailUrl = baseUrl + path;

            MediaItem item = new MediaItem(title, year, posterUrl, detailUrl, 0);
            item.setMediaType(mediaType);
            item.setFuente("cuevana3");

            // Rating si está disponible
            Matcher voteMatcher = Pattern.compile("<span class=[\"']?Vote[^\"']*[\"']?>([0-9.]+)</span>", Pattern.CASE_INSENSITIVE).matcher(fullCard);
            if (voteMatcher.find()) {
                try {
                    item.setRating(Double.parseDouble(voteMatcher.group(1)));
                } catch (Exception ignored) { }
            }

            out.add(item);
        }

        return out;
    }

    /**
     * Resuelve los enlaces de reproducción para una película.
     */
    public SmartPlaybackResult resolveMovie(MediaItem item) throws Exception {
        SmartPlaybackResult result = new SmartPlaybackResult();
        result.providerName = "Cuevana3";
        if (item == null) return result;

        String moviePageUrl = item.getDetailUrl();
        if (moviePageUrl != null && moviePageUrl.startsWith("/")) {
            moviePageUrl = baseUrl + moviePageUrl;
        }

        if (moviePageUrl == null || !moviePageUrl.contains("cuevana3")) {
            String title = item.getTitulo();
            if (title == null || title.trim().isEmpty()) return result;
            List<MediaItem> searchResults = search(title, 5);
            MediaItem best = pickBestMatch(searchResults, title, item.getAnio(), item.getTmdbId(), false);
            if (best != null && best.getDetailUrl() != null) {
                moviePageUrl = best.getDetailUrl();
            } else {
                return result;
            }
        }

        if (moviePageUrl.startsWith("/")) {
            moviePageUrl = baseUrl + moviePageUrl;
        }
        if (moviePageUrl.contains("/pelicula/")) {
            moviePageUrl = moviePageUrl.replace("/pelicula/", "/ver-pelicula/");
        }

        String html = httpGet(moviePageUrl);
        if (html == null || html.isEmpty()) return result;

        // 1. Intentar extraer reproductores de Next.js
        JSONObject nextData = extractNextData(html);
        if (nextData != null) {
            try {
                JSONObject props = nextData.optJSONObject("props");
                if (props != null) {
                    JSONObject pageProps = props.optJSONObject("pageProps");
                    if (pageProps != null) {
                        JSONObject thisMovie = pageProps.optJSONObject("thisMovie");
                        if (thisMovie != null) {
                            JSONObject videos = thisMovie.optJSONObject("videos");
                            if (videos != null) {
                                extractVideosFromNextJson(videos, result);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error extrayendo videos de Next.js para película: " + e.getMessage());
            }
        }

        // 2. Extraer iframes y enlaces directos de HTML
        List<String> embeds = extractEmbedUrls(html);
        for (String emb : embeds) {
            String normalized = normalizeUrl(emb);
            if (isValidEmbed(normalized)) {
                if (isStreamUrl(normalized)) {
                    result.streamUrls.add(normalized);
                } else {
                    result.hostUrls.add(normalized);
                }
            }
        }

        sortEmbedUrlsByReliability(result.hostUrls);
        if ((result.embedUrl == null || result.embedUrl.trim().isEmpty()) && !result.hostUrls.isEmpty()) {
            result.embedUrl = result.hostUrls.get(0);
        }

        return result;
    }

    /**
     * Resuelve los enlaces de reproducción para un episodio de una serie.
     */
    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode) throws Exception {
        SmartPlaybackResult result = new SmartPlaybackResult();
        result.providerName = "Cuevana3";
        if (item == null) return result;

        int sNum = season > 0 ? season : 1;
        int eNum = episode > 0 ? episode : 1;

        String seriesSlug = "";
        if (item.getDetailUrl() != null && item.getDetailUrl().contains("cuevana3")) {
            Matcher m = Pattern.compile("/(?:ver-serie|serie)/([^/?#]+)", Pattern.CASE_INSENSITIVE).matcher(item.getDetailUrl());
            if (m.find()) {
                seriesSlug = m.group(1);
            }
        }

        if (seriesSlug.isEmpty()) {
            String title = item.getTitulo();
            if (title == null || title.trim().isEmpty()) return result;
            List<MediaItem> searchResults = search(title, 5);
            MediaItem best = pickBestMatch(searchResults, title, item.getAnio(), item.getTmdbId(), true);
            if (best != null && best.getDetailUrl() != null) {
                Matcher m = Pattern.compile("/(?:ver-serie|serie)/([^/?#]+)", Pattern.CASE_INSENSITIVE).matcher(best.getDetailUrl());
                if (m.find()) {
                    seriesSlug = m.group(1);
                }
            }
        }

        if (seriesSlug.isEmpty()) {
            return result;
        }

        // Formato estándar moderno: /episodio/{slug}-temporada-{s}-episodio-{e}
        String episodeUrl = baseUrl + "/episodio/" + seriesSlug + "-temporada-" + sNum + "-episodio-" + eNum;

        String html = null;
        try {
            html = httpGet(episodeUrl);
        } catch (Exception ignored) { }

        // Fallback 1: Formato alternativo /episodio/{slug}-{s}x{e}
        if (html == null || html.isEmpty()) {
            try {
                html = httpGet(baseUrl + "/episodio/" + seriesSlug + "-" + sNum + "x" + eNum);
            } catch (Exception ignored) { }
        }

        // Fallback 2: Consultar la página de la serie
        if (html == null || html.isEmpty()) {
            String seriesHtml = httpGet(baseUrl + "/ver-serie/" + seriesSlug);
            if (seriesHtml == null || seriesHtml.isEmpty()) {
                seriesHtml = httpGet(baseUrl + "/serie/" + seriesSlug);
            }
            if (seriesHtml != null && !seriesHtml.isEmpty()) {
                String epPatternStr = "/episodio/[^\"'\\s>]*?" + sNum + "x" + eNum + "[\"']?";
                Matcher m = Pattern.compile(epPatternStr, Pattern.CASE_INSENSITIVE).matcher(seriesHtml);
                if (m.find()) {
                    String matchedPath = m.group().replaceAll("[\"']", "");
                    if (!matchedPath.startsWith("http")) {
                        matchedPath = baseUrl + matchedPath;
                    }
                    html = httpGet(matchedPath);
                }
            }
        }

        if (html == null || html.isEmpty()) return result;

        // Extraer videos de Next.js
        JSONObject nextData = extractNextData(html);
        if (nextData != null) {
            try {
                JSONObject props = nextData.optJSONObject("props");
                if (props != null) {
                    JSONObject pageProps = props.optJSONObject("pageProps");
                    if (pageProps != null) {
                        JSONObject epObj = pageProps.optJSONObject("episode");
                        if (epObj == null) epObj = pageProps.optJSONObject("thisEpisode");
                        if (epObj != null) {
                            JSONObject videos = epObj.optJSONObject("videos");
                            if (videos != null) {
                                extractVideosFromNextJson(videos, result);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error extrayendo videos de Next.js para episodio: " + e.getMessage());
            }
        }

        // Extraer embeds del HTML
        List<String> embeds = extractEmbedUrls(html);
        for (String emb : embeds) {
            String normalized = normalizeUrl(emb);
            if (isValidEmbed(normalized)) {
                if (isStreamUrl(normalized)) {
                    result.streamUrls.add(normalized);
                } else {
                    result.hostUrls.add(normalized);
                }
            }
        }

        sortEmbedUrlsByReliability(result.hostUrls);
        if ((result.embedUrl == null || result.embedUrl.trim().isEmpty()) && !result.hostUrls.isEmpty()) {
            result.embedUrl = result.hostUrls.get(0);
        }

        return result;
    }

    private static int getVideoItemScore(JSONObject item) {
        if (item == null) return 0;
        String cyberlocker = item.optString("cyberlocker", "").toLowerCase(Locale.ROOT);
        String server = item.optString("server", "").toLowerCase(Locale.ROOT);
        String title = item.optString("title", "").toLowerCase(Locale.ROOT);
        String result = item.optString("result", "").toLowerCase(Locale.ROOT);
        String combined = cyberlocker + " " + server + " " + title + " " + result;
        // Servidor 1: Doodstream (sin captcha, rápido)
        if (combined.contains("dood") || combined.contains("ds2play")) return 100;
        // Servidor 2: Streamwish / Vidhide / Filemoon (sin captcha)
        if (combined.contains("streamwish") || combined.contains("wishembed")) return 90;
        if (combined.contains("vidhide")) return 85;
        if (combined.contains("filemoon") || combined.contains("filelions")) return 82;
        // Servidor 3: Voesx (colocado en Servidor 3 por salto de captcha)
        if (combined.contains("voe")) return 80;
        return 30;
    }

    /**
     * Extrae los servidores de video del JSON de videos (latino, english, spanish)
     * e interactúa con el reproductor de Cuevana para obtener los enlaces directos a hostings.
     * Prioriza servidores con menos trabas (voesx y doodstream).
     */
    private void extractVideosFromNextJson(JSONObject videos, SmartPlaybackResult result) {
        String[] languages = {"latino", "spanish", "english"};
        int resolvedCount = 0;
        for (String lang : languages) {
            JSONArray list = videos.optJSONArray(lang);
            if (list == null) continue;

            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.optJSONObject(i);
                if (it != null) items.add(it);
            }
            Collections.sort(items, (o1, o2) -> Integer.compare(getVideoItemScore(o2), getVideoItemScore(o1)));

            for (JSONObject item : items) {
                String playerUrl = item.optString("result", "");
                if (playerUrl.isEmpty() || !playerUrl.startsWith("http")) continue;

                if (playerUrl.contains("player.cuevana3.eu") || playerUrl.contains("/player.php")) {
                    if (resolvedCount < 4) { // Resolver hasta 4 servidores principales para no demorar la respuesta
                        try {
                            String playerHtml = httpGet(playerUrl, baseUrl + "/");
                            Matcher urlMatcher = Pattern.compile("var\\s+url\\s*=\\s*['\"]([^'\"]+)['\"];", Pattern.CASE_INSENSITIVE).matcher(playerHtml);
                            if (urlMatcher.find()) {
                                String directUrl = urlMatcher.group(1);
                                if (isValidEmbed(directUrl)) {
                                    if (isStreamUrl(directUrl)) {
                                        result.streamUrls.add(directUrl);
                                    } else {
                                        result.hostUrls.add(directUrl);
                                    }
                                    resolvedCount++;
                                    continue;
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error resolviendo player URL " + playerUrl + ": " + e.getMessage());
                        }
                    }
                    // Si no se pudo resolver el enlace interno o se llegó al límite, agregar la URL del player directamente
                    if (isValidEmbed(playerUrl)) {
                        result.hostUrls.add(playerUrl);
                    }
                } else if (isValidEmbed(playerUrl)) {
                    if (isStreamUrl(playerUrl)) {
                        result.streamUrls.add(playerUrl);
                    } else {
                        result.hostUrls.add(playerUrl);
                    }
                }
            }
        }
    }

    /**
     * Obtiene las temporadas y episodios de una serie en Cuevana 3.
     */
    public List<AllCalidadScraper.Season> getSeasons(String seriesUrlOrSlug) {
        List<AllCalidadScraper.Season> seasons = new ArrayList<>();
        if (seriesUrlOrSlug == null || seriesUrlOrSlug.trim().isEmpty()) return seasons;

        String target = seriesUrlOrSlug.trim();
        String url;
        if (target.startsWith("http")) {
            url = target;
        } else if (target.startsWith("/")) {
            url = baseUrl + target;
        } else {
            String slug = target.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            url = baseUrl + "/ver-serie/" + slug;
        }

        if (url.contains("/serie/") && !url.contains("/ver-serie/")) {
            url = url.replace("/serie/", "/ver-serie/");
        }

        try {
            String html = httpGet(url);

            // 1. Intentar parsear Next.js
            JSONObject nextData = extractNextData(html);
            if (nextData != null) {
                try {
                    JSONObject props = nextData.optJSONObject("props");
                    if (props != null) {
                        JSONObject pageProps = props.optJSONObject("pageProps");
                        if (pageProps != null) {
                            JSONObject thisSerie = pageProps.optJSONObject("thisSerie");
                            if (thisSerie == null) thisSerie = pageProps.optJSONObject("serie");
                            if (thisSerie != null) {
                                JSONArray seasonsArr = thisSerie.optJSONArray("seasons");
                                if (seasonsArr != null) {
                                    for (int sIdx = 0; sIdx < seasonsArr.length(); sIdx++) {
                                        JSONObject sObj = seasonsArr.optJSONObject(sIdx);
                                        if (sObj == null) continue;

                                        int sNum = sObj.optInt("number", sIdx);
                                        JSONArray epArr = sObj.optJSONArray("episodes");
                                        if (epArr == null || epArr.length() == 0) continue;

                                        AllCalidadScraper.Season season = new AllCalidadScraper.Season();
                                        season.seasonNumber = sNum;

                                        for (int eIdx = 0; eIdx < epArr.length(); eIdx++) {
                                            JSONObject epObj = epArr.optJSONObject(eIdx);
                                            if (epObj == null) continue;

                                            AllCalidadScraper.Episode ep = new AllCalidadScraper.Episode();
                                            ep.seasonNumber = sNum;
                                            ep.episodeNumber = epObj.optInt("number", eIdx + 1);
                                            ep.title = epObj.optString("title", "Episodio " + ep.episodeNumber);

                                            String tmdbId = epObj.optString("TMDbId", "");
                                            if (!tmdbId.isEmpty()) {
                                                try {
                                                    ep.id = Integer.parseInt(tmdbId);
                                                } catch (Exception ignored) {
                                                    ep.id = Math.abs((target + "_" + sNum + "_" + ep.episodeNumber).hashCode() % 1000000) + 100000;
                                                }
                                            } else {
                                                ep.id = Math.abs((target + "_" + sNum + "_" + ep.episodeNumber).hashCode() % 1000000) + 100000;
                                            }

                                            season.episodes.add(ep);
                                        }

                                        if (!season.episodes.isEmpty()) {
                                            seasons.add(season);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error parseando temporadas desde Next.js: " + e.getMessage());
                }
            }

            if (!seasons.isEmpty()) {
                return seasons;
            }

            // 2. Fallback: Parseo HTML clásico (<ul id="season-1", etc.)
            Pattern seasonBlockPattern = Pattern.compile(
                    "<ul id=[\"']?season-(\\d+)[\"']?[^>]*>(.*?)</ul>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher seasonMatcher = seasonBlockPattern.matcher(html);

            while (seasonMatcher.find()) {
                int sNum = 1;
                try {
                    sNum = Integer.parseInt(seasonMatcher.group(1));
                } catch (Exception ignored) { }

                String seasonContent = seasonMatcher.group(2);
                AllCalidadScraper.Season seasonObj = new AllCalidadScraper.Season();
                seasonObj.seasonNumber = sNum;

                Pattern epPattern = Pattern.compile(
                        "<a href=[\"']?(/episodio/[^\"'\\s>]+)[\"']?>.*?<h2 class=[\"']?Title[\"']?>([^<]+)</h2>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
                );
                Matcher epMatcher = epPattern.matcher(seasonContent);
                int epIndex = 1;
                while (epMatcher.find()) {
                    AllCalidadScraper.Episode ep = new AllCalidadScraper.Episode();
                    ep.seasonNumber = sNum;
                    ep.episodeNumber = epIndex++;
                    ep.title = epMatcher.group(2).trim();

                    String epPath = epMatcher.group(1);
                    Matcher numMatch = Pattern.compile("(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(epPath);
                    if (numMatch.find()) {
                        try {
                            ep.seasonNumber = Integer.parseInt(numMatch.group(1));
                            ep.episodeNumber = Integer.parseInt(numMatch.group(2));
                        } catch (Exception ignored) { }
                    }

                    ep.id = Math.abs((epPath).hashCode() % 1000000) + 100000;
                    seasonObj.episodes.add(ep);
                }

                if (!seasonObj.episodes.isEmpty()) {
                    seasons.add(seasonObj);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getSeasons falló: " + e.getMessage());
        }

        return seasons;
    }

    private JSONObject extractNextData(String html) {
        if (html == null || html.isEmpty()) return null;
        try {
            Matcher m = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>([^<]+)</script>").matcher(html);
            if (m.find()) {
                return new JSONObject(m.group(1));
            }
        } catch (Exception ignored) {}
        return null;
    }

    public List<String> extractEmbedUrls(String html) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null || html.isEmpty()) return new ArrayList<>(out);

        // Buscar iframes con data-src (usado en los tabs de Cuevana)
        Pattern pDataSrc = Pattern.compile("<iframe[^>]+data-src=[\"']?([^\"'\\s>]+)[\"']?", Pattern.CASE_INSENSITIVE);
        Matcher m1 = pDataSrc.matcher(html);
        while (m1.find()) {
            String url = m1.group(1);
            if (url != null && !url.isEmpty()) {
                out.add(url);
            }
        }

        // Buscar iframes directos con src
        Pattern pSrc = Pattern.compile("<iframe[^>]+src=[\"']?([^\"'\\s>]+)[\"']?", Pattern.CASE_INSENSITIVE);
        Matcher m2 = pSrc.matcher(html);
        while (m2.find()) {
            String url = m2.group(1);
            if (url != null && !url.isEmpty() && !url.startsWith("about:") && !url.equals("javascript:void(0)")) {
                out.add(url);
            }
        }

        List<String> list = new ArrayList<>(out);
        sortEmbedUrlsByReliability(list);
        return list;
    }

    public static void sortEmbedUrlsByReliability(List<String> urls) {
        if (urls == null || urls.size() <= 1) return;
        Collections.sort(urls, (u1, u2) -> Integer.compare(getEmbedScore(u2), getEmbedScore(u1)));
    }

    private static int getEmbedScore(String url) {
        if (url == null) return 0;
        String lc = url.toLowerCase(Locale.ROOT);
        // Servidor 1: Doodstream (sin captcha, rápido)
        if (lc.contains("dood") || lc.contains("ds2play") || lc.contains("doodstream") || lc.contains("doods")) return 100;
        // Servidor 2: Streamwish / Vidhide / Filemoon (sin captcha)
        if (lc.contains("streamwish") || lc.contains("wishembed") || lc.contains("strwish") || lc.contains("awish")) return 90;
        if (lc.contains("vidhide")) return 85;
        if (lc.contains("filemoon") || lc.contains("filelions") || lc.contains("bysejikuar")) return 82;
        // Servidor 3: Voesx (colocado en Servidor 3 por salto de captcha)
        if (lc.contains("voe.sx") || lc.contains("voe.network") || lc.contains("voesx") || lc.contains("/voe/")) return 80;
        if (lc.contains("fastplay") || lc.contains("uqload") || lc.contains("mystream")) return 50;
        if (lc.contains("waaw") || lc.contains("netu.tv")) return 20;
        return 30;
    }

    public boolean isValidEmbed(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String lc = url.toLowerCase(Locale.ROOT);
        if (!lc.startsWith("http://") && !lc.startsWith("https://")) return false;
        if (lc.contains("google") || lc.contains("facebook") || lc.contains("twitter") || lc.contains("recaptcha") || lc.contains("analytics")) {
            return false;
        }
        // Descartar páginas de catálogo/navegación web de Cuevana y otros sitios
        if (lc.contains("/ver-pelicula/") || lc.contains("/ver-serie/")
                || lc.contains("/pelicula/") || lc.contains("/serie/")
                || lc.contains("/episodio/")
                || (lc.contains("cuevana3.eu") && !lc.contains("player.cuevana3.eu"))) {
            return false;
        }
        return isHostUrl(url, null)
                || lc.contains("streamwish")
                || lc.contains("voe.sx")
                || lc.contains("voe.network")
                || lc.contains("filemoon")
                || lc.contains("filelions")
                || lc.contains("bysejikuar")
                || lc.contains("vidhide")
                || lc.contains("player.cuevana3.eu")
                || lc.contains("dood")
                || lc.contains("waaw")
                || lc.contains("netu.tv")
                || lc.contains("streamlare")
                || lc.contains("watchsb")
                || lc.contains("zplayer")
                || lc.contains("streamtape")
                || lc.contains("videoapp")
                || lc.contains("hlswish")
                || lc.contains("goodstream")
                || lc.contains("vimeos")
                || lc.contains("fastplay")
                || lc.contains("uqload")
                || lc.contains("mystream")
                || lc.contains("pelistop")
                || lc.contains("embed")
                || lc.contains("/e/")
                || lc.contains("/f/")
                || isStreamUrl(url);
    }

    private MediaItem pickBestMatch(List<MediaItem> results, String query, String targetYear, int targetTmdbId, boolean preferSeries) {
        if (results == null || results.isEmpty()) return null;

        // 1. Coincidencia exacta por TMDbId si está disponible
        if (targetTmdbId > 0) {
            for (MediaItem item : results) {
                if (item != null && item.getTmdbId() == targetTmdbId) {
                    return item;
                }
            }
        }

        String qNorm = SourceMatcher.normalize(query);
        if (qNorm.isEmpty()) return null;

        MediaItem best = null;
        int bestScore = 0;

        for (MediaItem item : results) {
            if (item == null) continue;
            boolean isSeries = "tvshows".equalsIgnoreCase(item.getMediaType());
            if (preferSeries && !isSeries && results.size() > 1) {
                // Penalizar si buscamos serie y el resultado es película
                continue;
            }

            int score = SourceMatcher.fuzzyScore(item.getTitulo(), query);

            // Bonus / penalización por año
            if (targetYear != null && !targetYear.trim().isEmpty() && item.getAnio() != null && !item.getAnio().trim().isEmpty()) {
                try {
                    int ty = Integer.parseInt(targetYear.trim().replaceAll("[^0-9]", ""));
                    int iy = Integer.parseInt(item.getAnio().trim().replaceAll("[^0-9]", ""));
                    if (Math.abs(ty - iy) <= 1) {
                        score += 35;
                    } else {
                        score -= 30;
                    }
                } catch (Exception ignored) { }
            }

            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }

        // Si no hubo coincidencia estricta y se requería serie, buscar el mejor general
        if (best == null) {
            for (MediaItem item : results) {
                int score = SourceMatcher.fuzzyScore(item.getTitulo(), query);
                if (score > bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
        }

        return bestScore >= 40 ? best : null;
    }

    private String httpGet(String urlString) throws IOException {
        return httpGet(urlString, baseUrl + "/");
    }

    private static String httpGet(String urlString, String referer) throws IOException {
        if (urlString == null || urlString.trim().isEmpty()) return "";
        urlString = urlString.trim().replace(" ", "%20");
        if (urlString.startsWith("/")) urlString = DEFAULT_BASE + urlString;
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            } else {
                conn.setRequestProperty("Referer", DEFAULT_BASE + "/");
            }
            conn.setRequestProperty("Origin", DEFAULT_BASE);
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin");

            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code + " for " + urlString);
            }

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
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
