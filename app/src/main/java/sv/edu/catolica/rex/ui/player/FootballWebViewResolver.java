package sv.edu.catolica.rex.ui.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Pattern;

import sv.edu.catolica.rex.models.FootballMatch;

/**
 * Helper que usa un WebView headless (invisible) para resolver embeds de fútbol
 * que requieren ejecución de JavaScript (JWPlayer, Bitmovin, etc.) y no pueden
 * ser scrapeados con una simple petición HTTP.
 *
 * Uso:
 *   FootballWebViewResolver resolver = new FootballWebViewResolver(context);
 *   resolver.resolve(stream, url -> {
 *       // url es la URL .m3u8/.mpd directa, ya en el hilo principal
 *       launchPlayer(url);
 *   });
 *
 * Llama a resolver.destroy() cuando ya no se necesite (ej. en onDestroy).
 */
public class FootballWebViewResolver {

    private static final String TAG = "FootballWebViewResolver";

    // Patrones de URLs de stream que queremos interceptar
    private static final Pattern PATTERN_STREAM = Pattern.compile(
            ".+\\.(m3u8|mpd)(\\?.+)?$", Pattern.CASE_INSENSITIVE);

    // Timeout: si en 12s no se encontró stream, cancelar
    private static final long TIMEOUT_MS = 12_000;

    public interface OnStreamResolvedListener {
        void onResolved(String streamUrl);
        void onFailed(String reason);
    }

    private WebView webView;
    private final Context context;
    private Runnable timeoutRunnable;
    private android.os.Handler mainHandler;
    private boolean resolved = false;

