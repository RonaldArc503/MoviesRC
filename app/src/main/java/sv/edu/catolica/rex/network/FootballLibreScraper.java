package sv.edu.catolica.rex.network;

import android.util.Base64;
import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import sv.edu.catolica.rex.models.FootballMatch;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import sv.edu.catolica.rex.ui.player.FootballWebViewResolver;

/**
 * Scraper para https://futbol-libre.su/
 *
 * Flujo:
 *  1. getMatches()   → parsea la agenda del home y devuelve lista de partidos
 *                      con los streams ya identificados (channelName, quality, embedUrl).
 *  2. resolveStream()→ recibe un FootballStream y resuelve la URL reproducible
 *                      (.m3u8 / .mpd / .mp4) haciendo una segunda petición al embed.
 *
 * Uso recomendado (en background thread / executor):
 *   List<FootballMatch> matches = FootballLibreScraper.getInstance().getMatches();
 *   // Para reproducir el primer stream del primer partido:
 *   FootballMatch.FootballStream s = matches.get(0).getStreams().get(0);
 *   String playableUrl = FootballLibreScraper.getInstance().resolveStream(s);
 */
public class FootballLibreScraper {

    private static final String TAG = "FootballLibreScraper";

    // ── URLs base ──────────────────────────────────────────────────────────────
    private static final String BASE_URL    = "https://futbol-libre.su";
    private static final String AGENDA_URL  = BASE_URL + "/agenda/";

