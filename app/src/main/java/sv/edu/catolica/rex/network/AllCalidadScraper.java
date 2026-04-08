package sv.edu.catolica.rex.network;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sv.edu.catolica.rex.models.MediaItem;

public class AllCalidadScraper {

    private static final String TAG = "AllCalidadScraper";
    private static final String SITE_BASE = "https://allcalidad.re";
    private static final String API_BASE = "https://allcalidad.re/api/rest";
    private static final int TIMEOUT_MS = 30000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";

    // ================= MODELOS =================

    public static class ContentItem {
        public int id;
        public String title, year, overview, posterUrl, backdropUrl, detailUrl, rating, type;
    }

    public static class PlayerData {
        public String embedUrl;
        public List<Server> servers = new ArrayList<>();
    }

    public static class Server {
        public String name, url;
    }

    public static class Episode {
        public int id;
        public int seasonNumber;
        public int episodeNumber;
        public String title;
        public String overview;
        public String stillUrl;
    }

    public static class Season {
        public int seasonNumber;
        public List<Episode> episodes = new ArrayList<>();
    }

    // ================= LISTING =================

    public static List<ContentItem> getMovies(int page) throws IOException {
        String url = API_BASE + "/listing?page=" + page +
                "&post_type=movies&posts_per_page=16&genres=&years=";

        return parseList(httpGet(url));
    }

    public static List<ContentItem> getFeaturedMovies() throws IOException {
        String url = API_BASE + "/sliders?type=movies&posts_per_page=8";
        return parseList(httpGet(url));
    }

    public static List<ContentItem> getTvShows(int page) throws IOException {
        String url = API_BASE + "/listing?page=" + page +
                "&post_type=tvshows&posts_per_page=16&genres=&years=";

        return parseList(httpGet(url));
    }

    // ================= POPULARES =================

    public static List<ContentItem> getPopularMovies() throws IOException {
        String url = API_BASE + "/tops?range=month&limit=24&post_type=movies";

        return parseList(httpGet(url));
    }

    public static List<ContentItem> getPopularTvShows() throws IOException {
        String url = API_BASE + "/tops?range=month&limit=24&post_type=tvshows";

        return parseList(httpGet(url));
    }

    // ================= SEARCH =================

    public static List<ContentItem> search(String query, int page) throws IOException {
        String encoded = URLEncoder.encode(query, "UTF-8");

        String url = API_BASE + "/search?query=" + encoded +
                "&page=" + page +
                "&post_type=movies%2Ctvshows%2Canimes&posts_per_page=16";

        return parseList(httpGet(url));
    }

    // ================= RELATED =================

    public static List<ContentItem> getRelated(int postId) throws IOException {
        String url = API_BASE + "/related?post_id=" + postId +
                "&post_type=movies&posts_per_page=16";

        return parseList(httpGet(url));
    }

    public static void hit(int postId, String postType) {
        if (postId <= 0) {
            return;
        }
        try {
            String safeType = (postType == null || postType.trim().isEmpty()) ? "movies" : postType.trim();
            String encodedType = URLEncoder.encode(safeType, "UTF-8");
            String url = API_BASE + "/hit?post_id=" + postId + "&post_type=" + encodedType;
            httpGet(url);
        } catch (Exception ignored) {
            // Best effort only; playback does not depend on this endpoint.
        }
    }

