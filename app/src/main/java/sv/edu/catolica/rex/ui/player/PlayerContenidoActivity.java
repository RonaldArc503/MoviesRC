package sv.edu.catolica.rex.ui.player;

import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.ArrayList;
import sv.edu.catolica.rex.R;

public class PlayerContenidoActivity extends AppCompatActivity {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_URLS = "urls";
    private static final String EXTRA_TITLE = "title";
    private static final long SERVER_TIMEOUT_MS = 12000L;

    private WebView webView;
    private ProgressBar progressBar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayList<String> serverUrls = new ArrayList<>();
    private int currentServerIndex = 0;
    private boolean pageLoaded = false;
    private Runnable timeoutRunnable;
    private final Runnable autoPlayRunnable = this::requestAutoPlay;

    public static void start(Context context, String videoUrl, String title) {
        Intent intent = new Intent(context, PlayerContenidoActivity.class);
        intent.putExtra(EXTRA_URL, videoUrl);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    public static void start(Context context, ArrayList<String> urls, String title) {
        Intent intent = new Intent(context, PlayerContenidoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, urls);
        intent.putExtra(EXTRA_TITLE, title);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        enterImmersiveMode();
        setContentView(R.layout.activity_player_contenido);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) setTitle(title);
        else setTitle("Reproduciendo");

        if (urls != null && !urls.isEmpty()) {
            serverUrls.addAll(urls);
        }
        if ((serverUrls.isEmpty()) && (url != null && !url.isEmpty())) {
            serverUrls.add(url);
        }

        if (serverUrls.isEmpty()) {
            finish();
            return;
        }

        setupWebView();
        loadCurrentServer();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoaded = false;
                progressBar.setVisibility(android.view.View.VISIBLE);
                handler.removeCallbacks(autoPlayRunnable);
                scheduleServerTimeout();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                cancelServerTimeout();
                progressBar.setVisibility(android.view.View.GONE);
                triggerAutoPlayAttempts();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    tryNextServer();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    tryNextServer();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                String current = currentServerIndex < serverUrls.size() ? serverUrls.get(currentServerIndex) : "";
                if (failingUrl != null && failingUrl.equals(current)) {
                    tryNextServer();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.setVisibility(android.view.View.VISIBLE);
    }

    private void triggerAutoPlayAttempts() {
        handler.removeCallbacks(autoPlayRunnable);
        handler.postDelayed(autoPlayRunnable, 150L);
        handler.postDelayed(autoPlayRunnable, 1200L);
        handler.postDelayed(autoPlayRunnable, 2800L);
    }

    private void requestAutoPlay() {
        if (webView == null) {
            return;
        }

        String js = "(function(){"
                + "try {"
                + "var media=document.querySelectorAll('video,audio');"
                + "for (var i=0;i<media.length;i++){"
                + "var m=media[i];"
                + "m.muted=true;"
                + "m.autoplay=true;"
                + "var p=m.play();"
                + "if(p&&p.catch){p.catch(function(){});}"
                + "}"
                + "var selectors=['.vjs-big-play-button','.jw-icon-playback','.jw-display-icon-container','.plyr__control--overlaid','.play-button','.btn-play','button[aria-label*=Play]'];"
                + "for (var j=0;j<selectors.length;j++){"
                + "var el=document.querySelector(selectors[j]);"
                + "if(el){el.click();}"
                + "}"
                + "var ifr=document.querySelectorAll('iframe');"
                + "for (var k=0;k<ifr.length;k++){"
                + "try { ifr[k].contentWindow.postMessage('{\"event\":\"command\",\"func\":\"playVideo\",\"args\":\"\"}','*'); } catch(e){}"
                + "}"
                + "} catch(err) {}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void loadCurrentServer() {
        if (currentServerIndex < 0 || currentServerIndex >= serverUrls.size()) {
            Toast.makeText(this, "No hay más servidores disponibles", Toast.LENGTH_SHORT).show();
            progressBar.setVisibility(android.view.View.GONE);
            return;
        }
        String url = serverUrls.get(currentServerIndex);
        webView.loadUrl(url);
    }

    private void scheduleServerTimeout() {
        cancelServerTimeout();
        timeoutRunnable = () -> {
            if (!pageLoaded) {
                tryNextServer();
            }
        };
        handler.postDelayed(timeoutRunnable, SERVER_TIMEOUT_MS);
    }

    private void cancelServerTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void tryNextServer() {
        cancelServerTimeout();
        if (currentServerIndex + 1 < serverUrls.size()) {
            currentServerIndex++;
            Toast.makeText(this, "Cambiando al servidor " + (currentServerIndex + 1), Toast.LENGTH_SHORT).show();
            loadCurrentServer();
        } else {
            progressBar.setVisibility(android.view.View.GONE);
            Toast.makeText(this, "No se pudo reproducir con los servidores disponibles", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        cancelServerTimeout();
        handler.removeCallbacks(autoPlayRunnable);
        webView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
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
