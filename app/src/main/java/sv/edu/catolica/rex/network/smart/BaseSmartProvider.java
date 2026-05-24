package sv.edu.catolica.rex.network.smart;

import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class BaseSmartProvider implements SmartProvider {

    private static final String TAG = "BaseSmartProvider";
    private static final int TIMEOUT_MS = 20000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    protected SmartPlaybackResult resolveFromEmbed(String embedUrl, String providerName, List<String> preferredHosts) throws IOException {
        SmartPlaybackResult result = new SmartPlaybackResult();
        result.providerName = providerName;
        result.embedUrl = embedUrl;

        String html = httpGet(embedUrl);
        if (html == null || html.trim().isEmpty()) {
            return result;
        }

        if (isXupalaceNoFolders(embedUrl, html)) {
            result.embedUrl = "";
            return result;
        }

        Set<String> hosts = new LinkedHashSet<>();
        Set<String> streams = new LinkedHashSet<>();

        List<String> urls = extractUrls(html);
        for (String url : urls) {
            if (isStreamUrl(url)) {
                streams.add(url);
            } else if (isHostUrl(url, preferredHosts)) {
                hosts.add(url);
            }
        }

        // Xupalace exposes playable links through JS handlers like:
        // go_to_playerVast('https://hglink.to/e/....', ...)
        for (String jsPlayerUrl : extractPlayerActionUrls(html)) {
            if (isStreamUrl(jsPlayerUrl)) {
                streams.add(jsPlayerUrl);
            } else if (isHostUrl(jsPlayerUrl, preferredHosts)) {
                hosts.add(jsPlayerUrl);
            }
        }

        for (String iframe : extractIframeUrls(html)) {
            if (isStreamUrl(iframe)) {
                streams.add(iframe);
                continue;
            }
            hosts.add(iframe);
            try {
                String nestedHtml = httpGet(iframe, needsEmbed69Referer(iframe) ? embedUrl : iframe);
                if (nestedHtml == null || nestedHtml.trim().isEmpty()) {
                    continue;
                }
                List<String> nestedUrls = extractUrls(nestedHtml);
                for (String nestedUrl : nestedUrls) {
                    if (isStreamUrl(nestedUrl)) {
                        streams.add(nestedUrl);
                    } else if (isHostUrl(nestedUrl, preferredHosts)) {
                        hosts.add(nestedUrl);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        addEmbed69DecodedLinks(html, hosts, streams, preferredHosts);
        addVimeosDirectLinks(html, streams);
        resolveDirectStreamsFromHosts(hosts, streams, embedUrl, preferredHosts);

        result.hostUrls.addAll(hosts);
        result.streamUrls.addAll(streams);
        return result;
    }

    private static String httpGet(String urlString) throws IOException {
        return httpGet(urlString, urlString);
    }

    private static String httpGet(String urlString, String referer) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/html,application/json,application/javascript,*/*");
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
            conn.setRequestProperty("Referer", (referer == null || referer.trim().isEmpty()) ? urlString : referer);

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
        } catch (IOException e) {
            Log.w(TAG, "httpGet failed: " + e.getMessage());
            throw e;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void resolveDirectStreamsFromHosts(Set<String> hosts, Set<String> streams, String parentReferer, List<String> preferredHosts) {
        List<String> snapshot = new ArrayList<>(hosts);
        for (String hostUrl : snapshot) {
            if (hostUrl == null || hostUrl.trim().isEmpty()) {
                continue;
            }

            String lc = hostUrl.toLowerCase(Locale.ROOT);
            if (!(lc.contains("/embed/")
                    || lc.contains("minochinos.com")
                    || lc.contains("hglink.to")
                    || lc.contains("hgcloud.to")
                    || lc.contains("vibuxer.com")
                    || lc.contains("filelions.to")
                    || lc.contains("bysedikamoum.com")
                    || lc.contains("voe.sx")
                    || lc.contains("voe.network"))) {
                continue;
            }

            try {
                String hostHtml = httpGet(hostUrl, needsEmbed69Referer(hostUrl) ? parentReferer : hostUrl);
                if (hostHtml == null || hostHtml.trim().isEmpty()) {
                    continue;
                }

                for (String url : extractUrls(hostHtml)) {
                    if (isStreamUrl(url)) {
                        streams.add(url);
                    } else if (isHostUrl(url, preferredHosts)) {
                        hosts.add(url);
                    }
                }

                for (String iframe : extractIframeUrls(hostHtml)) {
                    if (isStreamUrl(iframe)) {
                        streams.add(iframe);
                    } else if (isHostUrl(iframe, preferredHosts)) {
                        hosts.add(iframe);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    protected static List<String> extractIframeUrls(String html) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null) return new ArrayList<>();

        Pattern iframePattern = Pattern.compile("<iframe[^>]+src=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher matcher = iframePattern.matcher(html);
        while (matcher.find()) {
            String url = normalizeUrl(matcher.group(1));
            if (!url.isEmpty()) out.add(url);
        }
        return new ArrayList<>(out);
    }

    protected static List<String> extractUrls(String html) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null) return new ArrayList<>();

        Pattern urlPattern = Pattern.compile("https?:\\\\?/\\\\?/[^\"'<>\\s)]+", Pattern.CASE_INSENSITIVE);
        Matcher matcher = urlPattern.matcher(html);
        while (matcher.find()) {
            String url = normalizeUrl(matcher.group());
            if (!url.isEmpty()) out.add(url);
        }

        Pattern cleanUrlPattern = Pattern.compile("https?://[^\"'<>\\s)]+", Pattern.CASE_INSENSITIVE);
        Matcher cleanMatcher = cleanUrlPattern.matcher(html);
        while (cleanMatcher.find()) {
            String url = normalizeUrl(cleanMatcher.group());
            if (!url.isEmpty()) out.add(url);
        }

        return new ArrayList<>(out);
    }

    protected static List<String> extractPlayerActionUrls(String html) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null || html.isEmpty()) {
            return new ArrayList<>();
        }

        Pattern vastAction = Pattern.compile(
                "go_to_playerVast\\(\\s*['\"](https?://[^'\"]+)['\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher vastMatcher = vastAction.matcher(html);
        while (vastMatcher.find()) {
            String url = normalizeUrl(vastMatcher.group(1));
            if (!url.isEmpty()) {
                out.add(url);
            }
        }

        Pattern plainAction = Pattern.compile(
                "go_to_player\\(\\s*['\"](https?://[^'\"]+)['\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher plainMatcher = plainAction.matcher(html);
        while (plainMatcher.find()) {
            String url = normalizeUrl(plainMatcher.group(1));
            if (!url.isEmpty()) {
                out.add(url);
            }
        }

        return new ArrayList<>(out);
    }

    protected static boolean isStreamUrl(String url) {
        if (url == null) return false;
        String lc = url.toLowerCase(Locale.ROOT);
        if (isStaticAssetUrl(lc)) return false;
        return lc.contains(".m3u8")
                || lc.contains(".mpd")
                || lc.contains(".mp4")
                || lc.contains("master.m3u8")
                || lc.contains("playlist.m3u8");
    }

    protected static boolean isHostUrl(String url, List<String> preferredHosts) {
        if (url == null || url.isEmpty()) return false;

        String lc = url.toLowerCase(Locale.ROOT);
        if (isStaticAssetUrl(lc)) return false;
        if (lc.contains("embed69.org") || lc.contains("xupalace.org")) {
            return true;
        }

        // Prefer links coming from allcalidad (site-specific player pages / APIs)
        if (lc.contains("allcalidad.re") || lc.contains("allcalidad.")) {
            return true;
        }

        if (preferredHosts != null) {
            for (String host : preferredHosts) {
                if (host != null && !host.isEmpty() && lc.contains(host.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }

        return lc.contains("streamwish")
                || lc.contains("hglink.to")
                || lc.contains("hgcloud.to")
                || lc.contains("vibuxer.com")
                || lc.contains("filelions.to")
                || lc.contains("filemoon")
                || lc.contains("bysedikamoum.com")
                || lc.contains("vidhide")
                || lc.contains("minochinos.com")
                || lc.contains("stape")
                || lc.contains("streamtape")
                || lc.contains("vox")
                || lc.contains("1fichier")
                || lc.contains("uqload")
                || lc.contains("dood")
                || lc.contains("vidsrc")
                || lc.contains("mixdrop")
                || lc.contains("ok.ru")
                || lc.contains("voe.sx")
                || lc.contains("voe.network")
                || lc.contains("vimeos.net");
    }

    private static boolean isStaticAssetUrl(String lcUrl) {
        if (lcUrl == null) return true;
        return lcUrl.contains("fontawesome")
                || lcUrl.endsWith(".css") || lcUrl.contains(".css?")
                || lcUrl.endsWith(".js") || lcUrl.contains(".js?")
                || lcUrl.endsWith(".json") || lcUrl.contains(".json?")
                || lcUrl.endsWith(".png") || lcUrl.contains(".png?")
                || lcUrl.endsWith(".jpg") || lcUrl.contains(".jpg?")
                || lcUrl.endsWith(".jpeg") || lcUrl.contains(".jpeg?")
                || lcUrl.endsWith(".webp") || lcUrl.contains(".webp?")
                || lcUrl.endsWith(".gif") || lcUrl.contains(".gif?")
                || lcUrl.endsWith(".svg") || lcUrl.contains(".svg?")
                || lcUrl.endsWith(".ico") || lcUrl.contains(".ico?")
                || lcUrl.endsWith(".woff") || lcUrl.contains(".woff?")
                || lcUrl.endsWith(".woff2") || lcUrl.contains(".woff2?")
                || lcUrl.endsWith(".ttf") || lcUrl.contains(".ttf?")
                || lcUrl.endsWith(".otf") || lcUrl.contains(".otf?")
                || lcUrl.endsWith(".eot") || lcUrl.contains(".eot?");
    }

    protected static String normalizeImdbId(String imdbId) {
        if (imdbId == null) return "";
        String val = imdbId.trim();
        if (val.matches("tt\\d+")) {
            return val;
        }
        return "";
    }

    protected static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String url = raw.trim();
        if (url.isEmpty()) return "";
        url = url.replace("\\/", "/");
        url = url.replace("\u0026", "&");
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "";
    }

    private static void addEmbed69DecodedLinks(String html, Set<String> hosts, Set<String> streams, List<String> preferredHosts) {
        if (html == null || html.isEmpty()) {
            return;
        }

        try {
            Pattern dataLinkPattern = Pattern.compile("let\\s+dataLink\\s*=\\s*(\\[.*?\\]);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = dataLinkPattern.matcher(html);
            if (!matcher.find()) {
                return;
            }

            JSONArray files = new JSONArray(matcher.group(1));
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null) continue;

                JSONArray sortedEmbeds = file.optJSONArray("sortedEmbeds");
                if (sortedEmbeds == null) continue;

                for (int j = 0; j < sortedEmbeds.length(); j++) {
                    JSONObject embed = sortedEmbeds.optJSONObject(j);
                    if (embed == null) continue;

                    String decoded = decodeEmbed69SignedLink(embed.optString("link", ""));
                    addResolvedLink(decoded, hosts, streams, preferredHosts);

                    String download = decodeEmbed69SignedLink(embed.optString("download", ""));
                    addResolvedLink(download, hosts, streams, preferredHosts);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode embed69 dataLink: " + e.getMessage());
        }
    }

    private static void addVimeosDirectLinks(String html, Set<String> streams) {
        if (html == null || html.isEmpty() || streams == null) {
            return;
        }

        Set<String> candidates = new LinkedHashSet<>();

        Pattern explicitHlsPattern = Pattern.compile(
                "https?:\\\\?/\\\\?/[^\"'<>\\s]*vimeos\\.net/[^\"'<>\\s]*master\\.m3u8[^\"'<>\\s]*",
                Pattern.CASE_INSENSITIVE);
        Matcher escapedMatcher = explicitHlsPattern.matcher(html);
        while (escapedMatcher.find()) {
            String candidate = normalizeUrl(escapedMatcher.group());
            if (!candidate.isEmpty()) {
                candidates.add(candidate);
            }
        }

        Pattern cleanHlsPattern = Pattern.compile(
                "https?://[^\"'<>\\s]*vimeos\\.net/[^\"'<>\\s]*master\\.m3u8[^\"'<>\\s]*",
                Pattern.CASE_INSENSITIVE);
        Matcher cleanMatcher = cleanHlsPattern.matcher(html);
        while (cleanMatcher.find()) {
            String candidate = normalizeUrl(cleanMatcher.group());
            if (!candidate.isEmpty()) {
                candidates.add(candidate);
            }
        }

        Pattern posterPattern = Pattern.compile(
                "(https?://([a-z0-9.-]*vimeos\\.net))/i/\\d+/\\d+/([a-z0-9]+)\\.(jpg|jpeg|webp|png)",
                Pattern.CASE_INSENSITIVE);
        Matcher posterMatcher = posterPattern.matcher(html);
        while (posterMatcher.find()) {
            String base = posterMatcher.group(1);
            String code = posterMatcher.group(3);
            if (base == null || code == null) {
                continue;
            }
            String directHls = normalizeUrl(base + "/hls2/urlset/" + code + "_/master.m3u8");
            if (!directHls.isEmpty()) {
                candidates.add(directHls);
            }
        }

        for (String candidate : candidates) {
            if (isStreamUrl(candidate)) {
                streams.add(candidate);
            }
        }
    }

    private static void addResolvedLink(String rawUrl, Set<String> hosts, Set<String> streams, List<String> preferredHosts) {
        String normalized = normalizeUrl(rawUrl);
        if (normalized.isEmpty()) {
            return;
        }

        String lc = normalized.toLowerCase(Locale.ROOT);
        if (isStaticAssetUrl(lc)) {
            return;
        }
        if (lc.contains("embed69.org/d/")) {
            return;
        }

        if (isStreamUrl(normalized)) {
            streams.add(normalized);
            return;
        }

        if (isHostUrl(normalized, preferredHosts) || lc.startsWith("https://") || lc.startsWith("http://")) {
            hosts.add(normalized);
        }
    }

    private static String decodeEmbed69SignedLink(String encoded) {
        if (encoded == null) {
            return "";
        }

        String token = encoded.trim().replace("`", "");
        if (token.isEmpty()) {
            return "";
        }

        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return normalizeUrl(token);
            }

            byte[] payload = Base64.decode(toBase64UrlPadded(parts[1]), Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(payload));
            return normalizeUrl(json.optString("link", ""));
        } catch (Exception ignored) {
            return normalizeUrl(token);
        }
    }

    private static String toBase64UrlPadded(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        int rem = out.length() % 4;
        if (rem == 2) out += "==";
        else if (rem == 3) out += "=";
        else if (rem == 1) out += "===";
        return out;
    }

    private static boolean needsEmbed69Referer(String url) {
        if (url == null) {
            return false;
        }
        String lc = url.toLowerCase(Locale.ROOT);
        return lc.contains("minochinos.com")
                || lc.contains("hglink.to")
                || lc.contains("hgcloud.to")
                || lc.contains("vibuxer.com")
                || lc.contains("filelions.to")
                || lc.contains("bysedikamoum.com")
                || lc.contains("voe.sx")
                || lc.contains("voe.network");
    }

    private static boolean isXupalaceNoFolders(String embedUrl, String html) {
        if (embedUrl == null || html == null) {
            return false;
        }
        String lcUrl = embedUrl.toLowerCase(Locale.ROOT);
        if (!lcUrl.contains("xupalace.org")) {
            return false;
        }
        String lcHtml = html.toLowerCase(Locale.ROOT);
        return lcHtml.contains("no folders found")
                || lcHtml.contains("no se encontraron folders")
                || lcHtml.contains("\"error\":\"no folders found\"");
    }

    protected static String episodeToken(int season, int episode) {
        int safeSeason = season > 0 ? season : 1;
        int safeEpisode = episode > 0 ? episode : 1;
        return safeSeason + "x" + String.format(Locale.US, "%02d", safeEpisode);
    }
}
