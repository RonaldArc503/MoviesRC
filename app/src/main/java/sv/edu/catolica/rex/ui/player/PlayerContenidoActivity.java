package sv.edu.catolica.rex.ui.player;

import android.annotation.SuppressLint;
import android.app.UiModeManager;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;

public class PlayerContenidoActivity extends AppCompatActivity {

    private static final String TAG = "PlayerContenidoActivity";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_URLS = "urls";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SERIES_TITLE = "series_title";
    private static final String EXTRA_SERIES_POST_ID = "series_post_id";
    private static final String EXTRA_SERIES_POST_TYPE = "series_post_type";
    private static final String EXTRA_EPISODE_ID = "episode_id";
    private static final String EXTRA_SEASON_NUMBER = "season_number";
    private static final String EXTRA_EPISODE_NUMBER = "episode_number";
    public static final String RESULT_EPISODE_ID = "result_episode_id";
    public static final String RESULT_SEASON_NUMBER = "result_season_number";
    public static final String RESULT_EPISODE_NUMBER = "result_episode_number";

    private static final long SERVER_TIMEOUT_MS = 12000L;
    private static final int MAX_RETRIES_PER_SERVER = 1;
    private static final long RETRY_DELAY_MS = 700L;
    private static final long PLAYBACK_POLL_INTERVAL_MS = 2000L;
    private static final long PLAYBACK_POLL_INTERVAL_SLOW_MS = 5000L;
    private static final double NEXT_EPISODE_BUTTON_REMAINING_SEC = 180d;
    private static final boolean SHOW_NEXT_EPISODE_BUTTON = true;
    private static final double NEXT_EPISODE_HIDE_HYSTERESIS_SEC = 12d;
    private static final double AUTO_NEXT_REMAINING_SEC = 1.2d;
    private static final String NEXT_EPISODE_LABEL_BASE = "Siguiente episodio";
    /** Tiempo de inactividad en TV antes de ocultar los controles del player (ms) */
    private static final long TV_CONTROLS_HIDE_DELAY_MS = 3500L;

    private static final String EXTRA_IMAGE_URL = "image_url";
    private static final String EXTRA_RESUME_POSITION_MS = "resume_position_ms";
    private static final long PLAYBACK_SAVE_INTERVAL_MS = 10000L;
    private static final long SEEK_RETRY_DELAY_MS = 500L;
    private static final int SEEK_MAX_RETRIES = 20;

    private WebView webView;
    private ProgressBar progressBar;
    private Button nextEpisodeButton;
    private TextView nextEpisodeLoaderText;
    private Button skipIntroButton;
    private boolean skipIntroVisible = false;
    private boolean introSkipped = false;
    private double currentIntroEndSec = -1;
    private double lastCurrentTimeSec = 0d;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayList<String> serverUrls = new ArrayList<>();
    private int currentServerIndex = 0;
    private int currentServerRetry = 0;
    private boolean pageLoaded = false;
    private boolean failoverInProgress = false;
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
    private boolean seasonPreloadAttempted = false;
    private boolean nextButtonVisible = false;
    private boolean isTransitioningToNextEpisode = false;
    private boolean autoNextTriggeredForCurrent = false;
    private boolean nextEpisodeQueuedInStore = false;
    private boolean resumed = false;
    private boolean isTvDevice = false;
    private boolean adOverlaysCleaned = false;
    private String lastLoadedEmbedUrl = "";
    private boolean exoSwitchInProgress = false;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private final Runnable tvHideControlsRunnable = this::injectTvControlsHide;

    private NextEpisodeInfo nextEpisodeInfo;

    private long resumePositionMs = 0;
    private double lastSavedPositionSec = -1;
    private long lastRemoteSaveElapsedMs = 0;
    private boolean positionRestored = false;
    private JavaScriptReplyProxy doramaFrame;
    private long doramaFrameSeenAtMs = 0;
    private long contentDurationMs = 0;
    private String contentId = "";
    private String imageUrl = "";
    private String seriesSource = "";
    private String seriesDetailUrl = "";
    private boolean disableExoSwitch = false;
    private volatile boolean isExiting = false;

    /** Guarda el progreso en un hilo de fondo para no saturar el main thread
     *  con Gson/SharedPreferences mientras el video se está reproduciendo. */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();

    // Controls overlay
    private View controlOverlay;
    private View centerControls;
    private View topBar;
    private View bottomBar;
    private ImageButton btnBack;
    private ImageButton btnPlayCenter;
    private ImageButton btnSkipBack;
    private ImageButton btnSkipForward;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvDuration;
    private TextView tvTitle;
    private boolean controlsVisible = false;
    private boolean seekBarDragging = false;
    private float seekDownX = -1f;
    private long lastVideoDurationMs = 0;
    private long lastVideoTapUpMs = 0L;
    private float lastVideoTapX = -1f;
    private PlayerDoubleTapHelper doubleTapHelper;
    private volatile boolean isVideoPlaying = false;
    private static final long PLAYBACK_WATCHDOG_MS = 20000L;
    private Runnable playbackWatchdogRunnable;

