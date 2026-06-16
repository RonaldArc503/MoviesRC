package sv.edu.catolica.rex.ui.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class FootballWebPlayerActivity extends AppCompatActivity {

    private static final String TAG = "FootballWebPlayer";

    private static final String EXTRA_EMBED_URL = "football_embed_url";
    private static final String EXTRA_REFERER = "football_referer";
    private static final String EXTRA_TITLE = "football_title";
    private static final String EXTRA_ALLOW_DIRECT_ROUTE = "football_allow_direct_route";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int autoStartGeneration = 0;
    private boolean routedToNativePlayer = false;
    private boolean allowDirectMediaRoute = true;

    public static void start(Context context, String embedUrl, String referer, String title) {
        start(context, embedUrl, referer, title, true);
    }

    public static void start(Context context,
                             String embedUrl,
                             String referer,
                             String title,
                             boolean allowDirectMediaRoute) {
        Intent intent = new Intent(context, FootballWebPlayerActivity.class);
        intent.putExtra(EXTRA_EMBED_URL, embedUrl);
        intent.putExtra(EXTRA_REFERER, referer);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_ALLOW_DIRECT_ROUTE, allowDirectMediaRoute);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        enterImmersiveMode();

        String embedUrl = getIntent().getStringExtra(EXTRA_EMBED_URL);
        if (embedUrl == null || embedUrl.trim().isEmpty()) {
            Toast.makeText(this, "Stream no disponible", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title == null || title.trim().isEmpty() ? "Futbol en vivo" : title);
        allowDirectMediaRoute = getIntent().getBooleanExtra(EXTRA_ALLOW_DIRECT_ROUTE, true);

        setupLayout();
        setupWebView();
        loadEmbed(normalizePlaybackUrl(embedUrl.trim()));
    }

    private void setupLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.BLACK);
        webView.setKeepScreenOn(true);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.requestFocus(View.FOCUS_DOWN);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(progressBar, progressParams);

        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUserAgentString(USER_AGENT);

        // Inyectar bridge de diagnóstico ANTES de cargar cualquier página
        // para que el JS pueda llamarlo desde el primer momento
        Log.d(TAG, "Registrando RexDiagnosticBridge en setupWebView");
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onVideoInfo(String json) {
                Log.d(TAG, "JS diagnostic: " + json);
            }
        }, "RexDiagnosticBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (allowDirectMediaRoute && isDirectMediaUrl(url)) {
                    routeDirectMediaToExo(url);
                    return true;
                }
                if (isBlockedNavigation(url)) {
                    return true;
                }
                return url.startsWith("intent:") || url.startsWith("market:");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (allowDirectMediaRoute && isDirectMediaUrl(url)) {
                    mainHandler.post(() -> routeDirectMediaToExo(url));
                    return new WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            new ByteArrayInputStream(new byte[0])
                    );
                }
                if (isBlockedNavigation(url)) {
                    return new WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            new ByteArrayInputStream(new byte[0])
                    );
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                enterImmersiveMode();
                scheduleAutoStart(view);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                webView.setVisibility(View.GONE);
                enterImmersiveMode();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private void loadEmbed(String embedUrl) {
        progressBar.setVisibility(View.VISIBLE);
        HashMap<String, String> headers = new HashMap<>();
        String referer = getIntent().getStringExtra(EXTRA_REFERER);
        String finalReferer = referer == null || referer.trim().isEmpty()
                ? defaultRefererFor(embedUrl)
                : referer.trim();
        headers.put("Referer", finalReferer);
        if (finalReferer.startsWith("https://cdn.jwplayer.com")) {
            headers.put("Origin", "https://cdn.jwplayer.com");
        }
        webView.loadUrl(embedUrl, headers);
    }

    private String normalizePlaybackUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.trim();
    }

    private String defaultRefererFor(String url) {
        if (url != null && url.toLowerCase(Locale.ROOT).contains("cdn.jwplayer.com")) {
            return "https://cdn.jwplayer.com/";
        }
        return "https://futbol-libre.su/";
    }

    private void autoStartVideo(WebView view) {
        Log.d(TAG, "autoStartVideo: injectando JS de reproducción");
        String js =
                "(function(){" +
                // 1. Bloquear window.open para evitar redirecciones no deseadas
                "try{window.open=function(u){try{location.href=u;}catch(e){}return null;};}catch(e){}" +
                // 2. Marcar si ya se inició reproducción para no repetir
                "var started=false;try{started=!!window.__rexFootballPlaybackStarted;}catch(e){}" +
                // 3. Ajustar viewport para que el contenido ocupe toda la pantalla
                "var meta=document.querySelector('meta[name=viewport]')||document.createElement('meta');" +
                "meta.name='viewport';meta.content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no';document.head.appendChild(meta);" +
                // 4. CSS mínimo: solo fondo negro y overflow oculto. NO forzar estilos en players específicos.
                "if(!document.getElementById('rex-minimal-style')){" +
                "var s=document.createElement('style');s.id='rex-minimal-style';" +
                "s.innerHTML='html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;}';" +
                "document.head.appendChild(s);" +
                "}" +
                // 5. Esperar a que el DOM tenga un elemento video real
                "function tryPlay(){ " +
                "  var v=document.querySelector('video'); " +
                "  if(!v){ " +
                "    if(!window.__rexVideoRetries) window.__rexVideoRetries=0; " +
                "    if(window.__rexVideoRetries<3){ " +
                "      window.__rexVideoRetries++; " +
                "      setTimeout(tryPlay,500); " +
                "    } " +
                "    return; " +
                "  } " +
                "  try{ if(!v.paused && v.currentTime>0.25){ window.__rexFootballPlaybackStarted=true; return; } }catch(e){} " +
                "  var hasVideoTrack=true; " +
                "  try{ hasVideoTrack = v.videoWidth>0 || (v.readyState>=1 && (v.videoWidth>0 || v.getVideoPlaybackQuality)); }catch(e){} " +
                "  window.__rexVideoHasDimensions=hasVideoTrack; " +
                "  try{ " +
                "    v.autoplay=true; " +
                "    v.preload='auto'; " +
                "    v.setAttribute('playsinline',''); " +
                "    v.setAttribute('webkit-playsinline',''); " +
                "    v.muted=false; " +
                "    v.volume=1; " +
                "    v.controls=true; " +
                "    var r=v.play(); " +
                "    if(r&&r.catch) r.catch(function(){}); " +
                "  }catch(e){} " +
                "  if(!window.__rexFootballClickedOnce){ " +
                "    window.__rexFootballClickedOnce=true; " +
                "    ['button.play,.jw-icon-playback,.jw-display-icon-container,.vjs-big-play-button,.plyr__control,.play-button'].forEach(function(sel){ " +
                "      var el=document.querySelector(sel); " +
                "      if(el){ try{ el.click(); }catch(e){} } " +
                "    }); " +
                "  } " +
                "} " +
                "try{ " +
                "  if(typeof jwplayer==='function'){ " +
                "    var p=null; " +
                "    var nodes=document.querySelectorAll('[id^=botr_],.jwplayer,[id*=jwplayer]'); " +
                "    for(var n=0;n<nodes.length&&!p;n++){ try{ if(nodes[n].id) p=jwplayer(nodes[n].id); }catch(e){} } " +
                "    if(!p) try{ p=jwplayer(); }catch(e){} " +
                "    if(p){ " +
                "      try{ p.setMute(false); }catch(e){} " +
                "      try{ p.setVolume(100); }catch(e){} " +
                "      try{ p.play(); }catch(e){} " +
                "    } " +
                "  } " +
                "}catch(e){} " +
                "tryPlay(); " +
                "try{ " +
                "  document.addEventListener('playing',function(e){ " +
                "    if(e.target&&(e.target.tagName==='VIDEO'||e.target.tagName==='AUDIO')){ " +
                "      window.__rexFootballPlaybackStarted=true; " +
                "    } " +
                "  },{once:true,capture:true}); " +
                "}catch(e){} " +
                // 9. Reportar diagnóstico de vuelta a Java
                "setTimeout(function(){ " +
                "  var v=document.querySelector('video'); " +
                "  var info={found:!!v,playing:false,hasVideoTrack:false,width:0,height:0,started:!!window.__rexFootballPlaybackStarted}; " +
                "  if(v){ try{ info.playing=!v.paused; }catch(e){} try{ info.hasVideoTrack=v.videoWidth>0; }catch(e){} try{ info.width=v.videoWidth; info.height=v.videoHeight; }catch(e){} } " +
                "  window.RexDiagnosticBridge.onVideoInfo(JSON.stringify(info)); " +
                "},2000); " +
                "})();";

        view.evaluateJavascript(js, null);
    }

    private void scheduleAutoStart(WebView view) {
        final int generation = ++autoStartGeneration;
        long[] delays = new long[] {0L, 700L, 1500L, 3000L, 5000L, 8000L};
        for (long delay : delays) {
            mainHandler.postDelayed(() -> {
                if (webView != null && generation == autoStartGeneration) {
                    enterImmersiveMode();
                    autoStartVideo(view);
                }
            }, delay);
        }
    }

    private boolean isBlockedNavigation(String url) {
        if (url == null) {
            return false;
        }
        String lc = url.toLowerCase(Locale.ROOT);
        return lc.startsWith("intent:")
                || lc.startsWith("market:")
                || lc.contains("doubleclick")
                || lc.contains("googlesyndication")
                || lc.contains("google-analytics")
                || lc.contains("popads")
                || lc.contains("adsterra");
    }

    private boolean isDirectMediaUrl(String url) {
        if (url == null) {
            return false;
        }
        String lc = url.toLowerCase(Locale.ROOT);
        int queryIndex = lc.indexOf('?');
        String path = queryIndex >= 0 ? lc.substring(0, queryIndex) : lc;

        // Streams con DRM (CENC/Widevine) no funcionan en ExoPlayer sin licencia ->
        // deben reproducirse en el WebView con JWPlayer que maneja la autenticación
        if (lc.contains("/dash/enc/")
                || lc.contains("/cenc")
                || lc.contains("cenc_")
                || lc.contains("cenc.mpd")) {
            Log.d(TAG, "Stream con DRM/CENC, manteniendo en WebView: " + url);
            return false;
        }

        // broadpeak.io requiere tokens de autenticación que solo JWPlayer maneja correctamente
        if (lc.contains("broadpeak.io") || lc.contains("broadpeak")) {
            Log.d(TAG, "Stream de broadpeak.io (requiere auth JWPlayer), manteniendo en WebView: " + url);
            return false;
        }

        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4");
    }

    private void routeDirectMediaToExo(String url) {
        if (routedToNativePlayer || url == null || url.trim().isEmpty()) {
            return;
        }
        Log.d(TAG, "Redirigiendo stream directo a ExoPlayer: " + url);
        routedToNativePlayer = true;
        ArrayList<String> urls = new ArrayList<>();
        urls.add(url.trim());
        String referer = getIntent().getStringExtra(EXTRA_REFERER);
        String embedUrl = getIntent().getStringExtra(EXTRA_EMBED_URL);
        PlayerExoActivity.start(
                this,
                urls,
                getTitle() == null ? "Futbol en vivo" : getTitle().toString(),
                true,
                referer,
                null,
                embedUrl,
                referer
        );
        finish();
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        enterImmersiveMode();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (webView != null) {
            webView.resumeTimers();
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        autoStartGeneration++;
        if (webView != null) {
            stopFootballPlayback();
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        autoStartGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        hideCustomView();
        if (webView != null) {
            stopFootballPlayback();
            destroyWebView();
        }
        super.onDestroy();
    }

    private void stopFootballPlayback() {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){"
                        + "try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();m.removeAttribute('src');m.src='';m.load();m.remove();}catch(e){}});}catch(e){}"
                        + "try{if(window.jwplayer){var nodes=document.querySelectorAll('[id^=botr_],.jwplayer,[id*=jwplayer]');for(var i=0;i<nodes.length;i++){try{var id=nodes[i].id;if(id){var p=window.jwplayer(id);if(p){if(p.pause)p.pause(true);if(p.stop)p.stop();if(p.remove)p.remove();}}}catch(e){}}try{var p2=window.jwplayer();if(p2){if(p2.pause)p2.pause(true);if(p2.stop)p2.stop();if(p2.remove)p2.remove();}}catch(e){}}}catch(e){}"
                        + "try{document.querySelectorAll('iframe').forEach(function(f){try{f.src='about:blank';f.remove();}catch(e){}});}catch(e){}"
                        + "})();",
                null
        );
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        try {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null);
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.clearHistory();
            webView.clearCache(false);
            webView.onPause();
            webView.pauseTimers();
            if (root != null) {
                root.removeView(webView);
            }
            webView.removeAllViews();
            webView.destroy();
        } catch (Exception ignored) {
        } finally {
            webView = null;
        }
    }

    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }
}
