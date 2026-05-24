package sv.edu.catolica.rex.network.smart;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sv.edu.catolica.rex.models.MediaItem;

public class PelispediaScraper extends BaseSmartProvider {
    private static final String TAG = "PelispediaScraper";
    private static final String BASE = "https://pelispedia.mov";

    public List<MediaItem> search(String query, int limit) throws IOException {
        String url = BASE + "/search?s=" + URLEncoder.encode(query, "UTF-8");
        String html = httpGet(url);
        List<MediaItem> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out; 
        // naive parse: look for <a class="item" href="/pelicula/slug" ...>
        Pattern p = Pattern.compile("href=['\\\"](/(pelicula|serie)/[a-zA-Z0-9\\-_/]+)['\\\"]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && out.size() < limit) {
            String path = m.group(1);
            if (seen.contains(path)) continue;
            seen.add(path);
            String title = path.replaceAll("/|pelicula|serie", " ").trim();
            MediaItem item = new MediaItem(title, "", "", BASE + path, 0);
            out.add(item);
        }
        return out;
    }

    public List<MediaItem> getHomeItems(int limit) throws IOException {
        List<MediaItem> out = new ArrayList<>();
        String html = httpGet(BASE);
        if (html == null || html.isEmpty()) return out;
        Pattern p = Pattern.compile("href=['\\\"](/(pelicula|serie)/[a-zA-Z0-9\\-_/]+)['\\\"][^>]*><img[^>]+src=['\\\"]([^'\\\"]+)['\\\"][^>]*", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find() && out.size() < limit) {
            String path = m.group(1);
            String img = m.group(3);
            if (seen.contains(path)) continue;
            seen.add(path);
            String title = path.replaceAll("/|pelicula|serie", " ").trim();
            MediaItem item = new MediaItem(title, "", img, BASE + path, 0);
            out.add(item);
        }
        return out;
    }

    @Override
    public boolean supports(SmartContentType contentType) {
        // Pelispedia can provide movies and series; accept all content types
        return true;
    }

    @Override
    public String getName() {
        return "Pelispedia";
    }

    public MediaItem fetchBySlug(String slugPath) throws IOException {
        String url = BASE + slugPath;
        String html = httpGet(url);
        if (html == null || html.isEmpty()) return null;
        MediaItem item = new MediaItem("", "", "", url, 0);
        // very basic parsing to fill title and image
        Pattern t = Pattern.compile("<h1[^>]*>([^<]+)</h1>", Pattern.CASE_INSENSITIVE);
        Matcher mt = t.matcher(html);
        if (mt.find()) item.setTitulo(mt.group(1).trim());
        Pattern img = Pattern.compile("<img[^>]+src=['\"]([^'\"]+)['\"][^>]*class=['\"][^'\"]*poster[^'\"]*['\"]?", Pattern.CASE_INSENSITIVE);
        Matcher mi = img.matcher(html);
        if (mi.find()) item.setImagen(mi.group(1));
        return item;
    }

    public SmartPlaybackResult resolveMovie(MediaItem item) throws Exception {
        if (item == null) return new SmartPlaybackResult();
        String detail = item.getDetailUrl();
        if (detail == null || detail.isEmpty()) return new SmartPlaybackResult();
        String html = httpGet(detail);
        SmartPlaybackResult result = new SmartPlaybackResult();
        result.providerName = "Pelispedia";
        if (html == null || html.isEmpty()) return result;

        // find vidurl or xupalace occurrences
        Pattern vid = Pattern.compile("/vidurl/[^\\\"]+", Pattern.CASE_INSENSITIVE);
        Matcher mv = vid.matcher(html);
        Set<String> hosts = new LinkedHashSet<>();
        Set<String> streams = new LinkedHashSet<>();
        while (mv.find()) {
            String path = mv.group();
            try {
                String page = httpGet(BASE + path);
                if (page == null) continue;
                for (String u : extractUrls(page)) {
                    if (isStreamUrl(u)) streams.add(u);
                    else hosts.add(u);
                }
                for (String iframe : extractIframeUrls(page)) {
                    if (isStreamUrl(iframe)) streams.add(iframe);
                    else hosts.add(iframe);
                }
            } catch (Exception ignored) {}
        }

        // fallback: scan page for direct urls
        for (String u : extractUrls(html)) {
            if (isStreamUrl(u)) streams.add(u);
            else if (isHostUrl(u, null)) hosts.add(u);
        }

        result.hostUrls.addAll(hosts);
        result.streamUrls.addAll(streams);
        return result;
    }

    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode) throws Exception {
        // build episode path heuristics: /serie/{slug}/temporada/{season}/capitulo/{episode}
        String base = item.getDetailUrl();
        if (base == null) return new SmartPlaybackResult();
        String epPath = base;
        if (!epPath.endsWith("/")) epPath += "/";
        epPath += "temporada/" + season + "/capitulo/" + episode;
        SmartPlaybackResult res = new SmartPlaybackResult();
        res.providerName = "Pelispedia";
        try {
            String html = httpGet(epPath);
            for (String u : extractUrls(html)) {
                if (isStreamUrl(u)) res.streamUrls.add(u);
                else if (isHostUrl(u, null)) res.hostUrls.add(u);
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveEpisode failed: " + e.getMessage());
        }
        return res;
    }

    private static String httpGet(String urlString) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/json,application/javascript,*/*");
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");

            int code = conn.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " for " + urlString);
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public SmartPlaybackResult resolve(SmartPlaybackRequest request) throws Exception {
        if (request == null) return new SmartPlaybackResult();
        // create minimal MediaItem from request title
        String title = request.title == null ? "" : request.title;
        MediaItem m = new MediaItem(title, "", "", "", 0);
        if (request.isEpisodeRequest()) {
            return resolveEpisode(m, request.seasonNumber, request.episodeNumber);
        }
        return resolveMovie(m);
    }
}
