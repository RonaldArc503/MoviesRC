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
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.network.AllCalidadScraper;

public class PlayerContenidoActivity extends AppCompatActivity {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_URLS = "urls";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SERIES_TITLE = "series_title";
    private static final String EXTRA_SERIES_POST_ID = "series_post_id";
    private static final String EXTRA_SERIES_POST_TYPE = "series_post_type";
    private static final String EXTRA_EPISODE_ID = "episode_id";
    private static final String EXTRA_SEASON_NUMBER = "season_number";
    private static final String EXTRA_EPISODE_NUMBER = "episode_number";

    private static final long SERVER_TIMEOUT_MS = 12000L;
    private static final long PLAYBACK_POLL_INTERVAL_MS = 1000L;
    private static final double NEXT_EPISODE_BUTTON_REMAINING_SEC = 198d;
    private static final double NEXT_EPISODE_HIDE_HYSTERESIS_SEC = 12d;
    private static final double AUTO_NEXT_REMAINING_SEC = 1.2d;

    private WebView webView;
    private ProgressBar progressBar;
    private Button nextEpisodeButton;
    private TextView nextEpisodeLoaderText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayList<String> serverUrls = new ArrayList<>();
    private int currentServerIndex = 0;
    private boolean pageLoaded = false;
    private Runnable timeoutRunnable;
    private final Runnable autoPlayRunnable = this::requestAutoPlay;

    private String seriesTitle = "";
    private int seriesPostId = -1;
    private String seriesPostType = "";
    private int currentEpisodeId = -1;
    private int currentSeasonNumber = -1;
    private int currentEpisodeNumber = -1;

    private final List<AllCalidadScraper.Season> seasonCache = new ArrayList<>();
    private boolean loadingSeasonData = false;
    private boolean nextButtonVisible = false;
    private boolean isTransitioningToNextEpisode = false;
    private boolean autoNextTriggeredForCurrent = false;
    private boolean resumed = false;

    private NextEpisodeInfo nextEpisodeInfo;

