package sv.edu.catolica.rex.network;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import sv.edu.catolica.rex.models.MediaItem;

public class AllCalidadScraper {

    private static final String TAG      = "AllCalidadScraper";
    private static final String SITE_BASE = "https://allcalidad.re";
    private static final String API_BASE  = "https://allcalidad.re/api/rest";
    private static final int    TIMEOUT_MS = 30_000;

    // ── User-Agent real de Chrome 124 en Android ──────────────────────────────
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36";

    // ─────────────────────────────────────────────────────────────────────────
    // MODELOS
    // ─────────────────────────────────────────────────────────────────────────

    public static class ContentItem {
        public int    id;
        public String title, year, overview, posterUrl, backdropUrl,
                detailUrl, rating, type;
    }

    public static class PlayerData {
        public String       embedUrl;
        public List<Server> servers = new ArrayList<>();
    }

    public static class Server {
        public String name, url;
    }

    public static class Episode {
        public int    id, seasonNumber, episodeNumber;
        public String title, overview, stillUrl;
    }

    public static class Season {
        public int          seasonNumber;
        public List<Episode> episodes = new ArrayList<>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LISTING  (devuelven lista vacía en error, nunca lanzan excepción al caller)
    // ─────────────────────────────────────────────────────────────────────────

    public static List<ContentItem> getMovies(int page) throws IOException {
        return parseList(httpGet(
                API_BASE + "/listing?page=" + page +
                        "&post_type=movies&posts_per_page=16&genres=&years="));
    }