    private final Runnable playbackMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!resumed || webView == null || isFinishing() || isDestroyed()) {
                return;
            }
            if (pageLoaded && !isTransitioningToNextEpisode) {
                inspectPlaybackState();
            }
            handler.postDelayed(this, currentPollInterval());
        }
    };

    /** Intervalo adaptativo: cuando el video se reproduce con controles ocultos
     *  se espacia el polling para reducir la presión de JS sobre el renderer. */
    private long currentPollInterval() {
        if (skipIntroVisible) {
            return 1000L;
        }
        if (lastCurrentTimeSec < 240d) {
            return 1500L;
        }
        if (pageLoaded && !controlsVisible && lastVideoDurationMs > 0) {
            return PLAYBACK_POLL_INTERVAL_SLOW_MS;
        }
        return PLAYBACK_POLL_INTERVAL_MS;
    }

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

    public static Intent createIntent(Context context, String videoUrl, String title) {
        Intent intent = new Intent(context, PlayerContenidoActivity.class);
        intent.putExtra(EXTRA_URL, videoUrl);
        intent.putExtra(EXTRA_TITLE, title);
        return intent;
    }

    public static Intent createIntent(Context context, ArrayList<String> urls, String title) {
        Intent intent = new Intent(context, PlayerContenidoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, urls);
        intent.putExtra(EXTRA_TITLE, title);
        return intent;
    }

    public static void start(Context context, String videoUrl, String title) {
        context.startActivity(createIntent(context, videoUrl, title));
    }

    public static void start(Context context, ArrayList<String> urls, String title) {
        context.startActivity(createIntent(context, urls, title));
    }

    public static Intent createEpisodeIntent(
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
        return intent;
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
        context.startActivity(createEpisodeIntent(
                context, urls, title, seriesTitle, seriesPostId, seriesPostType,
                episodeId, seasonNumber, episodeNumber));
    }

    /** Intent completo con metadatos + imagen + posición de reanudación.
     *  Se usa para re-lanzar el player WebView cuando ExoPlayer falla. */
    public static Intent createFullIntent(
            Context context,
            ArrayList<String> urls,
            String title,
            String seriesTitle,
            int seriesPostId,
            String seriesPostType,
            int episodeId,
            int seasonNumber,
            int episodeNumber,
            String imageUrl,
            long resumePositionMs
    ) {
        Intent intent = createEpisodeIntent(context, urls, title, seriesTitle, seriesPostId,
                seriesPostType, episodeId, seasonNumber, episodeNumber);
        if (imageUrl != null && !imageUrl.isEmpty()) {
            intent.putExtra(EXTRA_IMAGE_URL, imageUrl);
        }
        if (resumePositionMs > 0) {
            intent.putExtra(EXTRA_RESUME_POSITION_MS, resumePositionMs);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        enterImmersiveMode();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player_contenido);
        isTvDevice = isTelevision();

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);
        nextEpisodeButton = findViewById(R.id.btn_next_episode);
        nextEpisodeLoaderText = findViewById(R.id.tv_next_loader);
        skipIntroButton = findViewById(R.id.btn_skip_intro);
        initControls();

        ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        seriesTitle = getIntent().getStringExtra(EXTRA_SERIES_TITLE);
        seriesPostId = getIntent().getIntExtra(EXTRA_SERIES_POST_ID, -1);
        seriesPostType = getIntent().getStringExtra(EXTRA_SERIES_POST_TYPE);
        seriesSource = getIntent().getStringExtra("series_source");
        seriesDetailUrl = getIntent().getStringExtra("series_detail_url");
        currentEpisodeId = getIntent().getIntExtra(EXTRA_EPISODE_ID, -1);
        currentSeasonNumber = getIntent().getIntExtra(EXTRA_SEASON_NUMBER, -1);
        currentEpisodeNumber = getIntent().getIntExtra(EXTRA_EPISODE_NUMBER, -1);
        resumePositionMs = getIntent().getLongExtra(EXTRA_RESUME_POSITION_MS, 0L);
        imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);

        if (seriesTitle == null || seriesTitle.trim().isEmpty()) {
            seriesTitle = title;
        }
        if (seriesTitle == null || seriesTitle.trim().isEmpty()) {
            seriesTitle = "Reproduciendo";
        }

        contentId = buildContentId();

        if (title != null) setTitle(title);
        else setTitle("Reproduciendo");

        if (urls != null && !urls.isEmpty()) {
            serverUrls.addAll(urls);
        }
        if ((serverUrls.isEmpty()) && (url != null && !url.isEmpty())) {
            serverUrls.add(url);
        }
        serverUrls = filterDeprecatedServers(serverUrls);

        setupNextEpisodeButton();
        setupSkipIntroButton();
        if (isSeriesEpisodePlayback()) {
            refreshNextEpisodeFromCache();
        }
        setupWebView();

        if (serverUrls.isEmpty()) {
            resolveInitialPlaybackUrls();
            return;
        }

        startPlayingLoadedUrls();
    }

    private void startPlayingLoadedUrls() {
        disableExoSwitch = getIntent().getBooleanExtra("disable_exo_switch", false);

        if (!disableExoSwitch) {
            ArrayList<String> directStreamUrls = extractDirectStreamUrls(serverUrls);
            if (!directStreamUrls.isEmpty()) {
                String embedReferer = isTuDoramaSource() ? "https://tudorama.com/"
                        : originOf(!serverUrls.isEmpty() ? serverUrls.get(0) : null);
                Intent exoIntent = new Intent(this, PlayerExoActivity.class);
                exoIntent.putStringArrayListExtra(EXTRA_URLS, directStreamUrls);
                exoIntent.putStringArrayListExtra("exo_urls", directStreamUrls);
                String playTitle = getTitle() != null ? getTitle().toString() : seriesTitle;
                exoIntent.putExtra(EXTRA_TITLE, playTitle);
                exoIntent.putExtra("exo_title", playTitle);
                exoIntent.putExtra("content_id", contentId);
                exoIntent.putExtra("exo_content_id", contentId);
                exoIntent.putExtra("series_title", seriesTitle);
                exoIntent.putExtra("exo_series_title", seriesTitle);
                exoIntent.putExtra("image_url", imageUrl);
                exoIntent.putExtra("exo_image_url", imageUrl);
                exoIntent.putExtra("post_id", seriesPostId);
                exoIntent.putExtra("exo_post_id", seriesPostId);
                exoIntent.putExtra("media_type", seriesPostType);
                exoIntent.putExtra("exo_media_type", seriesPostType);
                exoIntent.putExtra("episode_id", currentEpisodeId);
                exoIntent.putExtra("exo_episode_id", currentEpisodeId);
                exoIntent.putExtra("season_number", currentSeasonNumber);
                exoIntent.putExtra("exo_season_number", currentSeasonNumber);
                exoIntent.putExtra("episode_number", currentEpisodeNumber);
                exoIntent.putExtra("exo_episode_number", currentEpisodeNumber);
                exoIntent.putExtra("resume_position_ms", resumePositionMs);
                exoIntent.putExtra("exo_resume_position_ms", resumePositionMs);
                exoIntent.putExtra("series_source", seriesSource);
                exoIntent.putExtra("exo_series_source", seriesSource);
                exoIntent.putExtra("series_detail_url", seriesDetailUrl);
                exoIntent.putExtra("exo_series_detail_url", seriesDetailUrl);
                if (isTuDoramaSource()) {
                    exoIntent.putStringArrayListExtra("exo_fallback_embed_urls",
                            extractEmbedUrls(serverUrls));
                }
                if (embedReferer != null) {
                    exoIntent.putExtra("referer", embedReferer);
                    exoIntent.putExtra("origin", embedReferer);
                    exoIntent.putExtra("exo_referer", embedReferer);
                    exoIntent.putExtra("exo_origin", embedReferer);
                }
                startActivity(exoIntent);
                setPlaybackResult();
                finish();
                return;
            }
        }

        loadCurrentServer();

        if (isSeriesEpisodePlayback()) {
            preloadSeasonData();
        }
    }

    private void resolveInitialPlaybackUrls() {
        showPlaybackStatus("Conectando con servidores...");
        new Thread(() -> {
            try {
                List<String> urls = new ArrayList<>();
                boolean isSeries = isSeriesEpisodePlayback();

                // 1) Si es Canela TV / Novelas
                boolean isNovelas = (seriesSource != null && ("novelas".equalsIgnoreCase(seriesSource) || "canela".equalsIgnoreCase(seriesSource)))
                        || (seriesDetailUrl != null && seriesDetailUrl.contains("canela.tv"));
                if (isNovelas) {
                    try {
                        sv.edu.catolica.rex.network.smart.CanelaScraper canelaScraper =
                                new sv.edu.catolica.rex.network.smart.CanelaScraper();
                        sv.edu.catolica.rex.models.MediaItem item = new sv.edu.catolica.rex.models.MediaItem(
                                seriesTitle, "", imageUrl != null ? imageUrl : "",
                                seriesDetailUrl != null ? seriesDetailUrl : "", seriesPostId);
                        item.setMediaType(seriesPostType != null ? seriesPostType : (isSeries ? "tvshows" : "movies"));
                        item.setFuente(seriesSource != null ? seriesSource : "novelas");
                        sv.edu.catolica.rex.network.smart.SmartPlaybackResult canelaResult = isSeries
                                ? canelaScraper.resolveEpisode(item, currentSeasonNumber, currentEpisodeNumber, currentEpisodeId)
                                : canelaScraper.resolveMovie(item);
                        if (canelaResult != null && canelaResult.hasPlayableUrls()) {
                            urls = canelaResult.toPlayableUrls();
                        } else if (isSeries) {
                            // Si se catalogó como serie pero no tiene episodios, intentar como película
                            canelaResult = canelaScraper.resolveMovie(item);
                            if (canelaResult != null && canelaResult.hasPlayableUrls()) {
                                urls = canelaResult.toPlayableUrls();
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // 2) Si es AllCalidad
                boolean isAllCalidad = !isNovelas && seriesPostId > 0
                        && (seriesSource == null || seriesSource.isEmpty() || "allcalidad".equalsIgnoreCase(seriesSource));
                if (isAllCalidad && currentEpisodeId > 0) {
                    try {
                        AllCalidadScraper.hit(currentEpisodeId, "episodes");
                        urls = AllCalidadScraper.getPlayableUrls(AllCalidadScraper.getPlayer(currentEpisodeId));
                    } catch (Exception ignored) {}
                } else if (isAllCalidad && !isSeries && seriesPostId > 0) {
                    try {
                        AllCalidadScraper.hit(seriesPostId, seriesPostType != null ? seriesPostType : "movies");
                        urls = AllCalidadScraper.getPlayableUrls(AllCalidadScraper.getPlayer(seriesPostId));
                    } catch (Exception ignored) {}
                }

                // 2) SmartScraperEngine (Caché instantáneo o multi-fuente)
                if (urls == null || urls.isEmpty()) {
                    try {
                        sv.edu.catolica.rex.network.smart.SmartScraperEngine engine =
                                new sv.edu.catolica.rex.network.smart.SmartScraperEngine();
                        String playTitle = seriesTitle != null && !seriesTitle.isEmpty()
                                ? seriesTitle : (getTitle() != null ? getTitle().toString() : "");
                        sv.edu.catolica.rex.models.MediaItem item = new sv.edu.catolica.rex.models.MediaItem(
                                playTitle, "", imageUrl != null ? imageUrl : "",
                                seriesDetailUrl != null ? seriesDetailUrl : "", seriesPostId);
                        item.setMediaType(seriesPostType != null ? seriesPostType : (isSeries ? "tvshows" : "movies"));
                        item.setFuente(seriesSource);

                        urls = isSeries
                                ? engine.resolveEpisodeUrls(PlayerContenidoActivity.this, item, currentSeasonNumber, currentEpisodeNumber, currentEpisodeId)
                                : engine.resolveMovieUrls(PlayerContenidoActivity.this, item);
                    } catch (Exception ignored) {}
                }

                final List<String> resolvedUrls = urls;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    hidePlaybackStatus();
                    if (resolvedUrls == null || resolvedUrls.isEmpty()) {
                        Toast.makeText(PlayerContenidoActivity.this, "No hay servidores disponibles para este contenido", Toast.LENGTH_LONG).show();
                        setPlaybackResult();
                        finish();
                        return;
                    }

                    serverUrls.clear();
                    serverUrls.addAll(resolvedUrls);
                    serverUrls = filterDeprecatedServers(serverUrls);

                    if (serverUrls.isEmpty()) {
                        Toast.makeText(PlayerContenidoActivity.this, "No hay servidores disponibles", Toast.LENGTH_SHORT).show();
                        setPlaybackResult();
                        finish();
                        return;
                    }

                    startPlayingLoadedUrls();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    hidePlaybackStatus();
                    Toast.makeText(PlayerContenidoActivity.this, "Error al conectar con los servidores", Toast.LENGTH_SHORT).show();
                    setPlaybackResult();
                    finish();
                });
            }
        }).start();
    }

    private boolean isSeriesEpisodePlayback() {
        return (currentSeasonNumber > 0 && currentEpisodeNumber > 0)
                || currentEpisodeId > 0
                || (seriesPostId > 0 && isSeriesType(seriesPostType));
    }

    private boolean isSeriesType(String type) {
        if (type == null) return false;
        String t = type.trim().toLowerCase(Locale.ROOT);
        return "tvshows".equals(t) || "animes".equals(t) || "series".equals(t) || "tv".equals(t);
    }

    private void setupNextEpisodeButton() {
        if (nextEpisodeButton == null) {
            return;
        }
        nextEpisodeButton.setText(NEXT_EPISODE_LABEL_BASE);
        nextEpisodeButton.setVisibility(View.GONE);
        nextEpisodeButton.setOnClickListener(v -> playNextEpisode(false));
    }

    private void setupSkipIntroButton() {
        if (skipIntroButton == null) {
            return;
        }
        skipIntroButton.setText("Saltar intro");
        skipIntroButton.setVisibility(View.GONE);
        skipIntroButton.bringToFront();
        skipIntroButton.setOnClickListener(v -> skipIntro());
    }

    private void skipIntro() {
        if (webView == null || isFinishing() || isDestroyed()) {
            return;
        }
        introSkipped = true;
        final double targetSec = currentIntroEndSec;
        hideSkipIntroButton(true);

        String skipJs = "(function(){"
                + "try{"
                + "  var clicked=false;"
                + "  var btn=document.querySelector('.skip-option.intro, [class*=\"skip-option\"][class*=\"intro\"], .player-skip-options .intro, [class*=\"skipintro\"], [class*=\"skip-intro\"]');"
                + "  if(btn){"
                + "    try{btn.click();clicked=true;}catch(e){}"
                + "    try{btn.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));clicked=true;}catch(e2){}"
                + "  }"
                + "  if(!clicked){"
                + "    try{"
                + "      var ifrs=document.querySelectorAll('iframe');"
                + "      for(var ifi=0;ifi<ifrs.length;ifi++){"
                + "        var idoc=ifrs[ifi].contentDocument||(ifrs[ifi].contentWindow&&ifrs[ifi].contentWindow.document);"
                + "        if(idoc){"
                + "          var ibtn=idoc.querySelector('.skip-option.intro, [class*=\"skip-option\"][class*=\"intro\"], .player-skip-options .intro, [class*=\"skipintro\"], [class*=\"skip-intro\"]');"
                + "          if(ibtn){"
                + "            try{ibtn.click();clicked=true;}catch(e){}"
                + "            try{ibtn.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));clicked=true;}catch(e2){}"
                + "            if(clicked)break;"
                + "          }"
                + "        }"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "  var t=" + targetSec + ";"
                + "  if(t>0){"
                + "    var v=document.querySelector('video');"
                + "    if(v){v.currentTime=t;clicked=true;}"
                + "    if(window.jwplayer){try{window.jwplayer().seek(t);clicked=true;}catch(e){}}"
                + "  } else {"
                + "    setTimeout(function(){"
                + "      try{"
                + "        var b2=document.querySelector('.skip-option.intro, [class*=\"skip-option\"][class*=\"intro\"], .player-skip-options .intro');"
                + "        if(b2){b2.click();b2.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}"
                + "      }catch(e){}"
                + "    },250);"
                + "  }"
                + "  return clicked;"
                + "}catch(e){return false;}"
                + "})();";

        webView.evaluateJavascript(skipJs, val -> {
            try {
                Toast.makeText(PlayerContenidoActivity.this, "Intro saltada", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    private void showSkipIntroButton(double introEnd) {
        if (skipIntroButton == null || isFinishing() || isDestroyed() || nextButtonVisible) {
            return;
        }
        if (introEnd > 0) {
            currentIntroEndSec = introEnd;
        }
        if (skipIntroVisible && skipIntroButton.getVisibility() == View.VISIBLE) {
            return;
        }

        skipIntroVisible = true;
        skipIntroButton.bringToFront();
        skipIntroButton.setVisibility(View.VISIBLE);
        skipIntroButton.setAlpha(0f);
        skipIntroButton.animate().alpha(1f).setDuration(180L).start();
        if (isTvDevice) {
            skipIntroButton.requestFocus();
        }
    }

    private void hideSkipIntroButton(boolean immediate) {
        if (skipIntroButton == null) {
            return;
        }
        if (!skipIntroVisible && skipIntroButton.getVisibility() != View.VISIBLE) {
            return;
        }

        skipIntroVisible = false;
        if (immediate) {
            skipIntroButton.animate().cancel();
            skipIntroButton.setAlpha(0f);
            skipIntroButton.setVisibility(View.GONE);
            return;
        }

        skipIntroButton.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (!skipIntroVisible) {
                        skipIntroButton.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void preconfigureCookiesForCanela() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && webView != null) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
            String nowIso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new java.util.Date());
            cm.setCookie("https://canela.tv", "OptanonAlertBoxClosed=" + nowIso + "; domain=.canela.tv; path=/");
            cm.setCookie("https://canela.tv", "OptanonConsent=isGpcEnabled=0&datestamp=" + nowIso + "&version=202401.1.0&isIABGlobal=false&hosts=&landingPath=NotLandingPage&groups=C0001%3A1%2CC0002%3A1%2CC0003%3A1%2CC0004%3A1; domain=.canela.tv; path=/");
            cm.flush();
        } catch (Exception ignored) {
        }
    }

    private void setupWebView() {
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }
        preconfigureCookiesForCanela();
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        try {
            String defaultUa = settings.getUserAgentString();
            if (defaultUa != null && defaultUa.contains("; wv")) {
                settings.setUserAgentString(defaultUa.replace("; wv", ""));
            }
        } catch (Exception ignored) {
        }
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
        try {
            settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        webView.setOnLongClickListener(v -> true);
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onPlayerBack() {
                handler.post(() -> finishWithResult());
            }
        }, "RexPlayerBridge");
        installDoramaFrameBridge();
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : "";
                return handleUrlNavigation(url);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlNavigation(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (isExiting || isFinishing() || isDestroyed()) return;
                if (isTuDoramaSource()) Log.i(TAG, "TuDorama cargando: " + Uri.parse(url).getHost());
                pageLoaded = false;
                doramaFrame = null;
                doramaFrameSeenAtMs = 0;
                adOverlaysCleaned = false;
                isVideoPlaying = false;
                lastVideoDurationMs = 0;
                cancelPlaybackWatchdog();
                progressBar.setVisibility(View.VISIBLE);
                showPlaybackStatus("Loading playback...");
                failoverInProgress = false;
                hideNextEpisodeButton(true);
                hideSkipIntroButton(true);
                currentIntroEndSec = -1;
                introSkipped = false;
                handler.removeCallbacks(autoPlayRunnable);
                handler.removeCallbacks(playbackMonitorRunnable);

                // Resetear flags para nueva página
                if (webView != null) {
                    webView.evaluateJavascript(
                            "try{window.__rexDone=false;window.__rexVidDone=false;}catch(e){}", null
                    );
                }

                injectPlayerBridge();
                injectHideVideoControls();
                scheduleServerTimeout();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null && isTvDevice && !disableExoSwitch && !exoSwitchInProgress) {
                    String reqUrl = request.getUrl().toString();
                    if (isPlaylistStreamUrl(reqUrl)) {
                        handler.post(() -> switchToExoPlayer(reqUrl));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isExiting || isFinishing() || isDestroyed()) return;
                pageLoaded = true;
                handler.postDelayed(() -> showControls(), 1000);
                currentServerRetry = 0;
                failoverInProgress = false;
                cancelServerTimeout();
                progressBar.setVisibility(android.view.View.GONE);
                hidePlaybackStatus();
                hideNextEpisodeLoader();
                isTransitioningToNextEpisode = false;
                autoNextTriggeredForCurrent = false;

                applyJwFullscreenFix(url);
                injectPlayerBridge();
                injectHideVideoControls();
                disableWebSubtitlesForAllCalidad();

                triggerAutoPlayAttempts();
                startPlaybackMonitor();
                schedulePositionRestore();
                schedulePlaybackWatchdog();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (isExiting || isFinishing() || isDestroyed()) return;
                if (request != null && request.isForMainFrame()) {
                    if (isTuDoramaSource()) Log.w(TAG, "TuDorama error WebView: " + error.getErrorCode());
                    handleServerFailure();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (isExiting || isFinishing() || isDestroyed()) return;
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    if (isTuDoramaSource()) Log.w(TAG, "TuDorama HTTP " + errorResponse.getStatusCode());
                    handleServerFailure();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (isExiting || isFinishing() || isDestroyed()) return;
                String current = currentServerIndex < serverUrls.size() ? serverUrls.get(currentServerIndex) : "";
                if (failingUrl != null && failingUrl.equals(current)) {
                    handleServerFailure();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Bloquear ventanas emergentes de anuncios (popunders / popups)
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (callback != null) callback.onCustomViewHidden();
            }

            @Override
            public void onShowCustomView(View view, int requestedOrientation, CustomViewCallback callback) {
                if (callback != null) callback.onCustomViewHidden();
            }

            @Override
            public void onHideCustomView() {
            }
        });

        webView.setOnTouchListener((v, event) -> {
            if (event == null || isExiting || isFinishing() || isDestroyed()) {
                return false;
            }
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                enterImmersiveMode();
                if (pageLoaded && !adOverlaysCleaned) {
                    adOverlaysCleaned = true;
                    removeAdOverlays();
                }
                if (isVideoPlaying || lastVideoDurationMs > 0) {
                    return true;
                }
                return false;
            } else if (action == MotionEvent.ACTION_UP) {
                if (handleVideoSurfaceTap(v, event)) {
                    return true;
                }
                // Si el video ya está reproduciéndose:
                // Solo alternar los controles nativos de la app y NO propagar el toque al WebView
                // para que ningún clickjacker o anuncio del sitio web se active al tocar la pantalla.
                if (isVideoPlaying || lastVideoDurationMs > 0) {
                    toggleControls();
                    return true;
                } else {
                    // Si el video aún no ha iniciado:
                    // Limpiar overlays transparentes antes de pasar el toque para que inicie la reproducción
                    removeAdOverlays();
                    showControls();
                    return false;
                }
            }
            return isVideoPlaying || lastVideoDurationMs > 0;
        });
        webView.setVisibility(android.view.View.VISIBLE);
    }

    private boolean isTuDoramaSource() {
        return "tudorama".equalsIgnoreCase(seriesSource)
                || (seriesDetailUrl != null && seriesDetailUrl.contains("tudorama.com"));
    }

    private void installDoramaFrameBridge() {
        if (!isTuDoramaSource() || webView == null) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WebView no admite control de frames TuDorama");
            return;
        }
        HashSet<String> origins = new HashSet<>(Arrays.asList(
                "https://bysesukior.com", "https://n1mwq.org", "https://hgcloud.to",
                "https://minochinos.com", "https://abyssplayer.com",
                "https://cdn.tudorama.com", "https://pixeldrain.com"));
        try {
            WebViewCompat.addWebMessageListener(webView, "RexDoramaFrame", origins,
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (isExiting || sourceOrigin == null || message == null) return;
                        String host = sourceOrigin.getHost();
                        if (host == null || !isTrustedDoramaFrame(host)) return;
                        String data = message.getData();
                        if (data == null || data.length() > 1000) return;
                        try {
                            JSONObject state = new JSONObject(data);
                            if (!state.optBoolean("hasMedia", false)) return;
                            double position = state.optDouble("currentTime", -1);
                            double duration = state.optDouble("duration", -1);
                            if (!Double.isFinite(position) || position < 0 || position > 86400
                                    || !Double.isFinite(duration) || duration < 0 || duration > 86400) return;
                            if (doramaFrame == null) {
                                Log.i(TAG, "Control TuDorama activo en " + host);
                            }
                            doramaFrame = replyProxy;
                            doramaFrameSeenAtMs = android.os.SystemClock.elapsedRealtime();
                            handlePlaybackProbe(data);
                        } catch (JSONException ignored) {
                        }
                    });
            StringBuilder script = new StringBuilder("window.__rexResumeSec=")
                    .append(resumePositionMs / 1000.0).append(';');
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getAssets().open("tudorama_frame_bridge.js"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) script.append(line).append('\n');
            }
            WebViewCompat.addDocumentStartJavaScript(webView, script.toString(), origins);
            Log.i(TAG, "Control de frames TuDorama instalado");
        } catch (Exception e) {
            Log.w(TAG, "No se pudo iniciar control de frames TuDorama", e);
        }
    }

    private boolean isTrustedDoramaFrame(String host) {
        return "bysesukior.com".equals(host) || "n1mwq.org".equals(host)
                || "hgcloud.to".equals(host) || "minochinos.com".equals(host)
                || "abyssplayer.com".equals(host) || "cdn.tudorama.com".equals(host)
                || "pixeldrain.com".equals(host);
    }

    private boolean sendDoramaFrameCommand(String type, double seconds) {
        if (!isTuDoramaSource() || doramaFrame == null
                || android.os.SystemClock.elapsedRealtime() - doramaFrameSeenAtMs > 5000L) return false;
        try {
            JSONObject command = new JSONObject();
            command.put("type", type);
            command.put("seconds", seconds);
            doramaFrame.postMessage(command.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void injectPlayerBridge() {
        if (webView == null || isExiting || isFinishing() || isDestroyed()) return;
        String bridgeJs = "(function(){"
                + "try{"
                + "  if(window.__rexBridgeInjected) return;"
                + "  window.__rexBridgeInjected = true;"
                + "  function triggerBack(){"
                + "    try{"
                + "      if(window.RexPlayerBridge && window.RexPlayerBridge.onPlayerBack){"
                + "        window.RexPlayerBridge.onPlayerBack();"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "  function attachBackListeners(doc){"
                + "    if(!doc) return;"
                + "    try{"
                + "      doc.addEventListener('click', function(e){"
                + "        var el = e.target;"
                + "        while(el && el !== doc.body && el !== doc.documentElement){"
                + "          var cls = (el.className || '').toString().toLowerCase();"
                + "          if(cls.indexOf('playback') !== -1 || cls.indexOf('background') !== -1 || cls.indexOf('backdrop') !== -1 || cls.indexOf('feedback') !== -1 || cls.indexOf('fallback') !== -1){"
                + "            el = el.parentElement; continue;"
                + "          }"
                + "          var tag = (el.tagName || '').toLowerCase();"
                + "          var aria = (el.getAttribute('aria-label') || '').toLowerCase();"
                + "          var title = (el.getAttribute('title') || '').toLowerCase();"
                + "          var id = (el.id || '').toLowerCase();"
                + "          var isBackBtn = false;"
                + "          if(/\\b(btn-back|button-back|arrow-back|back-btn|back-button|player-back|vjs-close-button|jw-svg-icon-close)\\b/.test(cls)){"
                + "            isBackBtn = true;"
                + "          } else if(/\\b(btn-back|button-back|back-btn|back-button)\\b/.test(id)){"
                + "            isBackBtn = true;"
                + "          } else if((tag === 'button' || tag === 'a' || tag === 'svg' || tag === 'i') && (aria === 'volver' || aria === 'regresar' || aria === 'atrás' || aria === 'atras' || aria === 'back' || aria === 'close' || aria === 'cerrar')){"
                + "            isBackBtn = true;"
                + "          } else if((tag === 'button' || tag === 'a') && (title === 'volver' || title === 'regresar' || title === 'atrás' || title === 'atras' || title === 'back' || title === 'close' || title === 'cerrar')){"
                + "            isBackBtn = true;"
                + "          }"
                + "          if(isBackBtn){"
                + "            e.preventDefault();"
                + "            e.stopPropagation();"
                + "            e.stopImmediatePropagation();"
                + "            triggerBack();"
                + "            return false;"
                + "          }"
                + "          el = el.parentElement;"
                + "        }"
                + "      }, true);"
                + "    }catch(e){}"
                + "  }"
                + "  attachBackListeners(document);"
                + "  try{"
                + "    var iframes = document.querySelectorAll('iframe');"
                + "    for(var i=0; i<iframes.length; i++){"
                + "      var idoc = iframes[i].contentDocument || (iframes[i].contentWindow && iframes[i].contentWindow.document);"
                + "      if(idoc) attachBackListeners(idoc);"
                + "    }"
                + "  }catch(e){}"
                + "}catch(e){}"
                + "})();";
        webView.evaluateJavascript(bridgeJs, null);
    }

    private boolean handleVideoSurfaceTap(View surface, MotionEvent event) {
        return false;
    }

    private void removeAdOverlays() {
        // Bloqueador de anuncios deshabilitado a petición del usuario para pruebas
    }

    private void triggerAutoPlayAttempts() {
        handler.removeCallbacks(autoPlayRunnable);
        // Intentos rápidos y precisos para arranque inmediato en Android TV
        handler.postDelayed(autoPlayRunnable, 80L);
        handler.postDelayed(autoPlayRunnable, 400L);
        handler.postDelayed(autoPlayRunnable, 1000L);
        handler.postDelayed(autoPlayRunnable, 2200L);
        handler.postDelayed(autoPlayRunnable, 4500L);
    }

    private void requestAutoPlay() {
        if (webView == null) return;

        String js = "(function(){"
                + "try{"
                + "var host=(location&&location.hostname?location.hostname.toLowerCase():'');"
                + "var isEmbed69=host.indexOf('embed69.org')!==-1;"
                + "var isJwCdn=host.indexOf('cdn.jwplayer.com')!==-1||host.indexOf('jwplayer.com')!==-1;"
                + "var isVidHide=host.indexOf('minochinos.com')!==-1"
                + "  ||host.indexOf('hglink.to')!==-1"
                + "  ||host.indexOf('bysedikamoum.com')!==-1"
                + "  ||host.indexOf('vidhide')!==-1"
                + "  ||host.indexOf('streamwish')!==-1"
                + "  ||host.indexOf('wishembed')!==-1"
                + "  ||host.indexOf('filemoon')!==-1"
                + "  ||host.indexOf('vimeos.net')!==-1;"
                + "var isCanela=host.indexOf('canela.tv')!==-1;"
                + "if(isCanela){"
                + "  try{"
                + "    var b=document.getElementById('onetrust-accept-btn-handler')||document.getElementById('accept-recommended-btn-handler');"
                + "    if(b){try{b.click();}catch(e){}}"
                + "    var btns=document.querySelectorAll('button, a, [role=\"button\"], input[type=\"button\"], div.ot-sdk-button');"
                + "    for(var bi=0;bi<btns.length;bi++){"
                + "      var txt=(btns[bi].textContent||btns[bi].innerText||btns[bi].value||'').toLowerCase().trim();"
                + "      if(txt==='aceptar todas'||txt==='aceptar todo'||txt==='aceptar todas las cookies'||txt.indexOf('aceptar todas')!==-1||txt.indexOf('aceptar todo')!==-1||txt==='solo necesarias'||txt==='sólo necesarias'||txt.indexOf('solo necesarias')!==-1||txt.indexOf('sólo necesarias')!==-1||txt==='accept all'||txt==='reject all'){"
                + "        try{btns[bi].click();btns[bi].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){}"
                + "      }"
                + "    }"
                + "    var rb=document.getElementById('onetrust-reject-all-handler')||document.querySelector('.ot-pc-refuse-all-handler');"
                + "    if(rb){try{rb.click();}catch(e){}}"
                + "    var ots=document.getElementById('onetrust-style');if(ots)try{ots.remove();}catch(e){}"
                + "    var bads=document.querySelectorAll('#onetrust-consent-sdk,#onetrust-banner-sdk,.onetrust-pc-dark-filter,.ot-sdk-container,[id*=\"onetrust\"],[class*=\"onetrust\"],div[id*=\"consent\"],div[class*=\"consent\"],div[class*=\"cookie-banner\"]');"
                + "    for(var bj=0;bj<bads.length;bj++){try{bads[bj].remove();}catch(e){try{bads[bj].style.display='none';}catch(e2){}}}"
                + "    if(window.localStorage)try{localStorage.setItem('OptanonAlertBoxClosed',new Date().toISOString());}catch(e){}"
                + "  }catch(e){}"
                + "  var vids=document.querySelectorAll('video');"
                + "  for(var vi=0;vi<vids.length;vi++){"
                + "    try{"
                + "      var cv=vids[vi];"
                + "      cv.muted=false;cv.volume=1;"
                + "      if(cv.paused){"
                + "        var p=cv.play();"
                + "        if(p&&p.catch){"
                + "          p.catch(function(){cv.muted=true;var p2=cv.play();if(p2&&p2.catch)p2.catch(function(){});});"
                + "        }"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "  return '';"
                + "}"

                // === CAPA 1: embed69 ===
                + "if(isEmbed69){"
                + "  try{window.go_to_playerVast=function(){return false;};}catch(e){}"
                + "  try{window.executePopupCode=function(){};}catch(e){}"
                + "  var adVid=document.getElementById('videoPlayer');"
                + "  if(adVid){try{adVid.pause();adVid.removeAttribute('src');adVid.load();}catch(e){}}"
                + "  var modal=document.getElementById('modal');"
                + "  if(modal)modal.style.display='none';"
                + "  if(typeof dataLink==='undefined'||!Array.isArray(dataLink)||dataLink.length===0)return '';"
                + "  function cleanLink(v){"
                + "    if(typeof v==='object'&&v!==null&&v.link){return String(v.link).replace(/`/g,'').trim();}"
                + "    return(typeof v==='string'?v.replace(/`/g,'').trim():'');"
                + "  }"
                + "  function extractUrl(v){"
                + "    v=cleanLink(v);"
                + "    if(!v)return '';"
                + "    if(v.indexOf('http')===0)return v;"
                + "    try{"
                + "      var p=v.split('.');"
                + "      if(p.length<2)return v;"
                + "      var b=p[1].replace(/-/g,'+').replace(/_/g,'/');"
                + "      while(b.length%4!==0)b+='=';"
                + "      var j=JSON.parse(atob(b));"
                + "      return cleanLink(j&&j.link?j.link:'');"
                + "    }catch(e){return v;}"
                + "  }"
                + "  var selectedFile=null;"
                + "  for(var i=0;i<dataLink.length;i++){"
                + "    var lang=((dataLink[i]&&dataLink[i].video_language)?String(dataLink[i].video_language):'').toUpperCase();"
                + "    if(lang.indexOf('LAT')!==-1){selectedFile=dataLink[i];break;}"
                + "    if(!selectedFile)selectedFile=dataLink[i];"
                + "  }"
                + "  var embeds=(selectedFile&&Array.isArray(selectedFile.sortedEmbeds))?selectedFile.sortedEmbeds:[];"
                + "  if(!embeds.length)return '';"
                + "  var pref=['vidhide','streamwish','filemoon','voe'];"
                + "  var picked=null;"
                + "  for(var p=0;p<pref.length&&!picked;p++){"
                + "    for(var j=0;j<embeds.length;j++){"
                + "      var sn=((embeds[j]&&embeds[j].servername)?String(embeds[j].servername):'').toLowerCase();"
                + "      if(sn.indexOf(pref[p])!==-1){picked=embeds[j];break;}"
                + "    }"
                + "  }"
                + "  if(!picked)picked=embeds[0];"
                + "  var rawLink=picked&&(picked.link||picked.download)||'';"
                + "  var embedUrl=extractUrl(rawLink);"
                + "  if(!embedUrl)return '';"
                // Ocultar UI de embed69
                + "  var fp=document.getElementById('fakePlayer');if(fp)fp.style.display='none';"
                + "  var wm=document.getElementById('warningModal');if(wm)wm.style.display='none';"
                + "  var ltc=document.getElementById('languageTabContainer');if(ltc)ltc.style.display='none';"
                + "  var stc=document.getElementById('serverTabContainer');if(stc)stc.style.display='none';"
                + "  document.body.style.cssText='margin:0;padding:0;overflow:hidden;background:#000;';"
                // Retornar la URL para que Java la cargue directamente
                + "  return embedUrl;"
                + "}"

                + "if(isJwCdn){"
                + "  try{document.documentElement.style.cssText='margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;';}catch(e){}"
                + "  try{document.body.style.cssText='margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;';}catch(e){}"
                + "  try{"
                + "    if(window.jwplayer){"
                + "      var jw=window.jwplayer();"
                + "      if(jw){"
                + "        try{if(jw.setMute)jw.setMute(true);}catch(e){}"
                + "        try{if(jw.play)jw.play(true);}catch(e){}"
                + "        var jw2=window.jwplayer('vplayer');"
                + "        if(jw2){"
                + "          try{if(jw2.setMute)jw2.setMute(true);}catch(e){}"
                + "          try{if(jw2.play)jw2.play(true);}catch(e){}"
                + "        }"
                + "      }"
                + "    }"
                + "  }catch(e){}"
                + "  var vids=document.querySelectorAll('video');"
                + "  for(var v=0;v<vids.length;v++){"
                + "    try{"
                + "      var vid=vids[v];"
                + "      vid.muted=true;vid.playsInline=true;"
                + "      if(vid.paused){"
                + "        var vp=vid.play();"
                + "        if(vp&&vp.then){vp.then(function(){setTimeout(function(){try{vid.muted=false;}catch(e){}},300);}).catch(function(){vid.muted=true;try{vid.play();}catch(e2){}})}"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "  return '';"
                + "}"

                // === CAPA 2: vidhide/minochinos — JWPlayer ===
                + "if(isVidHide){"
                // JWPlayer: reproducir muteado primero (Chromium permite autoplay muted)
                + "  try{"
                + "    if(window.jwplayer){"
                + "      var jw=window.jwplayer('vplayer')||window.jwplayer();"
                + "      if(jw&&typeof jw.getState==='function'){"
                + "        var state=jw.getState();"
                + "        if(state==='idle'||state==='paused'){"
                + "          try{if(jw.setMute)jw.setMute(true);}catch(e){}"
                + "          try{if(jw.play)jw.play(true);}catch(e){}"
                + "        }"
                + "      }"
                + "    }"
                + "  }catch(e){}"
                + "  var jwBtn=document.querySelector('.jw-display-icon-container,.jw-display-icon-display');"
                + "  if(jwBtn){try{jwBtn.click();}catch(e){}}"
                // Videos nativos: reproducir muteado primero
                + "  var vids=document.querySelectorAll('video');"
                + "  for(var v=0;v<vids.length;v++){"
                + "    try{"
                + "      var vid=vids[v];"
                + "      vid.muted=true;vid.playsInline=true;"
                + "      if(vid.paused){"
                + "        var vp=vid.play();"
                + "        if(vp&&vp.catch)vp.catch(function(){});"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "  return '';"
                + "}"

                // === OTROS PLAYERS genéricos (Cuevana y otros servidores) ===
                + "function isPlaying(m){return!!m&&!m.paused&&!m.ended&&m.readyState>2;}"
                + "var allMedia=[];"
                + "try{"
                + "  var v0=document.querySelectorAll('video,audio');"
                + "  for(var i=0;i<v0.length;i++)allMedia.push({m:v0[i],doc:document});"
                + "  var ifrs=document.querySelectorAll('iframe');"
                + "  for(var j=0;j<ifrs.length;j++){"
                + "    try{"
                + "      var idoc=ifrs[j].contentDocument||(ifrs[j].contentWindow&&ifrs[j].contentWindow.document);"
                + "      if(idoc){"
                + "        var iv=idoc.querySelectorAll('video,audio');"
                + "        for(var k=0;k<iv.length;k++)allMedia.push({m:iv[k],doc:idoc});"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "}catch(e){}"
                + "for(var i=0;i<allMedia.length;i++){if(isPlaying(allMedia[i].m))return '';}"
                + "try{var yp=document.getElementById('yesplease');if(yp){yp.click();}var cr=document.querySelectorAll('#checkresume_div,.checkresume');for(var ci=0;ci<cr.length;ci++){try{cr[ci].remove();}catch(e){}}}catch(e){}"
                + "var sel='.vjs-big-play-button,.play-wrapper,.play-icon,#os_player .play-icon,#player-overlay,.plyr__control--overlaid,.fluid_initial_play,button.play,[aria-label=\"Play\"],.jw-display-icon-container,.jw-icon-display,.playbtn,#playBtn,.play-btn,.jw-preview,.jw-media';"
                + "function clickAllIn(doc){"
                + "  if(!doc)return;"
                + "  try{"
                + "    var btns=doc.querySelectorAll(sel);"
                + "    for(var b=0;b<btns.length;b++){"
                + "      try{btns[b].click();}catch(e){}"
                + "      try{btns[b].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e2){}"
                + "    }"
                + "  }catch(e){}"
                + "}"
                + "clickAllIn(document);"
                + "try{"
                + "  var ifrs=document.querySelectorAll('iframe');"
                + "  for(var j=0;j<ifrs.length;j++){"
                + "    try{"
                + "      var idoc=ifrs[j].contentDocument||(ifrs[j].contentWindow&&ifrs[j].contentWindow.document);"
                + "      clickAllIn(idoc);"
                + "    }catch(e){}"
                + "  }"
                + "}catch(e){}"
                + "for(var j=0;j<allMedia.length;j++){"
                + "  var m=allMedia[j].m;"
                + "  try{"
                + "    m.muted=true;m.playsInline=true;"
                + "    m.setAttribute('playsinline','');"
                + "    m.setAttribute('webkit-playsinline','');"
                + "    if(m.paused){"
                + "      var pr=m.play();"
                + "      if(pr&&pr.then){"
                + "        pr.then(function(){setTimeout(function(){try{m.muted=false;}catch(e){}},400);}).catch(function(){m.muted=true;try{m.play();}catch(e2){}});"
                + "      }"
                + "    }"
                + "  }catch(e){}"
                + "}"
                + "try{if(window.jwplayer){var jw=window.jwplayer('vplayer')||window.jwplayer();if(jw){try{if(jw.setMute)jw.setMute(true);}catch(e){}if(jw.play)jw.play(true);}}}catch(e){}"
                + "try{if(window.videojs&&window.videojs.players){Object.keys(window.videojs.players).forEach(function(k){try{var p=window.videojs.players[k];p.muted(true);if(p.paused&&p.paused())p.play();}catch(e){}});}}catch(e){}"
                + "return '';"
                + "}catch(err){return '';}"
                + "})();";

        // El callback recibe la URL del embed (si embed69 la retornó)
        webView.evaluateJavascript(js, result -> {
            if (result == null) return;
            // evaluateJavascript envuelve strings en comillas, limpiar
            String playbackUrl = result.trim();
            if (playbackUrl.startsWith("\"")) {
                playbackUrl = playbackUrl.substring(1);
            }
            if (playbackUrl.endsWith("\"")) {
                playbackUrl = playbackUrl.substring(0, playbackUrl.length() - 1);
            }
            // Decodificar escapes unicode que Android agrega (\u003d etc)
            playbackUrl = playbackUrl.replace("\\u003d", "=")
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");

            if (playbackUrl.isEmpty() || playbackUrl.equals("null")) return;

            // Verificar que es una URL de un host de video conocido (Capa 2)
            boolean isVideoHost = isKnownEmbedVideoHost(playbackUrl);

            if (!isVideoHost) return;

            // Usar flag Java para evitar recargas redundantes
            if (playbackUrl.equals(lastLoadedEmbedUrl)) return;
            lastLoadedEmbedUrl = playbackUrl;

            // Cargar la Capa 2 solo si no hay un video reproduciéndose (nunca
            // interrumpir el playback con una recarga del WebView).
            loadEmbedUrlIfNotPlaying(playbackUrl);
        });
    }

    /** Carga una URL de embed en el WebView salvo que ya haya video en marcha. */
    private void loadEmbedUrlIfNotPlaying(final String embedUrl) {
        if (webView == null || embedUrl == null) return;
        // Se considera "en reproducción" si el elemento tiene progreso (currentTime>0)
        // o ya tiene datos suficientes (readyState>=2), para no recargar durante el
        // arranque de hls.js ni ante breves pausas de buffering.
        webView.evaluateJavascript(
            "(function(){try{var m=document.querySelector('video');"
            + "return !!(m&&(m.currentTime>0||m.readyState>=2));}catch(e){return false}})();",
            result -> {
                if ("true".equals(result)) return; // no interrumpir reproducción
                handler.post(() -> {
                    if (webView == null || isFinishing() || isDestroyed()) return;
                    serverUrls.clear();
                    serverUrls.add(embedUrl);
                    currentServerIndex = 0;
                    pageLoaded = false;
                    if (embedUrl.contains("canela.tv")) {
                        preconfigureCookiesForCanela();
                    }
                    Map<String, String> headers = buildPlaybackHeaders(embedUrl);
                    if (headers.isEmpty()) {
                        webView.loadUrl(embedUrl);
                    } else {
                        webView.loadUrl(embedUrl, headers);
                    }
                });
            });
    }

    private void startPlaybackMonitor() {
        handler.removeCallbacks(playbackMonitorRunnable);
        if (resumed) {
            handler.postDelayed(playbackMonitorRunnable, PLAYBACK_POLL_INTERVAL_MS);
        }
    }

    private void inspectPlaybackState() {
        if (isExiting || webView == null || isFinishing() || isDestroyed()) {
            return;
        }
        if (isTuDoramaSource() && doramaFrame != null
                && android.os.SystemClock.elapsedRealtime() - doramaFrameSeenAtMs < 3500L) {
            return;
        }

        if (isSeriesEpisodePlayback()) {
            if (nextEpisodeInfo == null && !loadingSeasonData) {
                preloadSeasonData();
            }
        }

        String js = "(function(){"
                + "try{"
                + "  try{"
                + "    var yp=document.getElementById('yesplease');if(yp){yp.click();}"
                + "    var cr=document.querySelectorAll('#checkresume_div,.checkresume');for(var i=0;i<cr.length;i++){try{cr[i].remove();}catch(e){}}"
                + "    var ob=document.getElementById('onetrust-accept-btn-handler')||document.getElementById('accept-recommended-btn-handler');"
                + "    if(ob){try{ob.click();}catch(e){}}"
                + "    var obtns=document.querySelectorAll('button,a,[role=\"button\"],div.ot-sdk-button');"
                + "    for(var obi=0;obi<obtns.length;obi++){"
                + "      var otx=(obtns[obi].textContent||obtns[obi].innerText||'').toLowerCase().trim();"
                + "      if(otx==='aceptar todas'||otx.indexOf('aceptar todas')!==-1||otx==='solo necesarias'||otx.indexOf('solo necesarias')!==-1){"
                + "        try{obtns[obi].click();obtns[obi].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){}"
                + "      }"
                + "    }"
                + "    var badNodes=document.querySelectorAll('#onetrust-consent-sdk,#onetrust-banner-sdk,.onetrust-pc-dark-filter,.ot-sdk-container');"
                + "    for(var bni=0;bni<badNodes.length;bni++){try{badNodes[bni].remove();}catch(e){try{badNodes[bni].style.display='none';}catch(e2){}}}"
                + "  }catch(e){}"
                + "  var dur=0;var cur=0;var isPaused=true;var ended=false;var hasMedia=false;"
                + "if(window.jwplayer){"
                + "  try{"
                + "    var jw=window.jwplayer('vplayer')||window.jwplayer();"
                + "    if(jw&&typeof jw.getState==='function'){"
                + "      var st=jw.getState();"
                + "      if(st==='playing'||st==='buffering'||st==='paused'){"
                + "        hasMedia=true;"
                + "        isPaused=(st==='paused');"
                + "        var jd=(typeof jw.getDuration==='function')?(jw.getDuration()||0):0;"
                + "        var jc=(typeof jw.getPosition==='function')?(jw.getPosition()||0):0;"
                + "        if(isFinite(jd)&&jd>0)dur=jd;"
                + "        if(isFinite(jc)&&jc>=0)cur=jc;"
                + "      }"
                + "    }"
                + "  }catch(e){}"
                + "}"
                + "var media=[];"
                + "try{"
                + "  var v0=document.querySelectorAll('video,audio');"
                + "  for(var i=0;i<v0.length;i++)media.push(v0[i]);"
                + "  var ifrs=document.querySelectorAll('iframe');"
                + "  for(var j=0;j<ifrs.length;j++){"
                + "    try{"
                + "      var doc=ifrs[j].contentDocument||(ifrs[j].contentWindow&&ifrs[j].contentWindow.document);"
                + "      if(doc){"
                + "        var vf=doc.querySelectorAll('video,audio');"
                + "        for(var k=0;k<vf.length;k++)media.push(vf[k]);"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "}catch(e){}"
                + "if(media&&media.length>0){"
                + "  var selected=null;"
                + "  for(var i=0;i<media.length;i++){"
                + "    var m=media[i];"
                + "    if(!selected)selected=m;"
                + "    if(m&&!m.paused){selected=m;break;}"
                + "  }"
                + "  if(selected){"
                + "    var md=(isFinite(selected.duration)&&selected.duration>0)?selected.duration:0;"
                + "    var mc=(isFinite(selected.currentTime)&&selected.currentTime>=0)?selected.currentTime:0;"
                + "    if(md>0)dur=md;"
                + "    if(mc>0||dur>0)cur=mc;"
                + "    if(!selected.paused)isPaused=false;"
                + "    if(selected.ended)ended=true;"
                + "    if(dur>0||cur>0||!selected.paused||(selected.readyState&&selected.readyState>=2)){"
                + "      hasMedia=true;"
                + "    }"
                + "  }"
                + "}"
                + "if(!hasMedia)return {hasMedia:false};"
                + "var remaining=(dur>0)?Math.max(0,dur-cur):999999;"
                + "var hasSkip=false;var introEnd=-1;"
                + "try{"
                + "  var skipEl=document.querySelector('.skip-option.intro, [class*=\"skip-option\"][class*=\"intro\"], .player-skip-options .intro, [class*=\"skipintro\"], [class*=\"skip-intro\"]');"
                + "  if(skipEl){hasSkip=true;}"
                + "}catch(e){}"
                + "if(!hasSkip){"
                + "  try{"
                + "    var ifrs=document.querySelectorAll('iframe');"
                + "    for(var ifi=0;ifi<ifrs.length;ifi++){"
                + "      try{"
                + "        var idoc=ifrs[ifi].contentDocument||(ifrs[ifi].contentWindow&&ifrs[ifi].contentWindow.document);"
                + "        if(idoc&&idoc.querySelector('.skip-option.intro, [class*=\"skip-option\"][class*=\"intro\"], .player-skip-options .intro, [class*=\"skipintro\"], [class*=\"skip-intro\"]')){"
                + "          hasSkip=true;break;"
                + "        }"
                + "      }catch(e){}"
                + "    }"
                + "  }catch(e){}"
                + "}"
                + "try{"
                + "  var ap=document.querySelector('app-player, app-player-control');"
                + "  if(ap&&ap.__ngContext__){"
                + "    var ctx=ap.__ngContext__;"
                + "    var ctxArr=Array.isArray(ctx)?ctx:(ctx.lView||[]);"
                + "    for(var ci=0;ci<ctxArr.length;ci++){"
                + "      var item=ctxArr[ci];"
                + "      if(item&&typeof item==='object'){"
                + "        if(item.skipIntro&&typeof item.skipIntro==='object'){"
                + "          if(item.skipIntro.show){hasSkip=true;}"
                + "          if(isFinite(item.skipIntro.end)&&item.skipIntro.end>0){introEnd=item.skipIntro.end;}"
                + "        }"
                + "        if(item.markers&&Array.isArray(item.markers)){"
                + "          for(var mi=0;mi<item.markers.length;mi++){"
                + "            var m=item.markers[mi];"
                + "            if(m&&(m.type==='skipintro'||m.t==='skipintro')){"
                + "              var ms=(m.start!=null)?m.start:m.m_st;"
                + "              var me=(m.end!=null)?m.end:m.m_ed;"
                + "              if(cur>=ms&&cur<me){hasSkip=true;introEnd=me;}"
                + "            }"
                + "          }"
                + "        }"
                + "      }"
                + "    }"
                + "  }"
                + "}catch(e){}"
                + "try{"
                + "  if(selected&&selected.textTracks){"
                + "    for(var ti=0;ti<selected.textTracks.length;ti++){"
                + "      var trk=selected.textTracks[ti];"
                + "      if(trk&&trk.cues){"
                + "        for(var ui=0;ui<trk.cues.length;ui++){"
                + "          var c=trk.cues[ui];"
                + "          var txt=((c.text||c.id||'')+'').toLowerCase();"
                + "          if(txt.indexOf('intro')!==-1&&cur>=c.startTime&&cur<=c.endTime){"
                + "            hasSkip=true;if(c.endTime>0)introEnd=c.endTime;"
                + "          }"
                + "        }"
                + "      }"
                + "    }"
                + "  }"
                + "}catch(e){}"
                + "return {hasMedia:true,duration:dur,currentTime:cur,remaining:remaining,ended:ended,paused:isPaused,hasSkipIntro:hasSkip,introEnd:introEnd};"
                + "} catch(err) {"
                + "return {hasMedia:false};"
                + "}"
                + "})();";

        if (webView != null && !isFinishing() && !isDestroyed()) {
            try {
                webView.evaluateJavascript(js, this::handlePlaybackProbe);
            } catch (Exception ignored) {
            }
        }
    }

    private void unmuteWebViewAudio() {
        if (webView == null) return;
        webView.evaluateJavascript(
            "(function(){try{"
            + "if(window.jwplayer){var jw=window.jwplayer('vplayer')||window.jwplayer();if(jw){if(jw.setMute)jw.setMute(false);if(jw.setVolume)jw.setVolume(100);}}"
            + "var vids=document.querySelectorAll('video');for(var i=0;i<vids.length;i++){try{vids[i].muted=false;vids[i].volume=1;}catch(e){}}"
            + "}catch(e){}})();", null
        );
    }

    private boolean isAllCalidadSource() {
        String source = seriesSource == null ? "" : seriesSource.trim();
        return seriesPostId > 0 && (source.isEmpty() || "allcalidad".equalsIgnoreCase(source));
    }

    private void disableWebSubtitlesForAllCalidad() {
        if (!isAllCalidadSource() || webView == null) return;
        webView.evaluateJavascript("(function(){try{"
                + "var videos=document.querySelectorAll('video');"
                + "for(var i=0;i<videos.length;i++){var tracks=videos[i].textTracks||[];"
                + "for(var j=0;j<tracks.length;j++){tracks[j].mode='disabled';}}"
                + "if(window.jwplayer){var jw=window.jwplayer('vplayer')||window.jwplayer();"
                + "if(jw&&jw.setCurrentCaptions)jw.setCurrentCaptions(-1);}" 
                + "}catch(e){}})();", null);
    }

    private void handlePlaybackProbe(String raw) {
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw)) {
            return;
        }

        try {
            JSONObject state = new JSONObject(raw);
            if (!state.optBoolean("hasMedia", false)) {
                if (isSeriesEpisodePlayback()) hideNextEpisodeButton(false);
                hideSkipIntroButton(false);
                return;
            }

            double currentTime = state.optDouble("currentTime", 0d);
            double duration = state.optDouble("duration", 0d);
            double remaining = state.optDouble("remaining", Double.MAX_VALUE);
            boolean ended = state.optBoolean("ended", false);
            boolean isPaused = state.optBoolean("paused", false);
            boolean hasSkipIntro = state.optBoolean("hasSkipIntro", false);
            double introEnd = state.optDouble("introEnd", -1d);
            if (isTuDoramaSource() && state.optBoolean("restored", false)) {
                positionRestored = true;
            }

            lastCurrentTimeSec = currentTime;

            if (duration > 0 || currentTime > 0 || !isPaused) {
                isVideoPlaying = true;
                cancelPlaybackWatchdog();
                if (duration > 0) {
                    lastVideoDurationMs = (long) (duration * 1000);
                }
                injectHideVideoControls();
                unmuteWebViewAudio();
                if (isTvDevice) {
                    scheduleTvControlsHide();
                }
            }

            if (!isTuDoramaSource() && !positionRestored && resumePositionMs > 0 && duration > 0) {
                double targetSec = resumePositionMs / 1000.0;
                if (targetSec < duration) {
                    String seekJs = "(function(){"
                            + "try{"
                            + "var v=document.querySelector('video');"
                            + "if(v){v.currentTime=" + targetSec + ";}"
                            + "if(window.jwplayer){try{window.jwplayer().seek(" + targetSec + ");}catch(e){}}"
                            + "}catch(e){}"
                            + "})();";
                    webView.evaluateJavascript(seekJs, null);
                }
                positionRestored = true;
            }

            updateOverlayState(currentTime, duration, isPaused);
            if (resumePositionMs <= 0 || positionRestored) {
                savePlaybackProgress(currentTime, duration);
            }

            if (hasSkipIntro && !introSkipped) {
                showSkipIntroButton(introEnd);
            } else {
                if (!hasSkipIntro) {
                    introSkipped = false;
                }
                hideSkipIntroButton(false);
            }

            if (!isSeriesEpisodePlayback()) {
                if (ended && contentId != null && !contentId.isEmpty()) {
                    ContinueWatchingStore.markCompleted(PlayerContenidoActivity.this, contentId);
                }
                return;
            }

            boolean hasEndedNaturally = ended && duration > 10 && currentTime > 5;
            boolean hasReachedEnd = duration > 30 && remaining <= AUTO_NEXT_REMAINING_SEC && currentTime > 20;
            if ((hasEndedNaturally || hasReachedEnd) && !autoNextTriggeredForCurrent) {
                autoNextTriggeredForCurrent = true;
                if (contentId != null && !contentId.isEmpty()) {
                    ContinueWatchingStore.markCompleted(PlayerContenidoActivity.this, contentId);
                }
                playNextEpisode(true);
                return;
            }

            if (duration > 30) {
                double trigger = resolveNextButtonTrigger(duration);
                if (remaining <= trigger && remaining > AUTO_NEXT_REMAINING_SEC) {
                    showNextEpisodeButton(remaining);
                } else if (remaining > trigger + NEXT_EPISODE_HIDE_HYSTERESIS_SEC) {
                    hideNextEpisodeButton(false);
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private double resolveNextButtonTrigger(double durationSec) {
        if (durationSec <= 0) {
            return NEXT_EPISODE_BUTTON_REMAINING_SEC;
        }
        if (durationSec <= NEXT_EPISODE_BUTTON_REMAINING_SEC + 30d) {
            return Math.max(15d, durationSec * 0.15d);
        }
        return NEXT_EPISODE_BUTTON_REMAINING_SEC;
    }

    private void showNextEpisodeButton(double remainingSec) {
        if (!SHOW_NEXT_EPISODE_BUTTON || nextEpisodeButton == null || nextEpisodeInfo == null
                || isTransitioningToNextEpisode) {
            return;
        }

        String code = formatEpisodeCode(nextEpisodeInfo.seasonNumber, nextEpisodeInfo.episodeNumber);
        if (remainingSec <= 30d) {
            int countdown = (int) Math.ceil(Math.max(0d, remainingSec));
            nextEpisodeButton.setText("Siguiente episodio (" + countdown + "s)");
        } else {
            nextEpisodeButton.setText("Siguiente episodio • " + code);
        }

        if (nextButtonVisible && nextEpisodeButton.getVisibility() == View.VISIBLE) {
            return;
        }

        hideSkipIntroButton(true);
        nextButtonVisible = true;
        nextEpisodeButton.setVisibility(View.VISIBLE);
        nextEpisodeButton.setAlpha(0f);
        nextEpisodeButton.animate().alpha(1f).setDuration(180L).start();
        if (isTvDevice) {
            nextEpisodeButton.requestFocus();
        }
    }

    private void hideNextEpisodeButton(boolean immediate) {
        if (nextEpisodeButton == null) {
            return;
        }
        if (!nextButtonVisible && nextEpisodeButton.getVisibility() != View.VISIBLE) {
            return;
        }

        nextButtonVisible = false;
        nextEpisodeButton.setText(NEXT_EPISODE_LABEL_BASE);
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
        showPlaybackStatus(label);
    }

    private ArrayList<String> filterDeprecatedServers(ArrayList<String> urls) {
        ArrayList<String> filtered = new ArrayList<>();
        if (urls == null || urls.isEmpty()) {
            return filtered;
        }
        for (String candidate : urls) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            String normalized = candidate.trim();
            String lc = normalized.toLowerCase(Locale.ROOT);
            if (lc.contains("embed69.org")) {
                continue;
            }
            // Descartar páginas de catálogo/sitio web (no son reproductores de video)
            if (lc.contains("/ver-pelicula/") || lc.contains("/ver-serie/")
                    || lc.contains("/pelicula/") || lc.contains("/serie/")
                    || lc.contains("/episodio/")
                    || (lc.contains("cuevana3") && !lc.contains("player.cuevana3"))) {
                continue;
            }
            filtered.add(normalized);
        }
        return filtered;
    }

    private void hideNextEpisodeLoader() {
        hidePlaybackStatus();
    }

    private void showPlaybackStatus(String label) {
        if (nextEpisodeLoaderText != null) {
            nextEpisodeLoaderText.setText(label);
            nextEpisodeLoaderText.setVisibility(View.VISIBLE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
    }

    private void hidePlaybackStatus() {
        if (nextEpisodeLoaderText != null) {
            nextEpisodeLoaderText.setVisibility(View.GONE);
        }
    }

    private void playNextEpisode(boolean automatic) {
        if (isExiting || isFinishing() || isDestroyed()) {
            return;
        }
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
        hideSkipIntroButton(true);
        currentIntroEndSec = -1;
        introSkipped = false;

        // Al elegir explícitamente el siguiente dentro de la ventana de tres
        // minutos, el episodio saliente queda finalizado de forma coherente.
        if (!automatic && contentId != null && !contentId.isEmpty()) {
            ContinueWatchingStore.markCompleted(this, contentId);
        }

        final NextEpisodeInfo target = nextEpisodeInfo;
        String code = formatEpisodeCode(target.seasonNumber, target.episodeNumber);
        showNextEpisodeLoader("Cargando " + code + "...");

        new Thread(() -> {
            try {
                List<String> urls = new ArrayList<>();
                boolean isNovelas = (seriesSource != null && ("novelas".equalsIgnoreCase(seriesSource) || "canela".equalsIgnoreCase(seriesSource)))
                        || (seriesDetailUrl != null && seriesDetailUrl.contains("canela.tv"));
                if (isNovelas) {
                    try {
                        sv.edu.catolica.rex.network.smart.CanelaScraper canelaScraper =
                                new sv.edu.catolica.rex.network.smart.CanelaScraper();
                        sv.edu.catolica.rex.models.MediaItem item = new sv.edu.catolica.rex.models.MediaItem(
                                seriesTitle, "", imageUrl != null ? imageUrl : "",
                                seriesDetailUrl != null ? seriesDetailUrl : "", seriesPostId);
                        item.setMediaType(seriesPostType != null ? seriesPostType : "tvshows");
                        item.setFuente(seriesSource);
                        sv.edu.catolica.rex.network.smart.SmartPlaybackResult canelaResult =
                                canelaScraper.resolveEpisode(item, target.seasonNumber, target.episodeNumber, target.episodeId);
                        if (canelaResult != null && canelaResult.hasPlayableUrls()) {
                            urls = canelaResult.toPlayableUrls();
                        }
                    } catch (Exception ignored) {}
                }

                boolean isAllCalidad = !isNovelas && seriesPostId > 0
                        && (seriesSource == null || seriesSource.isEmpty() || "allcalidad".equalsIgnoreCase(seriesSource));
                if (isAllCalidad && target.episodeId > 0) {
                    try {
                        AllCalidadScraper.hit(target.episodeId, "episodes");
                        urls = AllCalidadScraper.getPlayableUrls(AllCalidadScraper.getPlayer(target.episodeId));
                    } catch (Exception ignored) {}
                }

                if (urls == null || urls.isEmpty()) {
                    try {
                        sv.edu.catolica.rex.network.smart.SmartScraperEngine engine =
                                new sv.edu.catolica.rex.network.smart.SmartScraperEngine();
                        sv.edu.catolica.rex.models.MediaItem item = new sv.edu.catolica.rex.models.MediaItem(seriesTitle, "", imageUrl != null ? imageUrl : "", seriesDetailUrl != null ? seriesDetailUrl : "", seriesPostId);
                        item.setMediaType(seriesPostType != null ? seriesPostType : "tvshows");
                        item.setFuente(seriesSource);
                        urls = engine.resolveEpisodeUrls(
                                PlayerContenidoActivity.this,
                                item,
                                target.seasonNumber,
                                target.episodeNumber,
                                target.episodeId
                        );
                    } catch (Exception ignored) {}
                }

                final List<String> resolvedUrls = urls;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    if (resolvedUrls == null || resolvedUrls.isEmpty()) {
                        isTransitioningToNextEpisode = false;
                        hideNextEpisodeLoader();
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "No se pudo cargar el siguiente episodio", Toast.LENGTH_SHORT).show();
                        startPlaybackMonitor();
                        return;
                    }

                    applyEpisodeAndLoad(target, new ArrayList<>(resolvedUrls));
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
        ArrayList<String> directStreamUrls = extractDirectStreamUrls(urls);
        if (!directStreamUrls.isEmpty()) {
            String title = seriesTitle + " - " + formatEpisodeCode(episode.seasonNumber, episode.episodeNumber);
            String nextContentId = (seriesPostId > 0 && episode.episodeId > 0)
                    ? seriesPostId + "_" + episode.episodeId : contentId;
            String embedReferer = isTuDoramaSource() ? "https://tudorama.com/"
                    : originOf(urls != null && !urls.isEmpty() ? urls.get(0) : null);
            Intent exoIntent = new Intent(this, PlayerExoActivity.class);
            exoIntent.putStringArrayListExtra(EXTRA_URLS, directStreamUrls);
            exoIntent.putStringArrayListExtra("exo_urls", directStreamUrls);
            exoIntent.putExtra(EXTRA_TITLE, title);
            exoIntent.putExtra("exo_title", title);
            exoIntent.putExtra("content_id", nextContentId);
            exoIntent.putExtra("exo_content_id", nextContentId);
            exoIntent.putExtra("series_title", seriesTitle);
            exoIntent.putExtra("exo_series_title", seriesTitle);
            exoIntent.putExtra("image_url", imageUrl);
            exoIntent.putExtra("exo_image_url", imageUrl);
            exoIntent.putExtra("post_id", seriesPostId);
            exoIntent.putExtra("exo_post_id", seriesPostId);
            exoIntent.putExtra("media_type", seriesPostType);
            exoIntent.putExtra("exo_media_type", seriesPostType);
            exoIntent.putExtra("episode_id", episode.episodeId);
            exoIntent.putExtra("exo_episode_id", episode.episodeId);
            exoIntent.putExtra("season_number", episode.seasonNumber);
            exoIntent.putExtra("exo_season_number", episode.seasonNumber);
            exoIntent.putExtra("episode_number", episode.episodeNumber);
            exoIntent.putExtra("exo_episode_number", episode.episodeNumber);
            exoIntent.putExtra("resume_position_ms", 0L);
            exoIntent.putExtra("exo_resume_position_ms", 0L);
            exoIntent.putExtra("series_source", seriesSource);
            exoIntent.putExtra("exo_series_source", seriesSource);
            exoIntent.putExtra("series_detail_url", seriesDetailUrl);
            exoIntent.putExtra("exo_series_detail_url", seriesDetailUrl);
            if (isTuDoramaSource()) {
                exoIntent.putStringArrayListExtra("exo_fallback_embed_urls", extractEmbedUrls(urls));
            }
            if (embedReferer != null) {
                exoIntent.putExtra("referer", embedReferer);
                exoIntent.putExtra("origin", embedReferer);
                exoIntent.putExtra("exo_referer", embedReferer);
                exoIntent.putExtra("exo_origin", embedReferer);
            }
            startActivity(exoIntent);
            finish();
            return;
        }

        currentEpisodeId = episode.episodeId;
        currentSeasonNumber = episode.seasonNumber;
        currentEpisodeNumber = episode.episodeNumber;
        contentId = buildContentId();
        resumePositionMs = 0L;
        positionRestored = false;
        lastSavedPositionSec = -1;
        nextEpisodeInfo = null;
        seasonPreloadAttempted = false;

        serverUrls.clear();
        serverUrls.addAll(urls);
        currentServerIndex = 0;
        pageLoaded = false;
        autoNextTriggeredForCurrent = false;
        nextEpisodeQueuedInStore = false;

        if (webView != null) {
            webView.clearHistory();
        }

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

    private ArrayList<String> extractDirectStreamUrls(List<String> urls) {
        ArrayList<String> direct = new ArrayList<>();
        if (urls == null) {
            return direct;
        }
        for (String url : urls) {
            if (isDirectStreamUrl(url)) {
                direct.add(url.trim());
            }
        }
        return direct;
    }

    private ArrayList<String> extractEmbedUrls(List<String> urls) {
        ArrayList<String> embeds = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.trim().isEmpty() && !isDirectStreamUrl(url)) {
                    embeds.add(url.trim());
                }
            }
        }
        return embeds;
    }

    private boolean isDirectStreamUrl(String url) {
        if (url == null) {
            return false;
        }
        String lc = url.trim().toLowerCase(Locale.ROOT);
        if (lc.contains(".m3u8") || lc.contains(".mpd") || lc.contains(".mp4")) {
            return true;
        }
        try {
            Uri uri = Uri.parse(lc);
            return "pixeldrain.com".equals(uri.getHost())
                    && uri.getPath() != null && uri.getPath().matches("/api/file/[a-z0-9]+/?");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPlaylistStreamUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String lc = url.trim().toLowerCase(Locale.ROOT);
        if (lc.contains("/ad/") || lc.contains("/ads/") || lc.contains("doubleclick")
                || lc.contains("analytics") || lc.contains("turnstile")
                || lc.contains("challenges.cloudflare")) {
            return false;
        }
        return lc.contains(".m3u8") || lc.contains(".mpd");
    }

    private boolean isKnownEmbedVideoHost(String url) {
        if (url == null) {
            return false;
        }
        String lc = url.toLowerCase(Locale.ROOT);
        return lc.contains("minochinos.com")
                || lc.contains("hglink.to")
                || lc.contains("bysedikamoum.com")
                || lc.contains("voe.sx")
                || lc.contains("voe.network")
                || lc.contains("vidhide")
                || lc.contains("streamwish")
                || lc.contains("wishembed")
                || lc.contains("strwish")
                || lc.contains("awish")
                || lc.contains("flaswish")
                || lc.contains("filemoon")
                || lc.contains("filelions")
                || lc.contains("dood")
                || lc.contains("ds2play")
                || lc.contains("waaw")
                || lc.contains("netu.tv")
                || lc.contains("hqq.tv")
                || lc.contains("fastplay")
                || lc.contains("uqload")
                || lc.contains("streamtape")
                || lc.contains("pelistop")
                || lc.contains("embed69")
                || lc.contains("cuevana3")
                || lc.contains("cuevana")
                || lc.contains("allcalidad")
                || lc.contains("pelispedia")
                || lc.contains("delivery-node")
                || lc.contains("vimeos.net")
                || lc.contains("dramiyos-cdn.com");
    }

    private boolean handleUrlNavigation(String url) {
        if (isExiting || isFinishing() || isDestroyed()) {
            return true;
        }
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String lc = url.trim().toLowerCase(Locale.ROOT);

        // Bloquear únicamente esquemas externos como intent:, market:, whatsapp: para no romper la app
        if (!lc.startsWith("http://") && !lc.startsWith("https://") && !lc.startsWith("/") && !lc.startsWith("./")) {
            return true;
        }

        // Si la web intenta navegar fuera del reproductor (a home, detalle, catálogo, series, etc.),
        // consideramos que el usuario quiere salir del reproductor y cerramos limpiamente la pantalla.
        if (isExitNavigation(lc)) {
            Log.d(TAG, "Navegación de salida detectada en WebView: " + url + ". Cerrando reproductor.");
            finishWithResult();
            return true;
        }

        return false;
    }

    private boolean isExitNavigation(String lcUrl) {
        if (lcUrl == null) return false;
        if (currentServerIndex >= 0 && currentServerIndex < serverUrls.size()) {
            String current = serverUrls.get(currentServerIndex);
            if (current != null && !current.isEmpty()) {
                String lcCurrent = current.toLowerCase(Locale.ROOT);
                if (lcUrl.equalsIgnoreCase(lcCurrent) || lcUrl.startsWith(lcCurrent)) {
                    return false;
                }
            }
        }
        // Canela TV
        if (lcUrl.contains("canela.tv")) {
            if (lcUrl.contains("/player/") || lcUrl.contains("/tvepisode/") || lcUrl.contains("/movie/") || lcUrl.contains("/series/")) {
                return false;
            }
            if (lcUrl.equals("https://canela.tv/") || lcUrl.equals("https://www.canela.tv/")
                    || lcUrl.equals("https://canela.tv/es") || lcUrl.equals("https://www.canela.tv/es")
                    || lcUrl.equals("https://canela.tv/es/") || lcUrl.equals("https://www.canela.tv/es/")
                    || lcUrl.contains("/home") || lcUrl.contains("/catalogo") || lcUrl.contains("/search")) {
                return true;
            }
        }
        // Cuevana / AllCalidad / Pelispedia
        if (lcUrl.contains("cuevana") || lcUrl.contains("allcalidad") || lcUrl.contains("pelispedia")) {
            if (lcUrl.endsWith(".to/") || lcUrl.endsWith(".to") || lcUrl.endsWith(".re/") || lcUrl.endsWith(".re")
                    || lcUrl.contains("/inicio") || lcUrl.contains("/categoria") || lcUrl.contains("/genero")) {
                return true;
            }
        }
        return false;
    }

    private String hostOf(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void switchToExoPlayer(String streamUrl) {
        if (isExiting || disableExoSwitch || exoSwitchInProgress || streamUrl == null || streamUrl.trim().isEmpty()) {
            return;
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (lastSavedPositionSec >= 0) {
            savePlaybackProgress(lastSavedPositionSec,
                    contentDurationMs > 0 ? contentDurationMs / 1000.0 : 0);
        }
        exoSwitchInProgress = true;
        ArrayList<String> direct = new ArrayList<>();
        direct.add(streamUrl.trim());
        String title = getTitle() == null ? "Reproduciendo" : String.valueOf(getTitle());
        String embedUrl = currentEmbedUrl();
        String embedReferer = embedUrl != null ? originOf(embedUrl) : null;
        PlayerExoActivity.start(this, direct, title,
                contentId, seriesTitle, imageUrl,
                seriesPostId, seriesPostType,
                currentEpisodeId, currentSeasonNumber, currentEpisodeNumber,
                resumePositionMs, embedUrl, embedReferer,
                seriesSource, seriesDetailUrl);
        resumed = false;
        setPlaybackResult();
        finish();
    }

    private String currentEmbedUrl() {
        if (serverUrls != null && currentServerIndex >= 0 && currentServerIndex < serverUrls.size()) {
            String url = serverUrls.get(currentServerIndex);
            if (url != null && !url.trim().isEmpty()) {
                return url.trim();
            }
        }
        if (lastLoadedEmbedUrl != null && !lastLoadedEmbedUrl.isEmpty()) {
            return lastLoadedEmbedUrl;
        }
        return null;
    }

    private String originOf(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String formatEpisodeCode(int season, int episode) {
        int safeSeason = season > 0 ? season : 1;
        int safeEpisode = episode > 0 ? episode : 1;
        return String.format(Locale.getDefault(), "T%d:E%d", safeSeason, safeEpisode);
    }

    private int parseEpisodeNumber() {
        if (currentEpisodeNumber > 0) return currentEpisodeNumber;
        String[] candidates = { getTitle() != null ? getTitle().toString() : "", seriesDetailUrl };
        for (String s : candidates) {
            if (s == null || s.isEmpty()) continue;
            Matcher m = Pattern.compile("(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                try { return Integer.parseInt(m.group(2)); } catch (Exception ignored) {}
            }
            Matcher m2 = Pattern.compile("(?i)(?:e|ep|episodio|capitulo\\s*)(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m2.find()) {
                try { return Integer.parseInt(m2.group(1)); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    private int parseSeasonNumber() {
        if (currentSeasonNumber > 0) return currentSeasonNumber;
        String[] candidates = { getTitle() != null ? getTitle().toString() : "", seriesDetailUrl };
        for (String s : candidates) {
            if (s == null || s.isEmpty()) continue;
            Matcher m = Pattern.compile("(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
            Matcher m2 = Pattern.compile("(?i)(?:t|s|temporada\\s*)(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m2.find()) {
                try { return Integer.parseInt(m2.group(1)); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    private void preloadSeasonData() {
        if (!isSeriesEpisodePlayback() || loadingSeasonData || seasonPreloadAttempted) {
            return;
        }
        seasonPreloadAttempted = true;
        loadingSeasonData = true;

        // 1) Intentar recuperar de memoria instantáneamente (0 ms)
        List<AllCalidadScraper.Season> memorySeasons = sv.edu.catolica.rex.network.smart.SeriesSeasonsCache.get(
                seriesTitle, seriesPostId, seriesDetailUrl);
        if (memorySeasons != null && !memorySeasons.isEmpty()) {
            loadingSeasonData = false;
            seasonCache.clear();
            seasonCache.addAll(memorySeasons);
            refreshNextEpisodeFromCache();
            return;
        }

        // 2) Si no estaba en memoria, consultar en background según la fuente
        new Thread(() -> {
            try {
                List<AllCalidadScraper.Season> fetchedSeasons = null;
                String source = seriesSource != null ? seriesSource.trim().toLowerCase(Locale.ROOT) : "";
                boolean isNovelas = source.contains("novelas") || source.contains("canela")
                        || (seriesDetailUrl != null && seriesDetailUrl.contains("canela.tv"));

                if (isNovelas) {
                    try {
                        String query = (seriesDetailUrl != null && !seriesDetailUrl.isEmpty()) ? seriesDetailUrl : seriesTitle;
                        fetchedSeasons = new sv.edu.catolica.rex.network.smart.CanelaScraper().getSeasons(query);
                    } catch (Exception ignored) {}
                }

                boolean isCuevana = !isNovelas && (source.contains("cuevana")
                        || (seriesDetailUrl != null && seriesDetailUrl.contains("cuevana"))
                        || (seriesPostId <= 0));

                if (isCuevana) {
                    try {
                        String query = (seriesDetailUrl != null && !seriesDetailUrl.isEmpty()) ? seriesDetailUrl : seriesTitle;
                        fetchedSeasons = new sv.edu.catolica.rex.network.smart.Cuevana3Scraper().getSeasons(query);
                    } catch (Exception ignored) {}
                }

                if ((fetchedSeasons == null || fetchedSeasons.isEmpty()) && seriesPostId > 0) {
                    try {
                        String postType = (seriesPostType == null || seriesPostType.trim().isEmpty()) ? "tvshows" : seriesPostType;
                        AllCalidadScraper.hit(seriesPostId, postType);
                        fetchedSeasons = AllCalidadScraper.getSeasons(seriesPostId);
                    } catch (Exception ignored) {}
                }

                if ((fetchedSeasons == null || fetchedSeasons.isEmpty()) && seriesTitle != null && !seriesTitle.trim().isEmpty()) {
                    try {
                        fetchedSeasons = new sv.edu.catolica.rex.network.smart.Cuevana3Scraper().getSeasons(seriesTitle);
                    } catch (Exception ignored) {}
                }

                final List<AllCalidadScraper.Season> resultSeasons = fetchedSeasons;
                runOnUiThread(() -> {
                    loadingSeasonData = false;
                    seasonCache.clear();
                    if (resultSeasons != null && !resultSeasons.isEmpty()) {
                        seasonCache.addAll(resultSeasons);
                        sv.edu.catolica.rex.network.smart.SeriesSeasonsCache.put(
                                seriesTitle, seriesPostId, seriesDetailUrl, resultSeasons);
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
        int effectiveSeason = parseSeasonNumber();
        int effectiveEpisode = parseEpisodeNumber();

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
                if (episode == null) {
                    continue;
                }
                int episodeNumber = episode.episodeNumber > 0 ? episode.episodeNumber : 1;
                int epId = episode.id > 0 ? episode.id : Math.abs((seasonNumber + "x" + episodeNumber).hashCode() % 1000000) + 100000;
                episode.id = epId;

                if (foundCurrent) {
                    return new NextEpisodeInfo(epId, seasonNumber, episodeNumber, episode.title);
                }

                if ((seasonNumber == effectiveSeason && episodeNumber == effectiveEpisode)
                        || (currentEpisodeId > 0 && epId == currentEpisodeId)) {
                    currentSeasonNumber = seasonNumber;
                    currentEpisodeNumber = episodeNumber;
                    foundCurrent = true;
                }
            }
        }

        if (foundCurrent) {
            return null;
        }

        for (AllCalidadScraper.Season season : seasonCache) {
            if (season == null || season.episodes == null) {
                continue;
            }
            int seasonNumber = season.seasonNumber > 0 ? season.seasonNumber : 1;
            for (AllCalidadScraper.Episode episode : season.episodes) {
                if (episode == null) {
                    continue;
                }
                int episodeNumber = episode.episodeNumber > 0 ? episode.episodeNumber : 1;
                int epId = episode.id > 0 ? episode.id : Math.abs((seasonNumber + "x" + episodeNumber).hashCode() % 1000000) + 100000;
                episode.id = epId;

                boolean comesAfterCurrent =
                        seasonNumber > effectiveSeason
                                || (seasonNumber == effectiveSeason && episodeNumber > effectiveEpisode);
                if (comesAfterCurrent) {
                    return new NextEpisodeInfo(epId, seasonNumber, episodeNumber, episode.title);
                }
            }
        }

        return null;
    }
    private void savePlaybackProgress(double currentTimeSec, double durationSec) {
        savePlaybackProgress(currentTimeSec, durationSec, false);
    }

    private void savePlaybackProgress(double currentTimeSec, double durationSec, boolean syncToRemote) {
        if (contentId == null || contentId.isEmpty() || currentTimeSec < 0) return;

        contentDurationMs = (long) (durationSec * 1000);
        long positionMs = (long) (currentTimeSec * 1000);

        if (!syncToRemote && Math.abs(currentTimeSec - lastSavedPositionSec) < 8.0) return;
        lastSavedPositionSec = currentTimeSec;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean sendRemote = syncToRemote || now - lastRemoteSaveElapsedMs >= 30000L;
        if (sendRemote) lastRemoteSaveElapsedMs = now;

        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setContentId(contentId);
        item.setTitle(getTitle() != null ? getTitle().toString() : "");
        item.setSeriesTitle(seriesTitle);
        item.setImageUrl(imageUrl);
        item.setPostId(seriesPostId > 0 ? seriesPostId : (currentEpisodeId > 0 ? currentEpisodeId : 0));
        item.setDetailUrl(seriesDetailUrl);
        item.setSource(seriesSource);
        if (isSeriesEpisodePlayback()) {
            item.setEpisodeId(currentEpisodeId);
            item.setSeasonNumber(currentSeasonNumber);
            item.setEpisodeNumber(currentEpisodeNumber);
            item.setMediaType(seriesPostType != null && !seriesPostType.isEmpty() ? seriesPostType : "tvshows");
        } else {
            item.setMediaType("movies");
        }
        ContinueWatchingStore.applyPlaybackProgress(item, positionMs, contentDurationMs);
        boolean isNowCompleted = item.isCompleted();

        final ContinueWatchingItem itemToSave = item;
        final boolean shouldQueueNext = isNowCompleted && isSeriesEpisodePlayback()
                && !nextEpisodeQueuedInStore
                && ContinueWatchingStore.resolveNextEpisodeItem(itemToSave) != null;
        if (shouldQueueNext) {
            nextEpisodeQueuedInStore = true;
        }

        saveExecutor.execute(() -> {
            try {
                if (shouldQueueNext) {
                    ContinueWatchingStore.save(PlayerContenidoActivity.this, itemToSave);
                    ContinueWatchingStore.queueNextEpisodeIfAvailable(PlayerContenidoActivity.this, itemToSave);
                } else if (!nextEpisodeQueuedInStore) {
                    if (sendRemote) {
                        ContinueWatchingStore.save(PlayerContenidoActivity.this, itemToSave);
                    } else {
                        ContinueWatchingStore.saveLocal(PlayerContenidoActivity.this, itemToSave);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void schedulePositionRestore() {
        if (isTuDoramaSource()) return;
        if (isExiting || resumePositionMs <= 0 || positionRestored) return;
        final int[] retries = {0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isExiting || webView == null || isFinishing() || isDestroyed()) return;
                double targetSec = resumePositionMs / 1000.0;
                String js = "(function(){"
                        + "try{"
                        + "var v=document.querySelector('video');"
                        + "if(v&&isFinite(v.duration)&&v.duration>0){"
                        + "  v.currentTime=" + targetSec + ";"
                        + "  return true;"
                        + "}"
                        + "if(window.jwplayer){"
                        + "  var jw=window.jwplayer();"
                        + "  if(jw&&jw.seek&&jw.getState&&jw.getState()!=='idle'){"
                        + "    jw.seek(" + targetSec + ");"
                        + "    return true;"
                        + "  }"
                        + "}"
                        + "return false;"
                        + "}catch(e){return false;}"
                        + "})();";
                webView.evaluateJavascript(js, result -> {
                    if ("true".equals(result)) {
                        positionRestored = true;
                    } else if (retries[0] < SEEK_MAX_RETRIES) {
                        retries[0]++;
                        handler.postDelayed(this, SEEK_RETRY_DELAY_MS);
                    }
                });
            }
        }, 1500L);
    }

    private String buildContentId() {
        String passedContentId = getIntent().getStringExtra("exo_content_id");
        if (passedContentId != null && !passedContentId.trim().isEmpty()) {
            return passedContentId.trim();
        }
        if (isSeriesEpisodePlayback() && currentEpisodeId > 0) {
            return seriesPostId + "_" + currentEpisodeId;
        }
        if (currentEpisodeId > 0) {
            return "ep_" + currentEpisodeId;
        }
        if (seriesPostId > 0) {
            return "post_" + seriesPostId;
        }
        if (seriesDetailUrl != null && !seriesDetailUrl.trim().isEmpty()) {
            return "url_" + Math.abs(seriesDetailUrl.trim().hashCode());
        }
        if (seriesTitle != null && !seriesTitle.trim().isEmpty()) {
            return "title_" + Math.abs(seriesTitle.trim().toLowerCase(Locale.ROOT).hashCode());
        }
        return "";
    }

    private void loadCurrentServer() {
        if (isExiting || isFinishing() || isDestroyed()) {
            return;
        }
        if (currentServerIndex < 0 || currentServerIndex >= serverUrls.size()) {
            onAllServersFailed();
            return;
        }
        failoverInProgress = false;
        pageLoaded = false;
        lastLoadedEmbedUrl = "";
        cancelPlaybackWatchdog();
        String url = serverUrls.get(currentServerIndex);
        if (url != null && url.contains("canela.tv")) {
            preconfigureCookiesForCanela();
        }
        if (currentServerIndex == 0 && currentServerRetry == 0) {
            showPlaybackStatus("Connecting...");
        } else if (currentServerRetry > 0) {
            showPlaybackStatus("Loading playback...");
        } else {
            showPlaybackStatus("Testing alternate server...");
        }
        Map<String, String> headers = buildPlaybackHeaders(url);
        if (headers.isEmpty()) {
            webView.loadUrl(url);
        } else {
            webView.loadUrl(url, headers);
        }
    }

    private Map<String, String> buildPlaybackHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        if (url == null) {
            return headers;
        }

        String lc = url.trim().toLowerCase(Locale.ROOT);
        if (lc.contains("canela.tv")) {
            headers.put("Referer", "https://canela.tv/");
            headers.put("Origin", "https://canela.tv");
        }
        if (lc.contains("vimeos.net") || lc.contains("s12.vimeos.net")) {
            headers.put("Referer", "https://lamovie.link/");
            headers.put("Origin", "https://lamovie.link");
        }
        if (lc.contains("minochinos.com")
                || lc.contains("hglink.to")
                || lc.contains("bysedikamoum.com")
                || lc.contains("voe.sx")
                || lc.contains("voe.network")) {
            headers.put("Referer", "https://allcalidad.re/");
            headers.put("Origin", "https://allcalidad.re");
        }
        if (lc.contains("cuevana")
                || lc.contains("streamwish")
                || lc.contains("wishembed")
                || lc.contains("strwish")
                || lc.contains("filemoon")
                || lc.contains("filelions")
                || lc.contains("bysejikuar")
                || lc.contains("vidhide")
                || lc.contains("vidhidepro")
                || lc.contains("waaw")) {
            headers.put("Referer", "https://wv3.cuevana3.eu/");
            headers.put("Origin", "https://wv3.cuevana3.eu");
        }
        return headers;
    }

    private void scheduleServerTimeout() {
        cancelServerTimeout();
        if (isExiting || isFinishing() || isDestroyed()) return;
        timeoutRunnable = () -> {
            if (isExiting || isFinishing() || isDestroyed()) return;
            if (!pageLoaded) {
                handleServerFailure();
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

    private void schedulePlaybackWatchdog() {
        cancelPlaybackWatchdog();
        if (isExiting || isFinishing() || isDestroyed()) return;
        if (serverUrls != null && serverUrls.size() > 1 && (currentServerIndex + 1 < serverUrls.size())) {
            playbackWatchdogRunnable = () -> {
                if (isExiting || isFinishing() || isDestroyed()) return;
                if (!isVideoPlaying && lastVideoDurationMs <= 0) {
                    if (webView != null) {
                        webView.evaluateJavascript(
                            "(function(){try{"
                            + "if(window.jwplayer){var jw=window.jwplayer('vplayer')||window.jwplayer();if(jw&&typeof jw.getState==='function'){var s=jw.getState();if(s==='playing'||s==='buffering')return 'active';}}"
                            + "var v=document.querySelector('video');if(v&&(v.readyState>=1||v.currentTime>0||!v.paused))return 'active';"
                            + "var ifrs=document.querySelectorAll('iframe');for(var i=0;i<ifrs.length;i++){try{var doc=ifrs[i].contentDocument||ifrs[i].contentWindow.document;var iv=doc.querySelector('video');if(iv&&(iv.readyState>=1||iv.currentTime>0||!iv.paused))return 'active';}catch(e){}}"
                            + "return 'idle';"
                            + "}catch(e){return 'idle';}})();",
                            res -> {
                                if (isExiting || isFinishing() || isDestroyed()) return;
                                if (res != null && res.contains("active")) {
                                    Log.d(TAG, "Watchdog: player está activo/buffering, aplazando failover.");
                                    handler.postDelayed(playbackWatchdogRunnable, isTvDevice ? 5000L : 10000L);
                                    return;
                                }
                                Log.w(TAG, "Playback did not start within watchdog window. Failing over to next server.");
                                handleServerFailure();
                            }
                        );
                    } else {
                        handleServerFailure();
                    }
                }
            };
            long delay = isTvDevice ? 7500L : PLAYBACK_WATCHDOG_MS;
            handler.postDelayed(playbackWatchdogRunnable, delay);
        }
    }

    private void cancelPlaybackWatchdog() {
        if (playbackWatchdogRunnable != null) {
            handler.removeCallbacks(playbackWatchdogRunnable);
            playbackWatchdogRunnable = null;
        }
    }

    private void handleServerFailure() {
        if (isExiting || isFinishing() || isDestroyed()) {
            return;
        }
        if (failoverInProgress) {
            return;
        }
        failoverInProgress = true;
        cancelServerTimeout();
        cancelPlaybackWatchdog();

        if (currentServerRetry < MAX_RETRIES_PER_SERVER) {
            currentServerRetry++;
            showPlaybackStatus("Loading playback...");
            handler.postDelayed(() -> {
                failoverInProgress = false;
                loadCurrentServer();
            }, RETRY_DELAY_MS);
            return;
        }

        if (currentServerIndex + 1 < serverUrls.size()) {
            currentServerIndex++;
            currentServerRetry = 0;
            showPlaybackStatus("Testing alternate server...");
            failoverInProgress = false;
            loadCurrentServer();
            return;
        }

        if (isTransitioningToNextEpisode) {
            isTransitioningToNextEpisode = false;
            hideNextEpisodeLoader();
        }
        failoverInProgress = false;
        onAllServersFailed();
    }

    private void onAllServersFailed() {
        hidePlaybackStatus();
        progressBar.setVisibility(View.GONE);
        Toast.makeText(this, "No se pudo reproducir con los servidores disponibles", Toast.LENGTH_LONG).show();
    }

    private void applyJwFullscreenFix(String pageUrl) {
        if (webView == null || !isJwPlayerPage(pageUrl)) {
            return;
        }

        String js = "(function(){"
                + "try{"
                + "document.documentElement.style.cssText='margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;';"
                + "document.body.style.cssText='margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;';"
                + "var style=document.getElementById('__rex_jw_fullscreen_style');"
                + "if(!style){"
                + "  style=document.createElement('style');"
                + "  style.id='__rex_jw_fullscreen_style';"
                + "  style.textContent='html,body,#player,#vplayer,.jwplayer,.jw-wrapper,.jw-media,.jw-video,.jw-aspect{width:100% !important;height:100% !important;max-width:none !important;max-height:none !important;} video{width:100% !important;height:100% !important;object-fit:contain !important;background:#000 !important;}';"
                + "  document.head.appendChild(style);"
                + "}"
                + "if(window.jwplayer){"
                + "  var jw=window.jwplayer('vplayer')||window.jwplayer();"
                + "  if(jw){"
                + "    try{if(jw.setMute)jw.setMute(true);}catch(e){}"
                + "    try{if(jw.play)jw.play(true);}catch(e){}"
                + "    try{if(jw.on){jw.on('ready',function(){try{if(jw.setMute)jw.setMute(true);if(jw.play)jw.play(true);}catch(e){}});}}catch(e){}"
                + "  }"
                + "}"
                + "var videos=document.querySelectorAll('video');"
                + "for(var i=0;i<videos.length;i++){"
                + "  try{videos[i].muted=true;videos[i].playsInline=true;var p=videos[i].play();if(p&&p.catch)p.catch(function(){});}catch(e){}"
                + "}"
                + "}catch(e){}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean isJwPlayerPage(String url) {
        if (url == null) {
            return false;
        }
        String lc = url.toLowerCase(Locale.ROOT);
        return lc.contains("cdn.jwplayer.com")
                || lc.contains("jwplayer.com")
                || lc.contains("minochinos.com")
                || lc.contains("hglink.to")
                || lc.contains("bysedikamoum.com")
                || lc.contains("vidhide")
                || lc.contains("vimeos.net")
                || lc.contains("streamwish")
                || lc.contains("wishembed")
                || lc.contains("filemoon");
    }

    private void finishWithResult() {
        if (isExiting) return;
        isExiting = true;
        resumed = false;

        handler.removeCallbacksAndMessages(null);
        cancelServerTimeout();
        cancelPlaybackWatchdog();
        cancelAutoHide();
        hideSkipIntroButton(true);
        hideNextEpisodeButton(true);

        if (lastSavedPositionSec >= 0) {
            savePlaybackProgress(lastSavedPositionSec,
                    contentDurationMs > 0 ? contentDurationMs / 1000.0 : 0, true);
        }
        setPlaybackResult();

        if (webView != null) {
            try {
                webView.evaluateJavascript(
                    "(function(){try{"
                    + "var vids=document.querySelectorAll('video,audio');"
                    + "for(var i=0;i<vids.length;i++){vids[i].pause();vids[i].src='';vids[i].load();}"
                    + "if(window.jwplayer){try{window.jwplayer().stop();}catch(e){}}"
                    + "}catch(e){}})();", null);
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.onPause();
                webView.setVisibility(View.GONE);
            } catch (Exception ignored) {}
        }

        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private void setPlaybackResult() {
        Intent data = new Intent();
        data.putExtra(RESULT_EPISODE_ID, currentEpisodeId);
        data.putExtra(RESULT_SEASON_NUMBER, currentSeasonNumber);
        data.putExtra(RESULT_EPISODE_NUMBER, currentEpisodeNumber);
        setResult(RESULT_OK, data);
    }

    @Override
    public void onBackPressed() {
        if (isExiting) return;
        if (customView != null) {
            FrameLayout decorView = (FrameLayout) getWindow().getDecorView();
            decorView.removeView(customView);
            customView = null;
            if (webView != null) {
                webView.setVisibility(View.VISIBLE);
            }
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            enterImmersiveMode();
            return;
        }
        finishWithResult();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event == null || isExiting || isFinishing() || isDestroyed()) {
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (controlsVisible) {
                    hideControls();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            if (nextButtonVisible && nextEpisodeButton != null && nextEpisodeButton.getVisibility() == View.VISIBLE && nextEpisodeButton.hasFocus()) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    playNextEpisode(false);
                    return true;
                }
            }

            if (skipIntroVisible && skipIntroButton != null && skipIntroButton.getVisibility() == View.VISIBLE && skipIntroButton.hasFocus()) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    skipIntro();
                    return true;
                }
            }

            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case android.view.KeyEvent.KEYCODE_HEADSETHOOK:
                    toggleWebViewPlayback();
                    if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (!isVideoPlaying) {
                        requestAutoPlay();
                    }
                    toggleWebViewPlayback();
                    if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                    toggleWebViewPlayback();
                    if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    webViewSkip(10000);
                    if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
                    if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                    webViewSkip(-10000);
                    if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
                    if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                    return true;

                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!controlsVisible) {
                        if (!isVideoPlaying) {
                            requestAutoPlay();
                            toggleWebViewPlayback();
                        } else {
                            showControls();
                        }
                    } else {
                        View focused = getCurrentFocus();
                        if (focused != null && focused != controlOverlay && (focused.isClickable() || focused instanceof android.widget.Button || focused instanceof android.widget.ImageButton)) {
                            focused.performClick();
                        } else {
                            toggleWebViewPlayback();
                        }
                    }
                    if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                    return true;

                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!controlsVisible) {
                        webViewSkip(-10000);
                        if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
                        if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                        return true;
                    } else {
                        View focused = getCurrentFocus();
                        if (focused instanceof SeekBar) {
                            webViewSkip(-10000);
                            if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
                            if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                            return true;
                        }
                        resetAutoHide();
                        break;
                    }

                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!controlsVisible) {
                        webViewSkip(10000);
                        if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
                        if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                        return true;
                    } else {
                        View focused = getCurrentFocus();
                        if (focused instanceof SeekBar) {
                            webViewSkip(10000);
                            if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
                            if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                            return true;
                        }
                        resetAutoHide();
                        break;
                    }

                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!controlsVisible) {
                        showControls();
                        if (isTvDevice && isVideoPlaying) scheduleTvControlsHide();
                        return true;
                    } else {
                        resetAutoHide();
                        break;
                    }
            }
        }

        if (isTvDevice && pageLoaded && !isExiting && isVideoPlaying) {
            scheduleTvControlsHide();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        isExiting = true;
        if (doubleTapHelper != null) {
            doubleTapHelper.onDestroy();
        }
        handler.removeCallbacksAndMessages(null);
        cancelServerTimeout();
        cancelPlaybackWatchdog();
        cancelAutoHide();
        hideSkipIntroButton(true);
        hideNextEpisodeButton(true);
        if (lastSavedPositionSec >= 0) {
            savePlaybackProgress(lastSavedPositionSec,
                    contentDurationMs > 0 ? contentDurationMs / 1000.0 : 0, true);
        }
        setPlaybackResult();
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                android.view.ViewParent parent = webView.getParent();
                if (parent instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) parent).removeView(webView);
                }
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        saveExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        cancelPlaybackWatchdog();
        handler.removeCallbacks(playbackMonitorRunnable);
        handler.removeCallbacks(tvHideControlsRunnable);
        cancelAutoHide();
        if (lastSavedPositionSec >= 0) {
            savePlaybackProgress(lastSavedPositionSec,
                    contentDurationMs > 0 ? contentDurationMs / 1000.0 : 0, true);
        }
        if (webView != null) {
            sendDoramaFrameCommand("pause", 0);
            webView.evaluateJavascript(
                    "(function(){try{"
                    + "if(window.jwplayer){try{var jw=window.jwplayer('vplayer')||window.jwplayer();if(jw&&jw.pause)jw.pause(true);}catch(e){}}"
                    + "var media=document.querySelectorAll('video,audio');"
                    + "for(var i=0;i<media.length;i++){try{media[i].pause();}catch(e){}}"
                    + "}catch(e){}})();", null);
            webView.onPause();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initControls() {
        controlOverlay = findViewById(R.id.control_overlay);
        centerControls = findViewById(R.id.center_controls);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        btnBack = findViewById(R.id.btn_back);
        btnPlayCenter = findViewById(R.id.btn_play_center);
        btnSkipBack = findViewById(R.id.btn_skip_back);
        btnSkipForward = findViewById(R.id.btn_skip_forward);
        seekBar = findViewById(R.id.player_seekbar);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvDuration = findViewById(R.id.tv_duration);
        tvTitle = findViewById(R.id.tv_player_title);

        tvTitle.setText(resolveDisplayTitle());

        findViewById(R.id.btn_quality).setVisibility(View.GONE);
        findViewById(R.id.btn_subtitles).setVisibility(View.GONE);
        findViewById(R.id.btn_audio).setVisibility(View.GONE);
        findViewById(R.id.btn_fullscreen).setVisibility(View.GONE);
        findViewById(R.id.btn_lock).setVisibility(View.GONE);
        findViewById(R.id.btn_cast).setVisibility(View.GONE);

        controlOverlay.setVisibility(View.VISIBLE);
        controlOverlay.setAlpha(0f);
        controlOverlay.setOnClickListener(v -> toggleControls());

        btnBack.setOnClickListener(v -> finishWithResult());
        btnPlayCenter.setOnClickListener(v -> toggleWebViewPlayback());
        btnSkipBack.setOnClickListener(v -> {
            webViewSkip(-10000);
            if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
        });
        btnSkipForward.setOnClickListener(v -> {
            webViewSkip(10000);
            if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
        });

        doubleTapHelper = new PlayerDoubleTapHelper(this);
        doubleTapHelper.initViews(
                btnSkipBack,
                btnSkipForward,
                deltaMs -> webViewSkip(deltaMs),
                () -> toggleControls(),
                () -> controlsVisible,
                () -> resetAutoHide()
        );

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean fromUser = false;
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                this.fromUser = fromUser;
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekBarDragging = true;
                fromUser = true;
                cancelAutoHide();
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBarDragging = false;
                if (fromUser && lastVideoDurationMs > 0) {
                    long seekPos = lastVideoDurationMs * seekBar.getProgress() / seekBar.getMax();
                    webViewSeekTo(seekPos);
                }
                fromUser = false;
                resetAutoHide();
            }
        });

        // Tap en cualquier punto de la barra = saltar a esa posición (sin arrastrar).
        // Se detecta por umbral de movimiento (touch slop), no por seekBarDragging,
        // para que el primer toque siempre adelante/atrase.
        final float touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        seekBar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    seekDownX = event.getX();
                    break;
                case MotionEvent.ACTION_UP:
                    if (seekDownX >= 0 && Math.abs(event.getX() - seekDownX) < touchSlop) {
                        SeekBar sb = (SeekBar) v;
                        int available = sb.getWidth() - sb.getPaddingLeft() - sb.getPaddingRight();
                        if (available > 0) {
                            float fraction = Math.max(0f, Math.min(1f,
                                    (event.getX() - sb.getPaddingLeft()) / available));
                            sb.setProgress(Math.round(fraction * sb.getMax()));
                            if (lastVideoDurationMs > 0) {
                                webViewSeekTo(Math.round(fraction * lastVideoDurationMs));
                            }
                            resetAutoHide();
                        }
                    }
                    seekDownX = -1f;
                    break;
            }
            return false;
        });
    }

    private String resolveDisplayTitle() {
        if (seriesTitle != null && !seriesTitle.trim().isEmpty() && !"Reproduciendo".equalsIgnoreCase(seriesTitle.trim()) && !"REX".equalsIgnoreCase(seriesTitle.trim())) {
            if (currentSeasonNumber > 0 && currentEpisodeNumber > 0) {
                return String.format(Locale.US, "%s T%d:E%d", seriesTitle.trim(), currentSeasonNumber, currentEpisodeNumber);
            }
            return seriesTitle.trim();
        }
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        if (t == null || t.trim().isEmpty()) {
            t = getIntent().getStringExtra("title");
        }
        if (t != null && !t.trim().isEmpty() && !"REX".equalsIgnoreCase(t.trim()) && !"Reproduciendo".equalsIgnoreCase(t.trim())) {
            return t.trim();
        }
        return "";
    }

    private void toggleWebViewPlayback() {
        if (webView == null) return;
        if (sendDoramaFrameCommand("toggle", 0)) {
            resetAutoHide();
            return;
        }
        webView.evaluateJavascript(
            "(function(){try{"
            + "if(window.jwplayer){"
            + "  var jw=window.jwplayer('vplayer')||window.jwplayer();"
            + "  if(jw&&typeof jw.getState==='function'){"
            + "    var st=jw.getState();"
            + "    if(st==='playing'){"
            + "      if(jw.pause)jw.pause(true);"
            + "      return false;"
            + "    }else{"
            + "      if(jw.setMute)jw.setMute(false);"
            + "      if(jw.setVolume)jw.setVolume(100);"
            + "      if(jw.play)jw.play(true);"
            + "      return true;"
            + "    }"
            + "  }"
            + "}"
            + "var v=document.querySelector('video');"
            + "if(v){"
            + "  if(v.paused){"
            + "    v.muted=false;v.volume=1;"
            + "    var p=v.play();"
            + "    if(p&&p.catch)p.catch(function(){});"
            + "    return true;"
            + "  }else{"
            + "    v.pause();"
            + "    return false;"
            + "  }"
            + "}"
            + "var playBtns=document.querySelectorAll('.vjs-big-play-button,.play-wrapper,.play-icon,#os_player .play-icon,#player-overlay,.plyr__control--overlaid,.fluid_initial_play,button.play,[aria-label=\"Play\"],.jw-display-icon-container');"
            + "for(var b=0;b<playBtns.length;b++){try{playBtns[b].click();}catch(e){}}"
            + "return false;"
            + "}catch(e){return false;}})();",
            result -> {
                boolean playing = "true".equals(result);
                updatePlayPauseIcons(playing);
            });
        resetAutoHide();
    }

    private void webViewSkip(long deltaMs) {
        if (webView == null) return;
        double deltaSec = deltaMs / 1000.0;
        if (sendDoramaFrameCommand("skip", deltaSec)) {
            resetAutoHide();
            return;
        }
        webView.evaluateJavascript(
            "(function(){try{"
            + "if(window.jwplayer){"
            + "  var jw=window.jwplayer('vplayer')||window.jwplayer();"
            + "  if(jw&&typeof jw.getPosition==='function'){"
            + "    var pos=jw.getPosition()+" + deltaSec + ";"
            + "    var dur=(typeof jw.getDuration==='function')?jw.getDuration():0;"
            + "    if(dur>0)pos=Math.min(dur,Math.max(0,pos));"
            + "    if(jw.seek)jw.seek(pos);"
            + "  }"
            + "}"
            + "var v=document.querySelector('video');"
            + "if(v){"
            + "  var cur=v.currentTime||0;"
            + "  var target=cur+" + deltaSec + ";"
            + "  if(target<0)target=0;"
            + "  if(v.duration&&isFinite(v.duration)&&v.duration>0&&target>v.duration){target=v.duration;}"
            + "  v.currentTime=target;"
            + "  return true;"
            + "}"
            + "return false;"
            + "}catch(e){return false;}})();",
            null);
        resetAutoHide();
    }

    private void webViewSeekTo(long positionMs) {
        if (webView == null) return;
        double targetSec = positionMs / 1000.0;
        if (sendDoramaFrameCommand("seek", targetSec)) {
            resetAutoHide();
            return;
        }
        webView.evaluateJavascript(
            "(function(){try{"
            + "if(window.jwplayer){"
            + "  var jw=window.jwplayer('vplayer')||window.jwplayer();"
            + "  if(jw&&typeof jw.seek==='function')jw.seek(" + targetSec + ");"
            + "}"
            + "var v=document.querySelector('video');"
            + "if(v&&isFinite(v.duration)&&v.duration>0){"
            + "  v.currentTime=" + targetSec + ";"
            + "  return true;"
            + "}"
            + "return false;"
            + "}catch(e){return false;}})();",
            null);
    }

    private void updateOverlayState(double currentTimeSec, double durationSec, boolean isPaused) {
        if (controlOverlay == null) return;
        lastVideoDurationMs = (long) (durationSec * 1000);

        // Solo actualizar textos/seekbar cuando los controles están visibles para
        // evitar trabajo de UI inútil mientras el video se reproduce a pantalla completa.
        if (controlsVisible) {
            long posMs = (long) (currentTimeSec * 1000);
            long durMs = (long) (durationSec * 1000);
            tvCurrentTime.setText(formatTime(posMs));
            tvDuration.setText(formatTime(durMs));
            if (seekBar != null && durMs > 0) {
                int progress = (int) (posMs * seekBar.getMax() / durMs);
                seekBar.setProgress(progress);
            }
        }

        updatePlayPauseIcons(!isPaused);
    }

    private void updatePlayPauseIcons(boolean playing) {
        if (btnPlayCenter == null) return;
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        btnPlayCenter.setImageResource(icon);
    }

    private String formatTime(long ms) {
        if (ms <= 0) return "0:00";
        int totalSec = (int) (ms / 1000);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private void showControls() {
        if (controlOverlay == null) return;
        controlsVisible = true;
        float slide = 10f * getResources().getDisplayMetrics().density;

        controlOverlay.setVisibility(View.VISIBLE);
        controlOverlay.setAlpha(0f);
        controlOverlay.animate().alpha(1f).setDuration(220).start();

        if (topBar != null) {
            topBar.setVisibility(View.VISIBLE);
            topBar.setTranslationY(-slide);
            topBar.setAlpha(0f);
            topBar.animate().translationY(0f).alpha(1f).setDuration(220).start();
        }
        if (bottomBar != null) {
            bottomBar.setVisibility(View.VISIBLE);
            bottomBar.setTranslationY(slide);
            bottomBar.setAlpha(0f);
            bottomBar.animate().translationY(0f).alpha(1f).setDuration(220).start();
        }
        if (centerControls != null) {
            centerControls.setVisibility(View.VISIBLE);
            centerControls.setScaleX(0.92f);
            centerControls.setScaleY(0.92f);
            centerControls.setAlpha(0f);
            centerControls.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start();
        }

        cancelAutoHide();
        resetAutoHide();

        // Refrescar tiempos/seekbar al mostrar controles (el monitor no los
        // actualiza mientras están ocultos).
        if (pageLoaded && !isTransitioningToNextEpisode) {
            handler.postDelayed(this::inspectPlaybackState, 60L);
        }

        if (isTvDevice && btnPlayCenter != null) {
            btnPlayCenter.post(() -> {
                if (controlsVisible) {
                    btnPlayCenter.requestFocus();
                }
            });
        }
    }

    private void hideControls() {
        if (controlOverlay == null) return;
        controlsVisible = false;
        controlOverlay.animate()
                .alpha(0f)
                .setDuration(280)
                .withEndAction(() -> {
                    if (!controlsVisible) controlOverlay.setVisibility(View.GONE);
                })
                .start();
    }

    private void toggleControls() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private final Runnable autoHideRunnable = () -> {
        if (controlsVisible) hideControls();
    };

    private void resetAutoHide() {
        cancelAutoHide();
        if (controlsVisible) {
            handler.postDelayed(autoHideRunnable, 3000L);
        }
    }

    private void cancelAutoHide() {
        handler.removeCallbacks(autoHideRunnable);
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
            triggerAutoPlayAttempts();
            startPlaybackMonitor();
            if (isTvDevice) {
                scheduleTvControlsHide();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (doubleTapHelper != null && doubleTapHelper.dispatchTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void enterImmersiveMode() {
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(android.graphics.Color.BLACK);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
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

    private void injectHideVideoControls() {
        if (webView == null) return;
        final boolean playing = isVideoPlaying;
        webView.evaluateJavascript(
            "(function(){"
            + "try{"
            + "var styleId='rex-hide-all-controls';"
            + "var s=document.getElementById(styleId);"
            + "if(!s){"
            + "  s=document.createElement('style');"
            + "  s.id=styleId;"
            + "  (document.head||document.documentElement).appendChild(s);"
            + "}else if(s.parentElement){"
            + "  s.parentElement.appendChild(s);"
            + "}"
            + "s.textContent="
            + "  'video::-webkit-media-controls{display:none!important}'"
            + "  +'video::-webkit-media-controls-enclosure{display:none!important}'"
            + "  +'video::-webkit-media-controls-panel{display:none!important}'"
            + "  +'.jw-controlbar,.jw-controls-backdrop,.jw-title,.jw-logo,.jw-dock-buttons{display:none!important}'"
            + "  +'.jw-nextup-container,.jw-info-container,.jw-featured,.jw-rightclick{display:none!important}'"
            + "  +'.vjs-control-bar{display:none!important}'"
            + "  +'.media-control-layer,.fluid_controls_container,.art-bottom,.art-control,.fp-controls{display:none!important}'"
            + "  +'app-player-control,app-player-seekbar,app-player-volume,app-player-meta-data,app-player-header,app-player-bottom,.player-controls,.player-bottom,.player-header,.player-top,button[aria-label*=\"back\" i],button[aria-label*=\"volver\" i],[class*=\"back-button\"],[class*=\"btn-back\"],[class*=\"back_button\"]{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important}'"
            + "  +'.player-skip-options{visibility:hidden!important;opacity:0!important;pointer-events:none!important}'"
            + "  +'#checkresume_div,#checkresume_div_n,.checkresume,.checkresumearea,.checkresumetop,.checkresumebottom{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;z-index:-9999!important}'"
            + "  +'.jw-modal,.jw-dialog,.jw-resume,.jw-resume-prompt,.jw-resume-dialog,.vjs-resume-modal,.vjs-resume-dialog,.vjs-modal-dialog,[class*=\"resume-dialog\"],[class*=\"resume-modal\"]{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;z-index:-9999!important}'"
            + "  +'#onetrust-consent-sdk,#onetrust-banner-sdk,.onetrust-pc-dark-filter,.ot-sdk-container,#onetrust-style,[id*=\"onetrust\"],[class*=\"onetrust\"],div[id*=\"consent\"],div[class*=\"consent\"],div[class*=\"cookie-banner\"]{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;z-index:-999999!important;width:0!important;height:0!important;max-height:0!important;overflow:hidden!important}'"
            + (playing ? "  +'.jw-display-controls,.jw-display-icon-container,.jw-controls,.vjs-big-play-button,.plyr__control--overlaid{display:none!important}'" : "")
            + "  +'.jw-video,.jw-media,.jw-wrapper,.vjs-tech{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important}'"
            + "  +'video{width:100%!important;height:100%!important;object-fit:contain!important}';"
            // Quitar atributo controls nativo de <video>
            + "document.querySelectorAll('video').forEach(function(v){"
            + "  v.removeAttribute('controls');"
            + "  v.setAttribute('playsinline','');"
            + "  v.setAttribute('webkit-playsinline','');"
            + "});"
            // Auto-descartar OneTrust / consentimiento de privacidad buscando por botones y textos
            + "function dismissConsentNow(targetDoc){"
            + "  var doc=targetDoc||document;"
            + "  try{"
            + "    var b=doc.getElementById('onetrust-accept-btn-handler')||doc.getElementById('accept-recommended-btn-handler');"
            + "    if(b){try{b.click();}catch(e){}}"
            + "    var btns=doc.querySelectorAll('button, a, [role=\"button\"], input[type=\"button\"], div.ot-sdk-button');"
            + "    for(var bi=0;bi<btns.length;bi++){"
            + "      var txt=(btns[bi].textContent||btns[bi].innerText||btns[bi].value||'').toLowerCase().trim();"
            + "      if(txt==='aceptar todas'||txt==='aceptar todo'||txt==='aceptar todas las cookies'||txt.indexOf('aceptar todas')!==-1||txt.indexOf('aceptar todo')!==-1||txt==='solo necesarias'||txt==='sólo necesarias'||txt.indexOf('solo necesarias')!==-1||txt.indexOf('sólo necesarias')!==-1||txt==='accept all'||txt==='reject all'){"
            + "        try{btns[bi].click();btns[bi].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){}"
            + "      }"
            + "    }"
            + "    var rb=doc.getElementById('onetrust-reject-all-handler')||doc.querySelector('.ot-pc-refuse-all-handler');"
            + "    if(rb){try{rb.click();}catch(e){}}"
            + "    var ots=doc.getElementById('onetrust-style');if(ots)try{ots.remove();}catch(e){}"
            + "    var bads=doc.querySelectorAll('#onetrust-consent-sdk,#onetrust-banner-sdk,.onetrust-pc-dark-filter,.ot-sdk-container,[id*=\"onetrust\"],[class*=\"onetrust\"],div[id*=\"consent\"],div[class*=\"consent\"],div[class*=\"cookie-banner\"]');"
            + "    for(var bj=0;bj<bads.length;bj++){"
            + "      try{bads[bj].remove();}catch(e){try{bads[bj].style.display='none';}catch(e2){}}"
            + "    }"
            + "    if(window.localStorage)try{localStorage.setItem('OptanonAlertBoxClosed',new Date().toISOString());}catch(e){}"
            + "  }catch(e){}"
            + "}"
            + "dismissConsentNow();"
            + "if(!window.__rexOtTimer){"
            + "  window.__rexOtTimer=setInterval(function(){dismissConsentNow();},250);"
            + "  setTimeout(function(){clearInterval(window.__rexOtTimer);window.__rexOtTimer=null;},10000);"
            + "}"
            // Auto-descartar o confirmar cualquier modal/diálogo de 'Resume playing?'
            + "try{"
            + "  var yp=document.getElementById('yesplease');"
            + "  if(yp){yp.click();}"
            + "  var cr=document.querySelectorAll('#checkresume_div,.checkresume,.jw-modal,.jw-dialog,.jw-resume-dialog,.vjs-modal-dialog,[class*=\"resume\"]');"
            + "  for(var c=0;c<cr.length;c++){"
            + "    try{"
            + "      var btns=cr[c].querySelectorAll('button,a,.jw-button');"
            + "      for(var b=0;b<btns.length;b++){"
            + "        var txt=(btns[b].textContent||'').toLowerCase().trim();"
            + "        if(txt.indexOf('yes')!==-1||txt.indexOf('resume')!==-1||txt.indexOf('sí')!==-1||txt.indexOf('si')!==-1){btns[b].click();break;}"
            + "      }"
            + "    }catch(e){}"
            + "    try{cr[c].remove();}catch(e){cr[c].style.setProperty('display','none','important');}"
            + "  }"
            + "}catch(e){}"
            // Ocultar controles en iframes hijos (AllCalidad / JWPlayer anidado)
            + "try{"
            + "  var ifrs=document.querySelectorAll('iframe');"
            + "  for(var fi=0;fi<ifrs.length;fi++){"
            + "    try{"
            + "      var idoc=ifrs[fi].contentDocument||(ifrs[fi].contentWindow&&ifrs[fi].contentWindow.document);"
            + "      if(idoc){"
            + "        var istyle=idoc.getElementById(styleId);"
            + "        if(!istyle&&idoc.head){"
            + "          istyle=idoc.createElement('style');"
            + "          istyle.id=styleId;"
            + "          idoc.head.appendChild(istyle);"
            + "        }"
            + "        if(istyle)istyle.textContent=s.textContent;"
            + "        dismissConsentNow(idoc);"
            + "        idoc.querySelectorAll('video').forEach(function(v){"
            + "          v.removeAttribute('controls');"
            + "          v.setAttribute('playsinline','');"
            + "          v.setAttribute('webkit-playsinline','');"
            + "        });"
            + "        if(ifrs[fi].contentWindow&&ifrs[fi].contentWindow.jwplayer){"
            + "          try{"
            + "            var ijw=ifrs[fi].contentWindow.jwplayer('vplayer')||ifrs[fi].contentWindow.jwplayer();"
            + "            if(ijw&&typeof ijw.setControls==='function')ijw.setControls(false);"
            + "          }catch(e){}"
            + "        }"
            + "      }"
            + "    }catch(e){}"
            + "  }"
            + "}catch(e){}"
            + "if(!window.__rexResumeObserver&&(document.body||document.documentElement)){"
            + "  window.__rexResumeObserver=true;"
            + "  new MutationObserver(function(){"
            + "    dismissConsentNow();"
            + "    try{"
            + "      var y=document.getElementById('yesplease');if(y){y.click();}"
            + "      var r=document.querySelectorAll('#checkresume_div,.checkresume');"
            + "      for(var i=0;i<r.length;i++){try{r[i].remove();}catch(e){}}"
            + "    }catch(e){}"
            + "  }).observe(document.body||document.documentElement,{childList:true,subtree:true});"
            + "}"
            + (playing ? "try{if(window.playerInstance&&typeof window.playerInstance.setControls==='function'){window.playerInstance.setControls(false);}if(window.jwplayer){var jw=window.jwplayer('vplayer')||window.jwplayer();if(jw&&typeof jw.setControls==='function'){jw.setControls(false);}}}catch(e){}" : "")
            + "}catch(e){}"
            + "})();", null
        );
    }

    private boolean isTelevision() {
        return sv.edu.catolica.rex.utils.TvUtils.isTv(this);
    }

    // ── TV: auto-ocultar controles del reproductor por inactividad ──────────────

    private void scheduleTvControlsHide() {
        if (!isVideoPlaying) {
            return;
        }
        handler.removeCallbacks(tvHideControlsRunnable);
        handler.postDelayed(tvHideControlsRunnable, TV_CONTROLS_HIDE_DELAY_MS);
    }

    /**
     * Inyecta JS que simula mouseleave/mouseout para que el reproductor
     * oculte sus controles de forma nativa, y aplica un fade-out de CSS
     * sobre cualquier barra de controles restante.
     * SOLO se ejecuta si el video ya está efectivamente en reproducción.
     */
    private void injectTvControlsHide() {
        if (webView == null || !pageLoaded || isFinishing() || isDestroyed() || !isVideoPlaying) {
            return;
        }
        String js = "(function(){"
                + "try{"
                // Simular inactividad de mouse para reproductores que escuchan eventos de ratón
                + "var el=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2)||document.body;"
                + "var evOpts={bubbles:true,cancelable:true,clientX:-1,clientY:-1};"
                + "el.dispatchEvent(new MouseEvent('mousemove',evOpts));"
                + "el.dispatchEvent(new MouseEvent('mouseleave',{bubbles:false}));"
                + "el.dispatchEvent(new MouseEvent('mouseout',{bubbles:true}));"
                + "document.dispatchEvent(new MouseEvent('mouseleave',{bubbles:false}));"
                // JWPlayer: ocultar controles mediante API
                + "try{"
                + "  if(window.jwplayer){"
                + "    var jw=window.jwplayer();"
                + "    if(jw&&typeof jw.setControls==='function')jw.setControls(false);"
                + "  }"
                + "}catch(e){}"
                // Ocultar controles nativos de <video> via setAttribute
                + "var vids=document.querySelectorAll('video');"
                + "for(var i=0;i<vids.length;i++){try{vids[i].removeAttribute('controls');}catch(e){}}"
                + "}catch(e){}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }
}