    private final Runnable playbackMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!resumed || webView == null || isFinishing() || isDestroyed()) {
                return;
            }
            if (pageLoaded && !isTransitioningToNextEpisode) {
                inspectPlaybackState();
            }
            handler.postDelayed(this, PLAYBACK_POLL_INTERVAL_MS);
        }
    };

    private static class NextEpisodeInfo {
        final int episodeId;
        final int seasonNumber;
        final int episodeNumber;
        final String title;

        NextEpisodeInfo(int episodeId, int seasonNumber, int episodeNumber, String title) {
            this.episodeId = episodeId;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
            this.title = title;
        }
    }

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

    public static void startEpisode(
            Context context,
            ArrayList<String> urls,
            String title,
            String seriesTitle,
            int seriesPostId,
            String seriesPostType,
            int episodeId,
            int seasonNumber,
            int episodeNumber
    ) {
        Intent intent = new Intent(context, PlayerContenidoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, urls);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_SERIES_TITLE, seriesTitle);
        intent.putExtra(EXTRA_SERIES_POST_ID, seriesPostId);
        intent.putExtra(EXTRA_SERIES_POST_TYPE, seriesPostType);
        intent.putExtra(EXTRA_EPISODE_ID, episodeId);
        intent.putExtra(EXTRA_SEASON_NUMBER, seasonNumber);
        intent.putExtra(EXTRA_EPISODE_NUMBER, episodeNumber);
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
        nextEpisodeButton = findViewById(R.id.btn_next_episode);
        nextEpisodeLoaderText = findViewById(R.id.tv_next_loader);

        ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        seriesTitle = getIntent().getStringExtra(EXTRA_SERIES_TITLE);
        seriesPostId = getIntent().getIntExtra(EXTRA_SERIES_POST_ID, -1);
        seriesPostType = getIntent().getStringExtra(EXTRA_SERIES_POST_TYPE);
        currentEpisodeId = getIntent().getIntExtra(EXTRA_EPISODE_ID, -1);
        currentSeasonNumber = getIntent().getIntExtra(EXTRA_SEASON_NUMBER, -1);
        currentEpisodeNumber = getIntent().getIntExtra(EXTRA_EPISODE_NUMBER, -1);

        if (seriesTitle == null || seriesTitle.trim().isEmpty()) {
            seriesTitle = title;
        }
        if (seriesTitle == null || seriesTitle.trim().isEmpty()) {
            seriesTitle = "Reproduciendo";
        }

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

        setupNextEpisodeButton();
        setupWebView();
        loadCurrentServer();

        if (isSeriesEpisodePlayback()) {
            preloadSeasonData();
        }
    }

    private boolean isSeriesEpisodePlayback() {
        return seriesPostId > 0 && currentEpisodeId > 0;
    }

    private void setupNextEpisodeButton() {
        if (nextEpisodeButton == null) {
            return;
        }
        nextEpisodeButton.setVisibility(View.GONE);
        nextEpisodeButton.setOnClickListener(v -> playNextEpisode(false));
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
                hideNextEpisodeButton(true);
                handler.removeCallbacks(autoPlayRunnable);
                handler.removeCallbacks(playbackMonitorRunnable);
                scheduleServerTimeout();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                cancelServerTimeout();
                progressBar.setVisibility(android.view.View.GONE);
                hideNextEpisodeLoader();
                isTransitioningToNextEpisode = false;
                autoNextTriggeredForCurrent = false;
                triggerAutoPlayAttempts();
                startPlaybackMonitor();
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
        handler.postDelayed(autoPlayRunnable, 4500L);
    }

    private void requestAutoPlay() {
        if (webView == null) {
            return;
        }

        String js = "(function(){"
                + "try {"
                + "function isPlaying(media){return !!media && !media.paused && !media.ended && media.readyState > 2;}"
                + "var media=document.querySelectorAll('video,audio');"
                + "for (var i=0;i<media.length;i++){"
                + "if(isPlaying(media[i])){return;}"
                + "}"
                + "var hasInlineMedia=media.length>0;"
                + "for (var j=0;j<media.length;j++){"
                + "var m=media[j];"
                + "try{"
                + "m.defaultMuted=false;"
                + "m.muted=false;"
                + "m.removeAttribute('muted');"
                + "m.volume=1.0;"
                + "m.autoplay=true;"
                + "if(m.paused){var p=m.play();if(p&&p.catch){p.catch(function(){});}}"
                + "}catch(e){}"
                + "}"
                + "if(!hasInlineMedia && !window.__rexPlayButtonClicked){"
                + "var selectors=['.vjs-big-play-button','.jw-icon-playback','.jw-display-icon-container','.plyr__control--overlaid','.play-button','.btn-play','button[aria-label*=Play]'];"
                + "for (var k=0;k<selectors.length;k++){"
                + "var el=document.querySelector(selectors[k]);"
                + "if(el){try{el.click();window.__rexPlayButtonClicked=true;break;}catch(e){}}"
                + "}"
                + "}"
                + "var ifr=document.querySelectorAll('iframe');"
                + "for (var n=0;n<ifr.length;n++){"
                + "try { ifr[n].contentWindow.postMessage('{\"event\":\"command\",\"func\":\"playVideo\",\"args\":\"\"}','*'); } catch(e){}"
                + "}"
                + "} catch(err) {}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void startPlaybackMonitor() {
        handler.removeCallbacks(playbackMonitorRunnable);
        if (resumed) {
            handler.postDelayed(playbackMonitorRunnable, PLAYBACK_POLL_INTERVAL_MS);
        }
    }

    private void inspectPlaybackState() {
        if (!isSeriesEpisodePlayback() || webView == null) {
            return;
        }

        if (nextEpisodeInfo == null && !loadingSeasonData) {
            preloadSeasonData();
        }
        if (nextEpisodeInfo == null) {
            hideNextEpisodeButton(false);
            return;
        }

        String js = "(function(){"
                + "try {"
                + "var media=document.querySelectorAll('video,audio');"
                + "if(!media||media.length===0){return {hasMedia:false};}"
                + "var selected=null;"
                + "for(var i=0;i<media.length;i++){"
                + "var m=media[i];"
                + "if(!selected){selected=m;}"
                + "if(m&&!m.paused){selected=m;break;}"
                + "}"
                + "if(!selected){return {hasMedia:false};}"
                + "var duration=(isFinite(selected.duration)&&selected.duration>0)?selected.duration:0;"
                + "var current=(isFinite(selected.currentTime)&&selected.currentTime>=0)?selected.currentTime:0;"
                + "var remaining=duration>0?Math.max(0,duration-current):0;"
                + "return {hasMedia:true,duration:duration,currentTime:current,remaining:remaining,ended:!!selected.ended};"
                + "} catch(err) {"
                + "return {hasMedia:false};"
                + "}"
                + "})();";

        webView.evaluateJavascript(js, raw -> handlePlaybackProbe(raw));
    }

    private void handlePlaybackProbe(String raw) {
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw) || nextEpisodeInfo == null) {
            return;
        }

        try {
            JSONObject state = new JSONObject(raw);
            if (!state.optBoolean("hasMedia", false)) {
                hideNextEpisodeButton(false);
                return;
            }

            double duration = state.optDouble("duration", 0d);
            double remaining = state.optDouble("remaining", Double.MAX_VALUE);
            boolean ended = state.optBoolean("ended", false);

            if ((ended || remaining <= AUTO_NEXT_REMAINING_SEC) && !autoNextTriggeredForCurrent) {
                autoNextTriggeredForCurrent = true;
                playNextEpisode(true);
                return;
            }

            double trigger = resolveNextButtonTrigger(duration);
            if (remaining <= trigger && remaining > AUTO_NEXT_REMAINING_SEC) {
                showNextEpisodeButton();
            } else if (remaining > trigger + NEXT_EPISODE_HIDE_HYSTERESIS_SEC) {
                hideNextEpisodeButton(false);
            }
        } catch (JSONException ignored) {
        }
    }

    private double resolveNextButtonTrigger(double durationSec) {
        if (durationSec <= 0) {
            return NEXT_EPISODE_BUTTON_REMAINING_SEC;
        }
        return Math.min(NEXT_EPISODE_BUTTON_REMAINING_SEC, Math.max(20d, durationSec - 5d));
    }

    private void showNextEpisodeButton() {
        if (nextEpisodeButton == null || nextEpisodeInfo == null || nextButtonVisible || isTransitioningToNextEpisode) {
            return;
        }

        nextButtonVisible = true;
        nextEpisodeButton.setVisibility(View.VISIBLE);
        nextEpisodeButton.setAlpha(0f);
        nextEpisodeButton.animate().alpha(1f).setDuration(180L).start();
    }

    private void hideNextEpisodeButton(boolean immediate) {
        if (nextEpisodeButton == null) {
            return;
        }
        if (!nextButtonVisible && nextEpisodeButton.getVisibility() != View.VISIBLE) {
            return;
        }

        nextButtonVisible = false;
        if (immediate) {
            nextEpisodeButton.animate().cancel();
            nextEpisodeButton.setAlpha(0f);
            nextEpisodeButton.setVisibility(View.GONE);
            return;
        }

        nextEpisodeButton.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (!nextButtonVisible) {
                        nextEpisodeButton.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void showNextEpisodeLoader(String label) {
        if (nextEpisodeLoaderText == null) {
            return;
        }
        nextEpisodeLoaderText.setText(label);
        nextEpisodeLoaderText.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideNextEpisodeLoader() {
        if (nextEpisodeLoaderText != null) {
            nextEpisodeLoaderText.setVisibility(View.GONE);
        }
    }

    private void playNextEpisode(boolean automatic) {
        if (nextEpisodeInfo == null) {
            if (!automatic) {
                Toast.makeText(this, "Ya estas en el ultimo episodio disponible", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (isTransitioningToNextEpisode) {
            return;
        }

        isTransitioningToNextEpisode = true;
        hideNextEpisodeButton(true);

        final NextEpisodeInfo target = nextEpisodeInfo;
        String code = formatEpisodeCode(target.seasonNumber, target.episodeNumber);
        showNextEpisodeLoader("Cargando " + code + "...");

        new Thread(() -> {
            try {
                AllCalidadScraper.hit(target.episodeId, "episodes");
                List<String> urls = AllCalidadScraper.getPlayableUrls(AllCalidadScraper.getPlayer(target.episodeId));

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    if (urls == null || urls.isEmpty()) {
                        isTransitioningToNextEpisode = false;
                        hideNextEpisodeLoader();
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "No se pudo cargar el siguiente episodio", Toast.LENGTH_SHORT).show();
                        startPlaybackMonitor();
                        return;
                    }

                    applyEpisodeAndLoad(target, new ArrayList<>(urls));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    isTransitioningToNextEpisode = false;
                    hideNextEpisodeLoader();
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Error cargando siguiente episodio", Toast.LENGTH_SHORT).show();
                    startPlaybackMonitor();
                });
            }
        }).start();
    }

    private void applyEpisodeAndLoad(NextEpisodeInfo episode, ArrayList<String> urls) {
        currentEpisodeId = episode.episodeId;
        currentSeasonNumber = episode.seasonNumber;
        currentEpisodeNumber = episode.episodeNumber;
        nextEpisodeInfo = null;

        serverUrls.clear();
        serverUrls.addAll(urls);
        currentServerIndex = 0;
        pageLoaded = false;
        autoNextTriggeredForCurrent = false;

        String code = formatEpisodeCode(episode.seasonNumber, episode.episodeNumber);
        String title = seriesTitle + " - " + code;
        if (episode.title != null && !episode.title.trim().isEmpty()) {
            title = title + " - " + episode.title;
        }
        setTitle(title);

        refreshNextEpisodeFromCache();
        if (nextEpisodeInfo == null && !loadingSeasonData) {
            preloadSeasonData();
        }

        loadCurrentServer();
    }

    private String formatEpisodeCode(int season, int episode) {
        int safeSeason = season > 0 ? season : 1;
        int safeEpisode = episode > 0 ? episode : 1;
        return String.format(Locale.getDefault(), "T%d:E%d", safeSeason, safeEpisode);
    }

    private void preloadSeasonData() {
        if (!isSeriesEpisodePlayback() || loadingSeasonData) {
            return;
        }

        loadingSeasonData = true;
        new Thread(() -> {
            try {
                String postType = (seriesPostType == null || seriesPostType.trim().isEmpty()) ? "tvshows" : seriesPostType;
                AllCalidadScraper.hit(seriesPostId, postType);
                List<AllCalidadScraper.Season> fetchedSeasons = AllCalidadScraper.getSeasons(seriesPostId);

                runOnUiThread(() -> {
                    loadingSeasonData = false;
                    seasonCache.clear();
                    if (fetchedSeasons != null) {
                        seasonCache.addAll(fetchedSeasons);
                    }
                    refreshNextEpisodeFromCache();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> loadingSeasonData = false);
            }
        }).start();
    }

    private void refreshNextEpisodeFromCache() {
        nextEpisodeInfo = findNextEpisodeFromCache();
        if (nextEpisodeInfo == null) {
            hideNextEpisodeButton(true);
        }
    }

    private NextEpisodeInfo findNextEpisodeFromCache() {
        if (seasonCache.isEmpty()) {
            return null;
        }

        boolean foundCurrent = false;
        for (AllCalidadScraper.Season season : seasonCache) {
            if (season == null || season.episodes == null) {
                continue;
            }
            int seasonNumber = season.seasonNumber > 0 ? season.seasonNumber : 1;
            for (AllCalidadScraper.Episode episode : season.episodes) {
                if (episode == null || episode.id <= 0) {
                    continue;
                }
                int episodeNumber = episode.episodeNumber > 0 ? episode.episodeNumber : 1;

                if (foundCurrent) {
                    return new NextEpisodeInfo(episode.id, seasonNumber, episodeNumber, episode.title);
                }

                if (episode.id == currentEpisodeId ||
                        (currentEpisodeId <= 0 && seasonNumber == currentSeasonNumber && episodeNumber == currentEpisodeNumber)) {
                    currentSeasonNumber = seasonNumber;
                    currentEpisodeNumber = episodeNumber;
                    foundCurrent = true;
                }
            }
        }

        if (currentSeasonNumber <= 0 || currentEpisodeNumber <= 0) {
            return null;
        }

        for (AllCalidadScraper.Season season : seasonCache) {
            if (season == null || season.episodes == null) {
                continue;
            }
            int seasonNumber = season.seasonNumber > 0 ? season.seasonNumber : 1;
            for (AllCalidadScraper.Episode episode : season.episodes) {
                if (episode == null || episode.id <= 0) {
                    continue;
                }
                int episodeNumber = episode.episodeNumber > 0 ? episode.episodeNumber : 1;
                boolean comesAfterCurrent =
                        seasonNumber > currentSeasonNumber
                                || (seasonNumber == currentSeasonNumber && episodeNumber > currentEpisodeNumber);
                if (comesAfterCurrent) {
                    return new NextEpisodeInfo(episode.id, seasonNumber, episodeNumber, episode.title);
                }
            }
        }

        return null;
    }

    private void loadCurrentServer() {
        if (currentServerIndex < 0 || currentServerIndex >= serverUrls.size()) {
            Toast.makeText(this, "No hay más servidores disponibles", Toast.LENGTH_SHORT).show();
            progressBar.setVisibility(android.view.View.GONE);
            if (isTransitioningToNextEpisode) {
                isTransitioningToNextEpisode = false;
                hideNextEpisodeLoader();
            }
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
            if (isTransitioningToNextEpisode) {
                isTransitioningToNextEpisode = false;
                hideNextEpisodeLoader();
            }
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
        handler.removeCallbacks(playbackMonitorRunnable);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(playbackMonitorRunnable);
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (webView != null) {
            webView.onResume();
        }
        enterImmersiveMode();
        if (pageLoaded) {
            startPlaybackMonitor();
        }
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