    // ── User-Agent idéntico al de tu app de películas ─────────────────────────
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    // ── Patrones para extraer URLs de stream desde HTML/JS ────────────────────
    // HLS (.m3u8)
    private static final Pattern PATTERN_M3U8 = Pattern.compile(
            "https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE);
    // DASH (.mpd)
    private static final Pattern PATTERN_MPD = Pattern.compile(
            "https?://[^\"'\\s<>]+\\.mpd[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE);
    // MP4 directo
    private static final Pattern PATTERN_MP4 = Pattern.compile(
            "https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE);
        private static final Pattern PATTERN_JW_MEDIA_ID = Pattern.compile(
            "cdn\\.jwplayer\\.com/(?:previews|players)/([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    // src en JWPlayer / source tags
    private static final Pattern PATTERN_SRC = Pattern.compile(
            "[\"']?(?:file|src)[\"']?\\s*:\\s*[\"']([^\"'\\s]+(?:m3u8|mpd|mp4)[^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);
    // source tag HTML
    private static final Pattern PATTERN_HTML_SRC = Pattern.compile(
            "<source[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static FootballLibreScraper instance;
    private final OkHttpClient client;

    private FootballLibreScraper() {
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public static synchronized FootballLibreScraper getInstance() {
        if (instance == null) instance = new FootballLibreScraper();
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PASO 1 – Obtener lista de partidos del día
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Descarga la agenda y devuelve los partidos con sus streams.
     * Llama desde un hilo de fondo (AsyncTask, Executor, Coroutine via JNI...).
     *
     * @return lista de partidos; vacía si no hay partidos o falla la red.
     */
    public List<FootballMatch> getMatches() {
        List<FootballMatch> matches = new ArrayList<>();
        try {
            // La agenda se carga desde /agenda/ embebida en el home
            String html = fetchHtml(AGENDA_URL, BASE_URL + "/");
            if (html == null || html.isEmpty()) {
                // Intentar desde el home directamente
                html = fetchHtml(BASE_URL + "/", null);
            }
            if (html == null) {
                Log.e(TAG, "No se pudo obtener la agenda");
                return matches;
            }
            matches = parseAgenda(html);
        } catch (Exception e) {
            Log.e(TAG, "Error en getMatches: " + e.getMessage(), e);
        }
        return matches;
    }

    /**
     * Parsea el HTML de la agenda y construye los objetos FootballMatch.
     *
     * Estructura esperada:
     * <ul>
     *   <li class="CHA">
     *     <a href="#" class="active">Título<span class="t">HH:MM</span></a>
     *     <ul>
     *       <li class="subitem1">
     *         <a href="https://futbol-libre.su/eventos.html?r=BASE64" target="_top">
     *           Canal<span>Calidad Xp</span>
     *         </a>
     *       </li>
     *     </ul>
     *   </li>
     * </ul>
     */
    private List<FootballMatch> parseAgenda(String html) {
        List<FootballMatch> matches = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html, BASE_URL);

            // Los partidos reales vienen como <li class="CHA|..."> con:
            // - un <a href="#"> como primer hijo
            // - un <ul> con los streams en <li class="subitem1">
            Elements matchItems = doc.select("ul.menu > li[class], li[class]:has(> a):has(> ul)");

            for (Element li : matchItems) {
                try {
                    Element titleLink = null;
                    Element streamsList = null;
                    for (Element child : li.children()) {
                        if (titleLink == null && "a".equals(child.tagName())) {
                            titleLink = child;
                        } else if (streamsList == null && "ul".equals(child.tagName())) {
                            streamsList = child;
                        }
                    }

                    if (titleLink == null || streamsList == null) continue;
                    if (!"#".equals(titleLink.attr("href"))) continue;

                    // ── Título y hora ──────────────────────────────────────────
                    String fullTitle = titleLink.ownText().trim();
                    String time = "";
                    Element timeSpan = titleLink.selectFirst("span.t");
                    if (timeSpan != null) {
                        time = convertAgendaTime(timeSpan.text().trim());
                        // Quitar el tiempo del título si está incluido
                        fullTitle = fullTitle.replace(timeSpan.text(), "").trim();
                    }

                    // ── Categoría (clase CSS del li) ──────────────────────────
                    String category = li.className().trim();
                    // Si la clase tiene múltiples, tomamos la primera
                    if (category.contains(" ")) category = category.split("\\s+")[0];

                    // ── Competición (parte antes del ":" o el título completo) ─
                    String competition = fullTitle;
                    if (fullTitle.contains(":")) {
                        competition = fullTitle.substring(0, fullTitle.indexOf(":")).trim();
                    }

                    // ── Streams ───────────────────────────────────────────────
                    List<FootballMatch.FootballStream> streams = new ArrayList<>();
                    Elements streamItems = streamsList.select("li.subitem1 > a[href*='eventos.html'], a[href*='eventos.html'][href*='r=']");

                    for (Element streamLink : streamItems) {
                        String eventsUrl = streamLink.attr("abs:href");
                        if (eventsUrl.isEmpty()) eventsUrl = streamLink.attr("href");
                        if (!eventsUrl.contains("eventos.html")) continue;

                        // Decodificar el parámetro r= (base64 → URL del embed)
                        String embedUrl = decodeEventsUrl(eventsUrl);
                        if (embedUrl == null) continue;

                        // Nombre del canal y calidad
                        String channelName = streamLink.ownText().trim();
                        String quality = "";
                        Element qualitySpan = streamLink.selectFirst("span");
                        if (qualitySpan != null) {
                            quality = qualitySpan.text()
                                    .replace("Calidad", "").trim();
                        }

                        streams.add(new FootballMatch.FootballStream(
                                channelName, quality, eventsUrl, embedUrl));
                    }

                    if (!streams.isEmpty()) {
                        matches.add(new FootballMatch(
                                fullTitle, competition, time, category, streams));
                    }

                } catch (Exception e) {
                    Log.w(TAG, "Error parseando un partido: " + e.getMessage());
                }
            }

            Log.d(TAG, "Partidos encontrados: " + matches.size());
        } catch (Exception e) {
            Log.e(TAG, "Error parseando agenda: " + e.getMessage(), e);
        }
        return matches;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PASO 2 – Resolver URL reproducible de un stream
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Dado un FootballStream con embedUrl, hace una petición HTTP al embed
     * y extrae la URL directa reproducible (.m3u8, .mpd o .mp4).
     *
     * Si ya tiene playableUrl guardada, la devuelve directamente (caché).
     *
     * @param stream El stream a resolver.
     * @return URL reproducible, o null si no se pudo extraer.
     */
    public String resolveStream(FootballMatch.FootballStream stream) {
        if (stream == null) return null;

        String embedUrl = stream.getEmbedUrl();
        if (embedUrl == null || embedUrl.isEmpty()) return null;

        // Los embeds de JWPlayer cambian con frecuencia; para esos casos
        // se resuelve siempre de nuevo desde el preview/playback JSON.
        boolean jwPlayerLiveSource = isJwPlayerLiveSource(embedUrl);

        // Caché: si ya fue resuelto antes, reutilizar solo fuera de JWPlayer live.
        if (!jwPlayerLiveSource && stream.getPlayableUrl() != null && !stream.getPlayableUrl().isEmpty()) {
            return stream.getPlayableUrl();
        }

        try {
            if (jwPlayerLiveSource) {
                String livePlayable = resolveJwPlayerLiveUrl(embedUrl, stream.getEventsUrl());
                if (livePlayable != null && isNativePlayableFootballUrl(livePlayable)) {
                    stream.setPlayableUrl(livePlayable);
                    Log.d(TAG, "Stream JW live resuelto: " + livePlayable);
                    return livePlayable;
                }
            }

            // Referer: la página de eventos que abre el embed
            String referer = stream.getEventsUrl() != null
                    ? stream.getEventsUrl()
                    : BASE_URL + "/";

            String html = fetchHtml(embedUrl, referer);
            if (html == null) return null;

            String playable = extractPlayableUrl(html, embedUrl);
            if (playable != null) {
                stream.setPlayableUrl(playable);
                Log.d(TAG, "Stream resuelto: " + playable);
            }
            return playable;

        } catch (Exception e) {
            Log.e(TAG, "Error resolviendo stream " + embedUrl + ": " + e.getMessage(), e);
            return null;
        }
    }

    private boolean isJwPlayerLiveSource(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("cdn.jwplayer.com/")
                && PATTERN_JW_MEDIA_ID.matcher(url).find();
    }

    private String resolveJwPlayerLiveUrl(String sourceUrl, String referer) {
        String mediaId = extractJwPlayerMediaId(sourceUrl);
        if (mediaId == null || mediaId.isEmpty()) {
            return null;
        }

        String playbackJsonUrl = "https://cdn.jwplayer.com/v2/sites/2TG5q0qV/media/"
                + mediaId + "/playback.json";
        String json = fetchHtml(playbackJsonUrl, referer != null ? referer : "https://cdn.jwplayer.com/");
        if (json == null || json.isEmpty()) {
            return null;
        }

        Matcher m3u8 = PATTERN_M3U8.matcher(json);
        if (m3u8.find()) {
            return cleanUrl(m3u8.group());
        }

        Matcher mpd = PATTERN_MPD.matcher(json);
        if (mpd.find()) {
            return cleanUrl(mpd.group());
        }

        Matcher mp4 = PATTERN_MP4.matcher(json);
        if (mp4.find()) {
            return cleanUrl(mp4.group());
        }

        return null;
    }

    private String extractJwPlayerMediaId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher matcher = PATTERN_JW_MEDIA_ID.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Callback para la resolución asíncrona con fallback a WebView.
     * Se asegura de invocar el callback en el hilo principal (UI thread).
     */
    public interface ResolveCallback {
        void onResolved(String url);
        void onFailed(String reason);
    }

    /**
     * Intenta resolver el stream vía HTTP (rápido). Si devuelve null y se
     * proporciona un `Context`, intenta usar `FootballWebViewResolver` en el
     * hilo UI como fallback. La respuesta se entrega en el hilo principal.
     *
     * @param ctx Contexto (requerido solo para fallback WebView). Puede ser null.
     */
    public void resolveStreamWithFallback(Context ctx, FootballMatch.FootballStream stream, ResolveCallback cb) {
        if (cb == null) return;

        // Intento rápido por HTTP (sin UI)
        String playable = null;
        try {
            playable = resolveStream(stream);
        } catch (Exception e) {
            // seguir al fallback
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        if (playable != null && !playable.isEmpty()) {
            final String p = playable;
            mainHandler.post(() -> cb.onResolved(p));
            return;
        }

        // Si no hay Contexto, responder fallo inmediatamente
        if (ctx == null) {
            mainHandler.post(() -> cb.onFailed("No se pudo resolver el stream y no hay contexto para fallback"));
            return;
        }

        // Ejecutar WebView resolver en hilo UI
        mainHandler.post(() -> {
            FootballWebViewResolver resolver = new FootballWebViewResolver(ctx);
            resolver.resolve(stream, new FootballWebViewResolver.OnStreamResolvedListener() {
                @Override
                public void onResolved(String url) {
                    cb.onResolved(url);
                }

                @Override
                public void onFailed(String reason) {
                    cb.onFailed(reason != null ? reason : "Resolución via WebView fallida");
                }
            });
        });
    }

    /**
     * Resuelve todos los streams de un partido en secuencia.
     * Útil para pre-cargar opciones de calidad.
     */
    public void resolveAllStreams(FootballMatch match) {
        if (match == null || match.getStreams() == null) return;
        for (FootballMatch.FootballStream stream : match.getStreams()) {
            resolveStream(stream);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers privados
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Decodifica el parámetro r= de una URL de eventos.html.
     * El parámetro es la URL del embed codificada en Base64.
     *
     * Ejemplo:
     *   eventsUrl = "https://futbol-libre.su/eventos.html?r=aHR0cHM6Ly9lc3ZpZGVvZnkuY29tL3R2ZWxhMWVzLnBocA=="
     *   resultado  = "https://esvideofy.com/tvela1es.php"
     */
    private String decodeEventsUrl(String eventsUrl) {
        try {
            URI uri = new URI(eventsUrl);
            String query = uri.getRawQuery();
            if (query == null) return null;

            for (String param : query.split("&")) {
                if (param.startsWith("r=")) {
                    String encoded = URLDecoder.decode(param.substring(2), "UTF-8");
                    // android.util.Base64
                    byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                    return new String(decoded, StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Exception e) {
            // Si falla el parseo de URI, intentar por índice de string
            try {
                int idx = eventsUrl.indexOf("?r=");
                if (idx != -1) {
                    String encoded = eventsUrl.substring(idx + 3);
                    byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                    return new String(decoded, StandardCharsets.UTF_8).trim();
                }
            } catch (Exception e2) {
                Log.w(TAG, "No se pudo decodificar: " + eventsUrl);
            }
        }
        return null;
    }

    /**
     * Realiza una petición GET y devuelve el HTML como String.
     *
     * @param url     URL a descargar.
     * @param referer Referer header (puede ser null).
     */
    private String convertAgendaTime(String rawTime) {
        if (rawTime == null) return "";
        String clean = rawTime.trim();
        Matcher matcher = PATTERN_TIME.matcher(clean);
        if (!matcher.matches()) {
            return clean;
        }

        try {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            int timezoneMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
            int totalMinutes = (hour * 60) + minute + timezoneMinutes - 60;
            totalMinutes = ((totalMinutes % 1440) + 1440) % 1440;
            return String.format(Locale.US, "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
        } catch (Exception e) {
            return clean;
        }
    }

    private String fetchHtml(String url, String referer) {
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "keep-alive");

            if (referer != null && !referer.isEmpty()) {
                builder.header("Referer", referer);
                // Extraer origen del referer
                try {
                    URI refUri = new URI(referer);
                    builder.header("Origin", refUri.getScheme() + "://" + refUri.getHost());
                } catch (Exception ignored) {}
            }

            try (Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "HTTP " + response.code() + " para: " + url);
                    return null;
                }
                if (response.body() == null) return null;
                return response.body().string();
            }
        } catch (IOException e) {
            Log.e(TAG, "fetchHtml error para " + url + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Extrae la primera URL reproducible del HTML de un embed.
     * Prioridad: .mpd > .m3u8 > .mp4 > src JS > HTML source tag.
     *
     * @param html       Contenido del embed.
     * @param sourceUrl  URL original del embed (para resolver rutas relativas).
     */
    private String extractPlayableUrl(String html, String sourceUrl) {
        // 1. DASH (.mpd) — máxima prioridad por calidad
        Matcher mMpd = PATTERN_MPD.matcher(html);
        while (mMpd.find()) {
            String candidate = cleanNativePlayableUrl(mMpd.group());
            if (candidate != null) return candidate;
        }

        // 2. HLS (.m3u8)
        Matcher mM3u8 = PATTERN_M3U8.matcher(html);
        while (mM3u8.find()) {
            String candidate = cleanNativePlayableUrl(mM3u8.group());
            if (candidate != null) return candidate;
        }

        // 3. src/file en JS (JWPlayer, VideoJS, Bitmovin, etc.)
        Matcher mSrc = PATTERN_SRC.matcher(html);
        while (mSrc.find()) {
            String candidate = cleanNativePlayableUrl(mSrc.group(1));
            if (candidate != null) return candidate;
        }

        // 4. <source src="...">
        Matcher mHtml = PATTERN_HTML_SRC.matcher(html);
        while (mHtml.find()) {
            String candidate = cleanNativePlayableUrl(mHtml.group(1));
            if (candidate != null) return candidate;
        }

        // 5. MP4 directo
        Matcher mMp4 = PATTERN_MP4.matcher(html);
        while (mMp4.find()) {
            String candidate = cleanNativePlayableUrl(mMp4.group());
            if (candidate != null) return candidate;
        }

        Log.d(TAG, "No se encontró URL reproducible en: " + sourceUrl);
        return null;
    }

    /**
     * Limpia una URL extraída: quita escapes, comillas, etc.
     */
    private String cleanNativePlayableUrl(String url) {
        String cleaned = cleanUrl(url);
        return isNativePlayableFootballUrl(cleaned) ? cleaned : null;
    }

    private boolean isNativePlayableFootballUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String lc = url.toLowerCase(Locale.ROOT);
        if (lc.contains("/dash/enc/")
                || lc.contains("/cenc")
                || lc.contains("cenc_")
                || lc.contains("cenc.mpd")) {
            Log.d(TAG, "Stream DASH CENC requiere reproductor WebView: " + url);
            return false;
        }
        return true;
    }

    private String cleanUrl(String url) {
        if (url == null) return null;
        return url.replace("\\/", "/")
                  .replace("\\u0026", "&")
                  .replace("%5C", "/")
                  .trim();
    }
}