    public static List<ContentItem> getFeaturedMovies() {
        // Este endpoint es inestable (devuelve 500 a veces); si falla, vacío.
        try {
            return parseList(httpGet(API_BASE + "/sliders?type=movies&posts_per_page=8"));
        } catch (Exception e) {
            Log.w(TAG, "getFeaturedMovies falló, usando vacío: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static List<ContentItem> getTvShows(int page) throws IOException {
        return parseList(httpGet(
                API_BASE + "/listing?page=" + page +
                        "&post_type=tvshows&posts_per_page=16&genres=&years="));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POPULARES
    // ─────────────────────────────────────────────────────────────────────────

    public static List<ContentItem> getPopularMovies() throws IOException {
        return parseList(httpGet(
                API_BASE + "/tops?range=month&limit=24&post_type=movies"));
    }

    public static List<ContentItem> getPopularTvShows() throws IOException {
        return parseList(httpGet(
                API_BASE + "/tops?range=month&limit=24&post_type=tvshows"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BÚSQUEDA
    // ─────────────────────────────────────────────────────────────────────────

    public static List<ContentItem> search(String query, int page) throws IOException {
        String encoded = URLEncoder.encode(query, "UTF-8");
        return parseList(httpGet(
                API_BASE + "/search?query=" + encoded +
                        "&page=" + page +
                        "&post_type=movies%2Ctvshows%2Canimes&posts_per_page=16"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RELACIONADOS / HIT
    // ─────────────────────────────────────────────────────────────────────────

    public static List<ContentItem> getRelated(int postId) throws IOException {
        return parseList(httpGet(
                API_BASE + "/related?post_id=" + postId +
                        "&post_type=movies&posts_per_page=16"));
    }

    // Fix hit() — agregar ?nocache=timestamp
    public static void hit(int postId, String postType) {
        if (postId <= 0) return;
        try {
            String safeType = (postType == null || postType.trim().isEmpty())
                    ? "movies" : postType.trim();
            String encodedType = URLEncoder.encode(safeType, "UTF-8");
            // El sitio real incluye nocache con timestamp para evitar caché
            String url = API_BASE + "/hit?nocache=" + System.currentTimeMillis()
                    + "&post_id=" + postId + "&post_type=" + encodedType;
            httpGet(url);
        } catch (Exception ignored) { }
    }

    public static String getRating(int postId) {
        try {
            String json = httpGet(API_BASE + "/rating?post_id=" + postId);
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("error", true)) {
                Object data = root.opt("data");
                if (data instanceof JSONObject) {
                    return ((JSONObject) data).optString("rating", "");
                }
                if (data instanceof String) return (String) data;
            }
        } catch (Exception e) {
            Log.w(TAG, "getRating failed: " + e.getMessage());
        }
        return "";
    }


    // ─────────────────────────────────────────────────────────────────────────
    // TEMPORADAS / EPISODIOS
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Season> getSeasons(int postId) throws IOException {
        List<Season> seasons = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(httpGet(API_BASE + "/episodes?post_id=" + postId));
            if (root.optBoolean("error", true)) return seasons;

            Object data = root.opt("data");
            if (data instanceof JSONObject) {
                JSONObject dataObj = (JSONObject) data;
                JSONArray seasonArray = dataObj.optJSONArray("seasons");
                if (seasonArray != null) return sortSeasons(parseSeasonList(seasonArray));
                JSONArray episodesArray = dataObj.optJSONArray("episodes");
                if (episodesArray != null) {
                    if (looksLikeSeasonContainerArray(episodesArray))
                        return sortSeasons(parseSeasonList(episodesArray));
                    return sortSeasons(groupEpisodesBySeason(episodesArray));
                }
            }
            if (data instanceof JSONArray) {
                JSONArray dataArray = (JSONArray) data;
                if (looksLikeSeasonContainerArray(dataArray))
                    return sortSeasons(parseSeasonList(dataArray));
                return sortSeasons(groupEpisodesBySeason(dataArray));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Episodes parse error", e);
        }
        return sortSeasons(seasons);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PLAYER
    // ─────────────────────────────────────────────────────────────────────────

    public static PlayerData getPlayer(int postId) throws IOException {
        PlayerData playerData = new PlayerData();
        try {
            JSONObject root = new JSONObject(
                    httpGet(API_BASE + "/player?post_id=" + postId + "&_any=1"));
            if (!root.optBoolean("error", true)) {
                parsePlayerData(playerData, root.opt("data"));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Player parse error", e);
        }
        return playerData;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARSER GENERAL
    // ─────────────────────────────────────────────────────────────────────────

    private static List<ContentItem> parseList(String json) {
        List<ContentItem> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("error", true)) {
                JSONArray posts;
                if (root.optJSONObject("data") != null &&
                        root.getJSONObject("data").optJSONArray("posts") != null) {
                    posts = root.getJSONObject("data").getJSONArray("posts");
                } else {
                    posts = root.optJSONArray("data");
                }
                if (posts == null) return list;

                for (int i = 0; i < posts.length(); i++) {
                    JSONObject post = posts.getJSONObject(i);
                    ContentItem item = new ContentItem();
                    item.id       = post.optInt("_id", post.optInt("ID", 0));
                    item.title    = post.optString("title");
                    item.overview = post.optString("overview");
                    item.rating   = post.optString("rating");
                    item.type     = normalizePostType(
                            firstNonEmpty(post.optString("type"), post.optString("post_type")));

                    String date = post.optString("release_date");
                    if (date != null && date.length() >= 4) item.year = date.substring(0, 4);

                    JSONObject images = post.optJSONObject("images");
                    if (images != null) {
                        item.posterUrl   = normalizeUrl(images.optString("poster"));
                        item.backdropUrl = normalizeUrl(images.optString("backdrop"));
                    }
                    if (item.posterUrl == null || item.posterUrl.isEmpty())
                        item.posterUrl = normalizeUrl(post.optString("poster"));
                    if (item.overview == null || item.overview.isEmpty())
                        item.overview = post.optString("description");

                    String slug = post.optString("slug");
                    if (slug != null && !slug.isEmpty()) {
                        item.detailUrl = buildDetailUrl(item.type, slug);
                    } else {
                        item.detailUrl = normalizeUrl(post.optString("url"));
                    }

                    if (item.title != null && !item.title.isEmpty()) list.add(item);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
        }
        return list;
    }

    public static List<String> getPlayableUrls(PlayerData playerData) {
        Set<String> urls = new LinkedHashSet<>();
        if (playerData == null) return new ArrayList<>();
        if (playerData.embedUrl != null && !playerData.embedUrl.isEmpty())
            urls.add(playerData.embedUrl);
        if (playerData.servers != null) {
            for (Server s : playerData.servers)
                if (s != null && s.url != null && !s.url.isEmpty()) urls.add(s.url);
        }
        return new ArrayList<>(urls);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERSIÓN
    // ─────────────────────────────────────────────────────────────────────────

    public static MediaItem toMediaItem(ContentItem item) {
        MediaItem mi = new MediaItem(
                item.title    != null ? item.title    : "",
                item.year     != null ? item.year     : "",
                item.posterUrl!= null ? item.posterUrl: "",
                item.detailUrl!= null ? item.detailUrl: "",
                0);
        mi.setPostId(item.id);
        mi.setSynopsis(item.overview != null ? item.overview : "");
        mi.setMediaType(item.type    != null ? item.type     : "movies");

        // ✅ AGREGAR ESTO - Guardar el rating del JSON
        if (item.rating != null && !item.rating.isEmpty()) {
            try {
                mi.setRating(Double.parseDouble(item.rating));
            } catch (NumberFormatException e) {
                mi.setRating(0.0);
            }
        }

        return mi;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARSING INTERNO (sin cambios respecto a original)
    // ─────────────────────────────────────────────────────────────────────────

    private static Season parseSeasonObject(JSONObject seasonObj, int fallbackSeasonNumber) {
        Season season = new Season();
        season.seasonNumber = seasonObj.optInt("season_number",
                seasonObj.optInt("season", seasonObj.optInt("number", fallbackSeasonNumber)));
        JSONArray episodesArray = seasonObj.optJSONArray("episodes");
        Map<Integer, Episode> uniqueEpisodes = new LinkedHashMap<>();
        if (episodesArray != null) {
            for (int i = 0; i < episodesArray.length(); i++) {
                JSONObject ep = episodesArray.optJSONObject(i);
                if (ep == null) continue;
                Episode episode = parseEpisode(ep, season.seasonNumber, i + 1);
                if (episode != null) uniqueEpisodes.put(episode.id, episode);
            }
        }
        season.episodes.addAll(uniqueEpisodes.values());
        return season;
    }

    private static List<Season> parseSeasonList(JSONArray seasonArray) {
        List<Season> seasons = new ArrayList<>();
        if (seasonArray == null) return seasons;
        for (int i = 0; i < seasonArray.length(); i++) {
            JSONObject obj = seasonArray.optJSONObject(i);
            if (obj == null) continue;
            Season s = parseSeasonObject(obj, i + 1);
            if (!s.episodes.isEmpty()) seasons.add(s);
        }
        return seasons;
    }

    private static boolean looksLikeSeasonContainerArray(JSONArray array) {
        if (array == null || array.length() == 0) return false;
        JSONObject first = array.optJSONObject(0);
        if (first == null) return false;
        return first.optJSONArray("episodes") != null
                && (first.has("season") || first.has("season_number") || first.has("number"));
    }

    private static List<Season> groupEpisodesBySeason(JSONArray episodesArray) {
        Map<Integer, Season> seasonMap = new LinkedHashMap<>();
        for (int i = 0; i < episodesArray.length(); i++) {
            JSONObject obj = episodesArray.optJSONObject(i);
            if (obj == null) continue;
            Episode episode = parseEpisode(obj, 1, i + 1);
            if (episode == null) continue;
            int sn = episode.seasonNumber > 0 ? episode.seasonNumber : 1;
            Season season = seasonMap.get(sn);
            if (season == null) { season = new Season(); season.seasonNumber = sn; seasonMap.put(sn, season); }
            season.episodes.add(episode);
        }
        return new ArrayList<>(seasonMap.values());
    }

    private static Episode parseEpisode(JSONObject obj, int fallbackSeason, int fallbackEp) {
        Episode e = new Episode();
        e.id            = obj.optInt("post_id", obj.optInt("_id", obj.optInt("ID", 0)));
        e.seasonNumber  = obj.optInt("season_number", obj.optInt("season", fallbackSeason));
        e.episodeNumber = obj.optInt("episode_number", obj.optInt("episode", obj.optInt("number", fallbackEp)));
        e.title         = firstNonEmpty(obj.optString("title"), obj.optString("name"), "Episodio " + e.episodeNumber);
        e.overview      = firstNonEmpty(obj.optString("overview"), obj.optString("description"));
        e.stillUrl      = normalizeUrl(firstNonEmpty(obj.optString("still_path"), obj.optString("still")));
        if (e.id <= 0) return null;
        return e;
    }

    private static List<Season> sortSeasons(List<Season> seasons) {
        if (seasons == null) return new ArrayList<>();
        for (Season season : seasons) {
            if (season == null || season.episodes == null) continue;
            for (Episode ep : season.episodes) {
                if (ep == null) continue;
                if (ep.seasonNumber <= 0) ep.seasonNumber = season.seasonNumber > 0 ? season.seasonNumber : 1;
            }
            Collections.sort(season.episodes, (a, b) -> {
                int an = (a != null && a.episodeNumber > 0) ? a.episodeNumber : Integer.MAX_VALUE;
                int bn = (b != null && b.episodeNumber > 0) ? b.episodeNumber : Integer.MAX_VALUE;
                if (an != bn) return Integer.compare(an, bn);
                return Integer.compare(a != null ? a.id : Integer.MAX_VALUE, b != null ? b.id : Integer.MAX_VALUE);
            });
        }
        Collections.sort(seasons, (a, b) -> {
            int as = (a != null && a.seasonNumber > 0) ? a.seasonNumber : Integer.MAX_VALUE;
            int bs = (b != null && b.seasonNumber > 0) ? b.seasonNumber : Integer.MAX_VALUE;
            return Integer.compare(as, bs);
        });
        return seasons;
    }

    private static String normalizePostType(String rawType) {
        if (rawType == null) return "movies";
        String v = rawType.trim().toLowerCase();
        if (v.isEmpty())                                    return "movies";
        if ("movie".equals(v))                              return "movies";
        if ("tvshow".equals(v) || "series".equals(v))       return "tvshows";
        if ("anime".equals(v))                              return "animes";
        return v;
    }

    private static String buildDetailUrl(String type, String slug) {
        if (slug == null || slug.trim().isEmpty()) return "";
        String nt = normalizePostType(type);
        if ("tvshows".equals(nt)) return SITE_BASE + "/series/"  + slug + "/";
        if ("animes".equals(nt))  return SITE_BASE + "/animes/"  + slug + "/";
        return                           SITE_BASE + "/peliculas/"+ slug + "/";
    }

    private static void parsePlayerData(PlayerData pd, Object dataObj) throws JSONException {
        if (dataObj == null) return;
        if (dataObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) dataObj;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof JSONObject) addServerFromJson(pd, (JSONObject) item, "Servidor " + (i + 1));
            }
            return;
        }
        if (!(dataObj instanceof JSONObject)) return;
        JSONObject data = (JSONObject) dataObj;
        String embed = firstNonEmpty(data.optString("embed_url"), data.optString("iframe_url"), data.optString("url"));
        if (!embed.isEmpty()) pd.embedUrl = normalizeUrl(embed);
        JSONArray servers = data.optJSONArray("servers");
        if (servers != null) for (int i = 0; i < servers.length(); i++) {
            JSONObject s = servers.optJSONObject(i);
            if (s != null) addServerFromJson(pd, s, "Servidor " + (i + 1));
        }
        JSONArray embeds = data.optJSONArray("embeds");
        if (embeds != null) for (int i = 0; i < embeds.length(); i++) {
            Object raw = embeds.get(i);
            if (raw instanceof JSONObject) addServerFromJson(pd, (JSONObject) raw, "Embed " + (i + 1));
            else if (raw instanceof String) addServer(pd, "Embed " + (i + 1), (String) raw);
        }
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("servers".equals(key) || "embeds".equals(key) || "embed_url".equals(key)
                    || "iframe_url".equals(key) || "url".equals(key)) continue;
            Object raw = data.get(key);
            if (raw instanceof JSONObject) addServerFromJson(pd, (JSONObject) raw, "Servidor " + key);
        }
        if ((pd.embedUrl == null || pd.embedUrl.isEmpty()) && !pd.servers.isEmpty())
            pd.embedUrl = pd.servers.get(0).url;
    }

    private static void addServerFromJson(PlayerData pd, JSONObject j, String fallback) {
        String name = firstNonEmpty(j.optString("name"), j.optString("lang"), fallback);
        String url  = firstNonEmpty(j.optString("url"), j.optString("embed_url"), j.optString("iframe_url"));
        addServer(pd, name, url);
    }

    private static void addServer(PlayerData pd, String name, String rawUrl) {
        String normalized = normalizeUrl(rawUrl);
        if (normalized == null || normalized.isEmpty()) return;
        Server s = new Server();
        s.name = (name == null || name.isEmpty()) ? "Servidor" : name;
        s.url  = normalized;
        pd.servers.add(s);
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty())        return "";
        if (v.startsWith("//")) return "https:" + v;
        if (v.startsWith("http://") || v.startsWith("https://")) return v;

        // Fix: /thumbs/ → /wp-content/uploads/thumbs/
        if (v.startsWith("/thumbs/")) return SITE_BASE + "/wp-content/uploads" + v;

        if (v.startsWith("/")) return SITE_BASE + v;
        return SITE_BASE + "/" + v;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP — cabeceras de navegador real, reintentos, manejo graceful de 500
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Realiza una petición GET con cabeceras idénticas a las de Chrome en Android.
     * - Reintenta UNA vez si el servidor devuelve 500/503 (errores transitorios).
     * - Lanza IOException solo si ambos intentos fallan o hay error de red.
     */
    private static String httpGet(String urlString) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return httpGetOnce(urlString);
            } catch (IOException e) {
                lastException = e;
                String msg = e.getMessage();
                boolean isServerError = msg != null && (msg.startsWith("HTTP 5") || msg.startsWith("HTTP 429"));
                if (!isServerError) throw e;   // Error de red, no reintentar
                if (attempt < 2) {
                    try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw lastException;
    }

    private static String httpGetOnce(String urlString) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            conn.setRequestProperty("User-Agent",      USER_AGENT);
            conn.setRequestProperty("Accept",          "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7");
            // ← NO poner Accept-Encoding, HttpURLConnection lo gestiona solo
            conn.setRequestProperty("Connection",      "keep-alive");
            conn.setRequestProperty("Referer",         SITE_BASE + "/");
            conn.setRequestProperty("Origin",          SITE_BASE);
            conn.setRequestProperty("Sec-Fetch-Dest",  "empty");
            conn.setRequestProperty("Sec-Fetch-Mode",  "cors");
            conn.setRequestProperty("Sec-Fetch-Site",  "same-origin");
            conn.setRequestProperty("Cache-Control",   "no-cache");
            conn.setRequestProperty("Pragma",          "no-cache");
            conn.setRequestProperty("X-Requested-With","XMLHttpRequest");

            int code = conn.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " for " + urlString);

            // Sin GZIPInputStream — Java descomprime automáticamente
            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();

        } finally {
            if (br   != null) try { br.close();   } catch (IOException ignored) {}   // ← cerrar siempre
            if (conn != null) conn.disconnect();
        }
    }
}