    public FootballWebViewResolver(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    /**
     * Inicializa el WebView y carga el embed del stream.
     * Debe llamarse desde el hilo principal (UI thread).
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void resolve(FootballMatch.FootballStream stream,
                        OnStreamResolvedListener listener) {
        if (stream == null || stream.getEmbedUrl() == null) {
            listener.onFailed("Stream o embedUrl nulo");
            return;
        }

        resolved = false;
        String embedUrl = stream.getEmbedUrl();
        String referer  = stream.getEventsUrl() != null
                ? stream.getEventsUrl()
                : "https://futbol-libre.su/";

        // Crear WebView programáticamente (sin layout)
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        // Interfaz JS → Java para recibir la URL extraída desde JS
        webView.addJavascriptInterface(new JsBridge(listener), "RexBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Interceptar .m3u8 / .mpd si el embed redirige directamente
                if (PATTERN_STREAM.matcher(url).matches()) {
                    notifyResolved(url, listener);
                    return true; // no cargar en WebView
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (PATTERN_STREAM.matcher(url).matches()) {
                    mainHandler.post(() -> notifyResolved(url, listener));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inyectar JS para extraer URL de stream de players conocidos
                injectExtractorScript(view, listener);
            }
        });

        // Timeout de seguridad
        timeoutRunnable = () -> {
            if (!resolved) {
                Log.w(TAG, "Timeout resolviendo: " + embedUrl);
                listener.onFailed("Timeout al cargar el stream");
                destroy();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS);

        // Cargar embed con Referer
        android.webkit.WebResourceRequest headers = null;
        java.util.HashMap<String, String> extraHeaders = new java.util.HashMap<>();
        extraHeaders.put("Referer", referer);
        webView.loadUrl(embedUrl, extraHeaders);

        Log.d(TAG, "Cargando embed: " + embedUrl);
    }

    /**
     * JS inyectado para extraer la URL del stream de los players más comunes
     * (JWPlayer, Bitmovin, VideoJS, video tag nativo).
     */
    private void injectExtractorScript(WebView view, OnStreamResolvedListener listener) {
        String js =
            "(function() {" +
            "  var found = null;" +

            // 1. JWPlayer
            "  try {" +
            "    if (typeof jwplayer !== 'undefined') {" +
            "      var p = null;" +
            "      var nodes = document.querySelectorAll('[id^=botr_],.jwplayer,[id*=jwplayer]');" +
            "      for (var n = 0; n < nodes.length && !p; n++) {" +
            "        try { if (nodes[n].id) p = jwplayer(nodes[n].id); } catch(e) {}" +
            "      }" +
            "      if (!p) try { p = jwplayer(); } catch(e) {}" +
            "      if (p && p.getPlaylistItem) {" +
            "        var item = p.getPlaylistItem();" +
            "        if (item && item.file) found = item.file;" +
            "        if (!found && item && item.sources) {" +
            "          for (var s = 0; s < item.sources.length; s++) {" +
            "            var file = item.sources[s] && item.sources[s].file;" +
            "            if (file && (file.includes('.m3u8') || file.includes('.mpd') || file.includes('.mp4'))) { found = file; break; }" +
            "          }" +
            "        }" +
            "      }" +
            "    }" +
            "  } catch(e) {}" +

            // 2. Bitmovin
            "  if (!found) try {" +
            "    var players = document.querySelectorAll('[class*=bitmovin]');" +
            "    var src = document.querySelector('video') ? document.querySelector('video').src : null;" +
            "    if (src && (src.includes('.m3u8') || src.includes('.mpd'))) found = src;" +
            "  } catch(e) {}" +

            // 3. Video tag nativo
            "  if (!found) try {" +
            "    var v = document.querySelector('video');" +
            "    if (v) {" +
            "      if (v.src && (v.src.includes('.m3u8') || v.src.includes('.mpd') || v.src.includes('.mp4'))) {" +
            "        found = v.src;" +
            "      } else {" +
            "        var src2 = v.querySelector('source');" +
            "        if (src2 && src2.src) found = src2.src;" +
            "      }" +
            "    }" +
            "  } catch(e) {}" +

            // 4. Buscar en texto de scripts inline
            "  if (!found) try {" +
            "    var scripts = document.querySelectorAll('script:not([src])');" +
            "    for (var i = 0; i < scripts.length; i++) {" +
            "      var txt = scripts[i].textContent;" +
            "      var m = txt.match(/https?:\\/\\/[^\"'\\\\s]+\\.m3u8[^\"'\\\\s]*/i);" +
            "      if (m) { found = m[0]; break; }" +
            "      var m2 = txt.match(/https?:\\/\\/[^\"'\\\\s]+\\.mpd[^\"'\\\\s]*/i);" +
            "      if (m2) { found = m2[0]; break; }" +
            "    }" +
            "  } catch(e) {}" +

            // Reportar resultado al bridge Java
            "  if (found) {" +
            "    window.RexBridge.onStreamFound(found);" +
            "  } else {" +
            "    window.RexBridge.onStreamNotFound('No stream en page load');" +
            "  }" +
            "})();";

        view.evaluateJavascript(js, value -> {
            // El resultado llega por el bridge; no necesitamos el valor de retorno aquí
        });
    }

    private void notifyResolved(String url, OnStreamResolvedListener listener) {
        if (resolved) return;
        resolved = true;
        if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
        Log.d(TAG, "Stream resuelto via WebView: " + url);
        mainHandler.post(() -> listener.onResolved(url));
        destroy();
    }

    public void destroy() {
        if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }

    // ── Interfaz JavaScript → Java ─────────────────────────────────────────────

    private class JsBridge {
        private final OnStreamResolvedListener listener;

        JsBridge(OnStreamResolvedListener listener) {
            this.listener = listener;
        }

        @JavascriptInterface
        public void onStreamFound(String url) {
            Log.d(TAG, "JsBridge.onStreamFound: " + url);
            notifyResolved(url, listener);
        }

        @JavascriptInterface
        public void onStreamNotFound(String reason) {
            Log.d(TAG, "JsBridge.onStreamNotFound: " + reason);
            // No fallar todavía — el player puede cargar el stream después
            // El timeout se encargará si nada llega
        }
    }
}
