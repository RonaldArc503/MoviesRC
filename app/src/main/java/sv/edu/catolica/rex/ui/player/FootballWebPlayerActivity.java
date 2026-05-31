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

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Locale;

public class FootballWebPlayerActivity extends AppCompatActivity {

    private static final String EXTRA_EMBED_URL = "football_embed_url";
    private static final String EXTRA_REFERER = "football_referer";
    private static final String EXTRA_TITLE = "football_title";
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

    public static void start(Context context, String embedUrl, String referer, String title) {
        Intent intent = new Intent(context, FootballWebPlayerActivity.class);
        intent.putExtra(EXTRA_EMBED_URL, embedUrl);
        intent.putExtra(EXTRA_REFERER, referer);
        intent.putExtra(EXTRA_TITLE, title);
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (isBlockedNavigation(url)) {
                    return true;
                }
                return url.startsWith("intent:") || url.startsWith("market:");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
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
        String js =
                "(function(){" +
                "try{window.open=function(u){try{location.href=u;}catch(e){}return null;};}catch(e){}" +
                "var media=null;try{media=document.querySelector('video')||document.querySelector('audio');}catch(e){}" +
                "var started=false;try{started=!!window.__rexFootballPlaybackStarted;}catch(e){}" +
                "if(media){" +
                "  try{media.addEventListener('playing',function(){window.__rexFootballPlaybackStarted=true;},{once:true});}catch(e){}" +
                "  try{if(!media.paused||media.currentTime>0.25){window.__rexFootballPlaybackStarted=true;return;}}catch(e){}" +
                "  if(started){try{var rp=media.play();if(rp&&rp.catch)rp.catch(function(){});}catch(e){}return;}" +
                "}" +
                "if(!document.getElementById('rex-fullscreen-style')){" +
                "var meta=document.querySelector('meta[name=viewport]')||document.createElement('meta');" +
                "meta.name='viewport';meta.content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no';document.head.appendChild(meta);" +
                "var css='html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;}'+" +
                "'body>*:not(script):not(style){max-width:none!important;}'+" +
                "'#player,#myElement,.jwplayer,.jw-wrapper,.jw-aspect,.jw-media,.jw-preview,.jw-display,.jw-controls,.jw-overlays,.jwplayer.jw-flag-fullscreen{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:100vw!important;max-height:100vh!important;margin:0!important;padding:0!important;background:#000!important;}'+" +
                "'.jw-flag-aspect-mode,.jw-flag-aspect-mode .jw-aspect{height:100vh!important;padding:0!important;}'+" +
                "'video,.jw-video,iframe{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important;object-fit:contain!important;background:#000!important;}'+" +
                "'.jwplayer{z-index:2147483647!important;}';" +
                "var s=document.createElement('style');s.id='rex-fullscreen-style';s.innerHTML=css;document.head.appendChild(s);" +
                "}" +
                "function fire(el){if(!el)return false;try{['pointerdown','mousedown','mouseup','click','touchstart','touchend'].forEach(function(t){el.dispatchEvent(new Event(t,{bubbles:true,cancelable:true}));});el.click();return true;}catch(e){try{el.click();return true;}catch(e2){}}return false;}" +
                "function press(sel){var els=document.querySelectorAll(sel);for(var i=0;i<els.length;i++){if(fire(els[i]))return true;}return false;}" +
                "function pressByText(){var words=['play','reproducir','ver','watch'];var els=document.querySelectorAll('button,a,[role=button]');for(var i=0;i<els.length;i++){var t=(els[i].innerText||els[i].textContent||'').toLowerCase();for(var j=0;j<words.length;j++){if(t.indexOf(words[j])!==-1){if(fire(els[i]))return true;}}}return false;}" +
                "function getJw(){try{if(typeof jwplayer!=='function')return null;var nodes=document.querySelectorAll('[id^=botr_],.jwplayer,[id*=jwplayer]');for(var i=0;i<nodes.length;i++){try{var id=nodes[i].id;if(id){var p=jwplayer(id);if(p)return p;}}catch(e){}}try{return jwplayer();}catch(e){return null;}}catch(e){return null;}}" +
                "try{document.querySelectorAll('.close,[aria-label*=close],[class*=close],[id*=close]').forEach(function(e){if((e.innerText||'').length<40){fire(e);}});}catch(e){}" +
                "try{" +
                "var p=getJw();if(p){" +
                "var state='';try{state=p.getState?p.getState():'';}catch(e){}" +
                "if(state==='playing'){window.__rexFootballPlaybackStarted=true;return;}" +
                "try{p.resize('100%','100%');}catch(e){}" +
                "try{p.setMute(false);}catch(e){}" +
                "try{p.setVolume(100);}catch(e){}" +
                "try{p.play();}catch(e){}" +
                "}" +
                "}catch(e){}" +
                "var v=document.querySelector('video');" +
                "if(v){try{v.autoplay=true;v.preload='auto';v.removeAttribute('playsinline');v.setAttribute('webkit-playsinline','false');v.muted=false;v.volume=1;v.controls=true;var r=v.play();if(r&&r.catch){r.catch(function(){});}}catch(e){}}" +
                "try{document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage('play','*');}catch(e){}});}catch(e){}" +
                "if(!window.__rexFootballClickedOnce){" +
                "  window.__rexFootballClickedOnce=true;" +
                "  press('.jw-icon-playback,.jw-display-icon-container,.jw-display-controls,.jwplayer .jw-icon,.jwplayer button,button,[role=button],.bmpui-ui-playbacktogglebutton,.vjs-big-play-button,.plyr__control,.play-button,.play,.btn-play');" +
                "  pressByText();" +
                "}" +
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
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        autoStartGeneration++;
        if (webView != null) {
            stopFootballPlayback();
            webView.onPause();
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
            webView.loadUrl("about:blank");
            root.removeView(webView);
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void stopFootballPlayback() {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();m.src='';m.load();}catch(e){}});}catch(e){}try{if(window.jwplayer){var p=window.jwplayer();if(p&&p.stop)p.stop();}}catch(e){}})();",
                null
        );
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