    public static List<Season> getSeasons(int postId) throws IOException {
        String url = API_BASE + "/episodes?post_id=" + postId;
        List<Season> seasons = new ArrayList<>();

        try {
            JSONObject root = new JSONObject(httpGet(url));
            if (root.optBoolean("error", true)) {
                return seasons;
            }

            Object data = root.opt("data");
            if (data instanceof JSONObject) {
                JSONObject dataObj = (JSONObject) data;
                JSONArray seasonArray = dataObj.optJSONArray("seasons");
                if (seasonArray != null) {
                    return sortSeasons(parseSeasonList(seasonArray));
                }

                JSONArray episodesArray = dataObj.optJSONArray("episodes");
                if (episodesArray != null) {
                    if (looksLikeSeasonContainerArray(episodesArray)) {
                        return sortSeasons(parseSeasonList(episodesArray));
                    }
                    return sortSeasons(groupEpisodesBySeason(episodesArray));
                }
            }

            if (data instanceof JSONArray) {
                JSONArray dataArray = (JSONArray) data;
                if (looksLikeSeasonContainerArray(dataArray)) {
                    return sortSeasons(parseSeasonList(dataArray));
                }
                return sortSeasons(groupEpisodesBySeason(dataArray));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Episodes parse error", e);
        }

        return sortSeasons(seasons);
    }

    // ================= PLAYER =================

    public static PlayerData getPlayer(int postId) throws IOException {
        String url = API_BASE + "/player?post_id=" + postId + "&_any=1";

        PlayerData playerData = new PlayerData();

        try {
            JSONObject root = new JSONObject(httpGet(url));

            if (!root.optBoolean("error", true)) {
                Object dataObj = root.opt("data");
                parsePlayerData(playerData, dataObj);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Player parse error", e);
        }

        return playerData;
    }

    // ================= PARSER GENERAL =================

    private static List<ContentItem> parseList(String json) {
        List<ContentItem> list = new ArrayList<>();

        try {
            JSONObject root = new JSONObject(json);

            if (!root.optBoolean("error", true)) {

                JSONArray posts;

                // Algunos endpoints usan data.posts, otros directamente data
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

                    item.id = post.optInt("_id", post.optInt("ID", 0));
                    item.title = post.optString("title");
                    item.overview = post.optString("overview");
                    item.rating = post.optString("rating");
                    item.type = normalizePostType(firstNonEmpty(post.optString("type"), post.optString("post_type")));

                    String date = post.optString("release_date");
                    if (date != null && date.length() >= 4) {
                        item.year = date.substring(0, 4);
                    }

                    JSONObject images = post.optJSONObject("images");
                    if (images != null) {
                        item.posterUrl = normalizeUrl(images.optString("poster"));
                        item.backdropUrl = normalizeUrl(images.optString("backdrop"));
                    }
                    if (item.posterUrl == null || item.posterUrl.isEmpty()) {
                        item.posterUrl = normalizeUrl(post.optString("poster"));
                    }
                    if (item.overview == null || item.overview.isEmpty()) {
                        item.overview = post.optString("description");
                    }

                    String slug = post.optString("slug");
                    if (slug != null && !slug.isEmpty()) {
                        item.detailUrl = buildDetailUrl(item.type, slug);
                    } else {
                        item.detailUrl = normalizeUrl(post.optString("url"));
                    }

                    if (item.title != null && !item.title.isEmpty()) {
                        list.add(item);
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
        }

        return list;
    }

    public static List<String> getPlayableUrls(PlayerData playerData) {
        Set<String> urls = new LinkedHashSet<>();
        if (playerData == null) {
            return new ArrayList<>();
        }

        if (playerData.embedUrl != null && !playerData.embedUrl.isEmpty()) {
            urls.add(playerData.embedUrl);
        }

        if (playerData.servers != null) {
            for (Server server : playerData.servers) {
                if (server != null && server.url != null && !server.url.isEmpty()) {
                    urls.add(server.url);
                }
            }
        }

        return new ArrayList<>(urls);
    }

    // ================= CONVERSIÓN =================

    public static MediaItem toMediaItem(ContentItem item) {
        MediaItem mediaItem = new MediaItem(
                item.title != null ? item.title : "",
                item.year != null ? item.year : "",
                item.posterUrl != null ? item.posterUrl : "",
                item.detailUrl != null ? item.detailUrl : "",
                0
        );
        mediaItem.setPostId(item.id);
        mediaItem.setSynopsis(item.overview != null ? item.overview : "");
        mediaItem.setMediaType(item.type != null ? item.type : "movies");
        return mediaItem;
    }

    private static Season parseSeasonObject(JSONObject seasonObj, int fallbackSeasonNumber) {
        Season season = new Season();
        season.seasonNumber = seasonObj.optInt(
                "season_number",
                seasonObj.optInt("season", seasonObj.optInt("number", fallbackSeasonNumber))
        );

        JSONArray episodesArray = seasonObj.optJSONArray("episodes");
        Map<Integer, Episode> uniqueEpisodes = new LinkedHashMap<>();
        if (episodesArray != null) {
            for (int i = 0; i < episodesArray.length(); i++) {
                JSONObject episodeObj = episodesArray.optJSONObject(i);
                if (episodeObj == null) {
                    continue;
                }
                Episode episode = parseEpisode(episodeObj, season.seasonNumber, i + 1);
                if (episode != null) {
                    uniqueEpisodes.put(episode.id, episode);
                }
            }
        }

        season.episodes.addAll(uniqueEpisodes.values());

        return season;
    }

    private static List<Season> parseSeasonList(JSONArray seasonArray) {
        List<Season> seasons = new ArrayList<>();
        if (seasonArray == null) {
            return seasons;
        }

        for (int i = 0; i < seasonArray.length(); i++) {
            JSONObject seasonObj = seasonArray.optJSONObject(i);
            if (seasonObj == null) {
                continue;
            }
            Season season = parseSeasonObject(seasonObj, i + 1);
            if (!season.episodes.isEmpty()) {
                seasons.add(season);
            }
        }

        return seasons;
    }

    private static boolean looksLikeSeasonContainerArray(JSONArray array) {
        if (array == null || array.length() == 0) {
            return false;
        }

        JSONObject first = array.optJSONObject(0);
        if (first == null) {
            return false;
        }

        return first.optJSONArray("episodes") != null
                && (first.has("season") || first.has("season_number") || first.has("number"));
    }

    private static List<Season> groupEpisodesBySeason(JSONArray episodesArray) {
        Map<Integer, Season> seasonMap = new LinkedHashMap<>();

        for (int i = 0; i < episodesArray.length(); i++) {
            JSONObject episodeObj = episodesArray.optJSONObject(i);
            if (episodeObj == null) {
                continue;
            }

            Episode episode = parseEpisode(episodeObj, 1, i + 1);
            if (episode == null) {
                continue;
            }

            int seasonNumber = episode.seasonNumber > 0 ? episode.seasonNumber : 1;
            Season season = seasonMap.get(seasonNumber);
            if (season == null) {
                season = new Season();
                season.seasonNumber = seasonNumber;
                seasonMap.put(seasonNumber, season);
            }
            season.episodes.add(episode);
        }

        return new ArrayList<>(seasonMap.values());
    }

    private static Episode parseEpisode(JSONObject episodeObj, int fallbackSeasonNumber, int fallbackEpisodeNumber) {
        Episode episode = new Episode();
        episode.id = episodeObj.optInt("post_id", episodeObj.optInt("_id", episodeObj.optInt("ID", 0)));
        episode.seasonNumber = episodeObj.optInt("season_number", episodeObj.optInt("season", fallbackSeasonNumber));
        episode.episodeNumber = episodeObj.optInt(
                "episode_number",
                episodeObj.optInt("episode", episodeObj.optInt("number", fallbackEpisodeNumber))
        );
        episode.title = firstNonEmpty(episodeObj.optString("title"), episodeObj.optString("name"), "Episodio " + episode.episodeNumber);
        episode.overview = firstNonEmpty(episodeObj.optString("overview"), episodeObj.optString("description"));
        episode.stillUrl = normalizeUrl(firstNonEmpty(episodeObj.optString("still_path"), episodeObj.optString("still")));

        if (episode.id <= 0) {
            return null;
        }
        return episode;
    }

    private static List<Season> sortSeasons(List<Season> seasons) {
        if (seasons == null) {
            return new ArrayList<>();
        }

        for (Season season : seasons) {
            if (season == null || season.episodes == null) {
                continue;
            }

            for (Episode episode : season.episodes) {
                if (episode == null) {
                    continue;
                }
                if (episode.seasonNumber <= 0) {
                    episode.seasonNumber = season.seasonNumber > 0 ? season.seasonNumber : 1;
                }
            }

            Collections.sort(season.episodes, (a, b) -> {
                int aNum = (a != null && a.episodeNumber > 0) ? a.episodeNumber : Integer.MAX_VALUE;
                int bNum = (b != null && b.episodeNumber > 0) ? b.episodeNumber : Integer.MAX_VALUE;
                if (aNum != bNum) {
                    return Integer.compare(aNum, bNum);
                }
                int aId = a != null ? a.id : Integer.MAX_VALUE;
                int bId = b != null ? b.id : Integer.MAX_VALUE;
                return Integer.compare(aId, bId);
            });
        }

        Collections.sort(seasons, (a, b) -> {
            int aSeason = (a != null && a.seasonNumber > 0) ? a.seasonNumber : Integer.MAX_VALUE;
            int bSeason = (b != null && b.seasonNumber > 0) ? b.seasonNumber : Integer.MAX_VALUE;
            return Integer.compare(aSeason, bSeason);
        });

        return seasons;
    }

    private static String normalizePostType(String rawType) {
        if (rawType == null) {
            return "movies";
        }
        String value = rawType.trim().toLowerCase();
        if (value.isEmpty()) {
            return "movies";
        }
        if ("movie".equals(value)) {
            return "movies";
        }
        if ("tvshow".equals(value) || "series".equals(value)) {
            return "tvshows";
        }
        if ("anime".equals(value)) {
            return "animes";
        }
        return value;
    }

    private static String buildDetailUrl(String type, String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            return "";
        }
        String normalizedType = normalizePostType(type);
        if ("tvshows".equals(normalizedType)) {
            return SITE_BASE + "/series/" + slug + "/";
        }
        if ("animes".equals(normalizedType)) {
            return SITE_BASE + "/animes/" + slug + "/";
        }
        return SITE_BASE + "/peliculas/" + slug + "/";
    }

    private static void parsePlayerData(PlayerData playerData, Object dataObj) throws JSONException {
        if (dataObj == null) {
            return;
        }

        if (dataObj instanceof JSONArray) {
            JSONArray array = (JSONArray) dataObj;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                if (item instanceof JSONObject) {
                    addServerFromJson(playerData, (JSONObject) item, "Servidor " + (i + 1));
                }
            }
            return;
        }

        if (!(dataObj instanceof JSONObject)) {
            return;
        }

        JSONObject data = (JSONObject) dataObj;

        String embed = firstNonEmpty(
                data.optString("embed_url"),
                data.optString("iframe_url"),
                data.optString("url")
        );
        if (!embed.isEmpty()) {
            playerData.embedUrl = normalizeUrl(embed);
        }

        JSONArray servers = data.optJSONArray("servers");
        if (servers != null) {
            for (int i = 0; i < servers.length(); i++) {
                JSONObject server = servers.optJSONObject(i);
                if (server != null) {
                    addServerFromJson(playerData, server, "Servidor " + (i + 1));
                }
            }
        }

        JSONArray embeds = data.optJSONArray("embeds");
        if (embeds != null) {
            for (int i = 0; i < embeds.length(); i++) {
                Object raw = embeds.get(i);
                if (raw instanceof JSONObject) {
                    addServerFromJson(playerData, (JSONObject) raw, "Embed " + (i + 1));
                } else if (raw instanceof String) {
                    addServer(playerData, "Embed " + (i + 1), (String) raw);
                }
            }
        }

        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("servers".equals(key) || "embeds".equals(key) || "embed_url".equals(key)
                    || "iframe_url".equals(key) || "url".equals(key)) {
                continue;
            }
            Object raw = data.get(key);
            if (raw instanceof JSONObject) {
                addServerFromJson(playerData, (JSONObject) raw, "Servidor " + key);
            }
        }

        if ((playerData.embedUrl == null || playerData.embedUrl.isEmpty()) && !playerData.servers.isEmpty()) {
            playerData.embedUrl = playerData.servers.get(0).url;
        }
    }

    private static void addServerFromJson(PlayerData playerData, JSONObject serverJson, String fallbackName) {
        String name = firstNonEmpty(serverJson.optString("name"), serverJson.optString("lang"), fallbackName);
        String url = firstNonEmpty(serverJson.optString("url"), serverJson.optString("embed_url"), serverJson.optString("iframe_url"));
        addServer(playerData, name, url);
    }

    private static void addServer(PlayerData playerData, String name, String rawUrl) {
        String normalized = normalizeUrl(rawUrl);
        if (normalized == null || normalized.isEmpty()) {
            return;
        }
        Server server = new Server();
        server.name = (name == null || name.isEmpty()) ? "Servidor" : name;
        server.url = normalized;
        playerData.servers.add(server);
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("/")) {
            return SITE_BASE + value;
        }
        return SITE_BASE + "/" + value;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    // ================= HTTP =================

    private static String httpGet(String urlString) throws IOException {
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Referer", "https://allcalidad.re/");

            int responseCode = conn.getResponseCode();
            if (responseCode >= 400) {
                throw new IOException("HTTP " + responseCode + " for " + urlString);
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));

            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

            br.close();
            return sb.toString();

        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}