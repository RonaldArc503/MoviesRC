package sv.edu.catolica.rex.ui.player;

import android.annotation.SuppressLint;
import android.app.UiModeManager;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;

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
    public static final String RESULT_EPISODE_ID = "result_episode_id";
    public static final String RESULT_SEASON_NUMBER = "result_season_number";
    public static final String RESULT_EPISODE_NUMBER = "result_episode_number";

    private static final long SERVER_TIMEOUT_MS = 12000L;
    private static final int MAX_RETRIES_PER_SERVER = 1;
    private static final long RETRY_DELAY_MS = 700L;
    private static final long PLAYBACK_POLL_INTERVAL_MS = 2000L;
    private static final long PLAYBACK_POLL_INTERVAL_SLOW_MS = 5000L;
    private static final double NEXT_EPISODE_BUTTON_REMAINING_SEC = 300d;
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
    private boolean positionRestored = false;
    private long contentDurationMs = 0;
    private String contentId = "";
    private String imageUrl = "";
    private boolean disableExoSwitch = false;

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
        setContentView(R.layout.activity_player_contenido);
        isTvDevice = isTelevision();

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);
        nextEpisodeButton = findViewById(R.id.btn_next_episode);
        nextEpisodeLoaderText = findViewById(R.id.tv_next_loader);
        initControls();

        ArrayList<String> urls = getIntent().getStringArrayListExtra(EXTRA_URLS);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        seriesTitle = getIntent().getStringExtra(EXTRA_SERIES_TITLE);
        seriesPostId = getIntent().getIntExtra(EXTRA_SERIES_POST_ID, -1);
        seriesPostType = getIntent().getStringExtra(EXTRA_SERIES_POST_TYPE);
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

        if (serverUrls.isEmpty()) {
            setPlaybackResult();
            finish();
            return;
        }

        disableExoSwitch = getIntent().getBooleanExtra("disable_exo_switch", false);

        if (!disableExoSwitch) {
            ArrayList<String> directStreamUrls = extractDirectStreamUrls(serverUrls);
            if (!directStreamUrls.isEmpty()) {
                String embedReferer = originOf(!serverUrls.isEmpty() ? serverUrls.get(0) : null);
                PlayerExoActivity.start(this, directStreamUrls, title,
                        contentId, seriesTitle, imageUrl,
                        seriesPostId, seriesPostType,
                        currentEpisodeId, currentSeasonNumber, currentEpisodeNumber,
                        resumePositionMs, null, embedReferer);
                setPlaybackResult();
                finish();
                return;
            }
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
        nextEpisodeButton.setText(NEXT_EPISODE_LABEL_BASE);
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
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
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
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoaded = false;
                adOverlaysCleaned = false;
                progressBar.setVisibility(View.VISIBLE);
                showPlaybackStatus("Loading playback...");
                failoverInProgress = false;
                hideNextEpisodeButton(true);
                handler.removeCallbacks(autoPlayRunnable);
                handler.removeCallbacks(playbackMonitorRunnable);

                // Resetear flags para nueva pÃ¡gina
                if (webView != null) {
                    webView.evaluateJavascript(
                            "try{window.__rexDone=false;window.__rexVidDone=false;}catch(e){}", null
                    );
                }

                // Neutralizar protecciÃ³n anti-embed de vidhide/minochinos
                // El script del host detecta sandbox/iframe y redirige a /sandboxed.html
                if (webView != null) {
                    webView.evaluateJavascript(
                            "try{"
                            + "Object.defineProperty(document,'domain',{get:function(){return location.hostname;},set:function(v){}});"
                            + "if(!window.__rexSandboxPatched){"
                            + "  window.__rexSandboxPatched=true;"
                            + "  var origTimeout=window.setTimeout;"
                            + "  window.setTimeout=function(fn,ms){"
                            + "    var s=(typeof fn==='function'?fn.toString():(typeof fn==='string'?fn:''));"
                            + "    if(s.indexOf('sandboxed')!==-1||s.indexOf('/sandboxed')!==-1)return 0;"
                            + "    return origTimeout.apply(this,arguments);"
                            + "  };"
                            + "}"
                            + "}catch(e){}", null
                    );
                }

                injectHideVideoControls();

                scheduleServerTimeout();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
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

                // Inyectar script para bloquear redirecciÃ³n a sandboxed
                if (webView != null) {
                    webView.evaluateJavascript(
                            "try{"
                            + "var sandboxFrames=document.querySelectorAll('iframe[src*=\"sandboxed\"]');"
                            + "for(var i=0;i<sandboxFrames.length;i++){sandboxFrames[i].parentNode.removeChild(sandboxFrames[i]);}"
                            + "}catch(e){}", null
                    );
                }

                applyJwFullscreenFix(url);
                injectHideVideoControls();

                triggerAutoPlayAttempts();
                startPlaybackMonitor();
                schedulePositionRestore();
                if (isTvDevice) {
                    scheduleTvControlsHide();
                }
            }



            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    handleServerFailure();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    handleServerFailure();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                String current = currentServerIndex < serverUrls.size() ? serverUrls.get(currentServerIndex) : "";
                if (failingUrl != null && failingUrl.equals(current)) {
                    handleServerFailure();
                }
            }
        });



        webView.setWebChromeClient(new WebChromeClient() {
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
            if (event == null) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                enterImmersiveMode();
                // Limpiar overlays de anuncios una sola vez por página (evita
                // inyectar JS pesado en cada toque).
                if (pageLoaded && !adOverlaysCleaned) {
                    adOverlaysCleaned = true;
                    removeAdOverlays();
                    showControls();
                } else if (pageLoaded) {
                    showControls();
                }
            }
            if (action == MotionEvent.ACTION_UP && handleVideoSurfaceTap(v, event)) {
                return true;
            }
            return false;
        });
        webView.setVisibility(android.view.View.VISIBLE);
    }

    private boolean handleVideoSurfaceTap(View surface, MotionEvent event) {
        long now = event.getEventTime();
        int doubleTapTimeout = android.view.ViewConfiguration.getDoubleTapTimeout();
        float maxDistance = 80f * getResources().getDisplayMetrics().density;
        boolean doubleTap = lastVideoTapUpMs > 0
                && now - lastVideoTapUpMs <= doubleTapTimeout
                && Math.abs(event.getX() - lastVideoTapX) <= maxDistance;

        lastVideoTapUpMs = now;
        lastVideoTapX = event.getX();

        if (!doubleTap) {
            return false;
        }

        lastVideoTapUpMs = 0L;
        if (event.getX() < surface.getWidth() / 2f) {
            webViewSkip(-10000);
            Toast.makeText(this, "-10 s", Toast.LENGTH_SHORT).show();
        } else {
            webViewSkip(10000);
            Toast.makeText(this, "+10 s", Toast.LENGTH_SHORT).show();
        }
        showControls();
        return true;
    }

    private void removeAdOverlays() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){"
                + "try{"
                // Bloquear window.open para evitar popups de anuncios
                + "if(!window.__rexOpenBlocked){window.__rexOpenBlocked=true;window.open=function(){return null;};}"
                // Eliminar divs invisibles de anuncios (creados por pickDirect/directlink)
                + "var allDivs=document.querySelectorAll('div[style*=\"z-index\"][style*=\"fixed\"]');"
                + "for(var i=0;i<allDivs.length;i++){"
                + "  var s=allDivs[i].style;"
                + "  if(s.zIndex>2000000000||s.opacity==='0.01'||s.cssText.indexOf('opacity:0.01')!==-1){"
                + "    allDivs[i].parentNode.removeChild(allDivs[i]);"
                + "  }"
                + "}"
                // Desactivar pickDirect para que no cree mÃ¡s overlays
                + "if(typeof pickDirect!=='undefined'){window.pickDirect=function(){};}"
                + "}catch(e){}"
                + "})();", null
        );
    }

    private void triggerAutoPlayAttempts() {
        handler.removeCallbacks(autoPlayRunnable);
        // Intentos suaves y espaciados: menos inyecciones de JS = menos presión
        // sobre el renderer y menos riesgo de interrumpir la reproducción.
        handler.postDelayed(autoPlayRunnable, 150L);
        handler.postDelayed(autoPlayRunnable, 1500L);
        handler.postDelayed(autoPlayRunnable, 4000L);
        handler.postDelayed(autoPlayRunnable, 8000L);
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
                + "  ||host.indexOf('voe.sx')!==-1"
                + "  ||host.indexOf('voe.network')!==-1;"

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
                + "        try{if(jw.setMute)jw.setMute(false);}catch(e){}"
                + "        try{if(jw.setVolume)jw.setVolume(100);}catch(e){}"
                + "        try{if(jw.play)jw.play();}catch(e){}"
                + "        var jw2=window.jwplayer('vplayer');"
                + "        if(jw2){"
                + "          try{if(jw2.setMute)jw2.setMute(false);}catch(e){}"
                + "          try{if(jw2.setVolume)jw2.setVolume(100);}catch(e){}"
                + "          try{if(jw2.play)jw2.play();}catch(e){}"
                + "        }"
                + "      }"
                + "    }"
                + "  }catch(e){}"
                + "  var vids=document.querySelectorAll('video');"
                + "  for(var v=0;v<vids.length;v++){"
                + "    try{"
                + "      var vid=vids[v];"
                + "      vid.muted=false;vid.volume=1;"
                + "      if(vid.paused){"
                + "        var vp=vid.play();"
                + "        if(vp&&vp.catch)vp.catch(function(){});"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "  return '';"
                + "}"

                // === CAPA 2: vidhide/minochinos â€” JWPlayer ===
                + "if(isVidHide){"
                // Bloquear window.open y eliminar overlays de anuncios
                + "  if(!window.__rexOpenBlocked){window.__rexOpenBlocked=true;window.open=function(){return null;};}"
                + "  var adbd=document.getElementById('adbd');if(adbd)adbd.style.display='none';"
                + "  if(typeof pickDirect!=='undefined'){window.pickDirect=function(){};}"
                // Eliminar divs invisibles de anuncios
                + "  var adDivs=document.querySelectorAll('div[style*=\"z-index\"][style*=\"fixed\"]');"
                + "  for(var ad=0;ad<adDivs.length;ad++){"
                + "    var adS=adDivs[ad].style;"
                + "    if(parseInt(adS.zIndex)>2000000000||adS.opacity==='0.01'){"
                + "      adDivs[ad].parentNode.removeChild(adDivs[ad]);"
                + "    }"
                + "  }"
                // Eliminar scripts de anuncios (static.js)
                + "  var adScripts=document.querySelectorAll('script[src*=\"static.js\"]');"
                + "  for(var as=0;as<adScripts.length;as++){adScripts[as].parentNode.removeChild(adScripts[as]);}"
                // JWPlayer: reproducir CON sonido directamente
                + "  try{"
                + "    if(window.jwplayer){"
                + "      var jw=window.jwplayer();"
                + "      if(jw&&typeof jw.getState==='function'){"
                + "        var state=jw.getState();"
                + "        if(state==='idle'||state==='paused'){"
                + "          jw.setMute(false);"
                + "          jw.setVolume(100);"
                + "          jw.play();"
                + "        } else if(state==='playing'||state==='buffering'){"
                + "          jw.setMute(false);"
                + "          jw.setVolume(100);"
                + "        }"
                + "      }"
                + "    }"
                + "  }catch(e){}"
                // Videos nativos: reproducir CON sonido (no mutear)
                + "  var vids=document.querySelectorAll('video');"
                + "  for(var v=0;v<vids.length;v++){"
                + "    try{"
                + "      var vid=vids[v];"
                + "      vid.muted=false;vid.volume=1;"
                + "      if(vid.paused){"
                + "        var vp=vid.play();"
                + "        if(vp&&vp.catch)vp.catch(function(){});"
                + "      }"
                + "    }catch(e){}"
                + "  }"
                + "  return '';"
                + "}"

                // === OTROS PLAYERS genÃ©ricos ===
                + "function isPlaying(m){return!!m&&!m.paused&&!m.ended&&m.readyState>2;}"
                + "var media=document.querySelectorAll('video,audio');"
                + "for(var i=0;i<media.length;i++){if(isPlaying(media[i]))return '';}"
                + "for(var j=0;j<media.length;j++){"
                + "  var m=media[j];"
                + "  try{"
                + "    m.muted=false;m.volume=1;m.autoplay=true;m.playsInline=true;"
                + "    if(m.paused){"
                + "      var pr=m.play();"
                + "      if(pr&&pr.catch)pr.catch(function(){});"
                + "    }"
                + "  }catch(e){}"
                + "}"
                + "try{if(window.jwplayer){var jw=window.jwplayer();if(jw){jw.setMute(false);jw.setVolume(100);if(jw.play)jw.play(true);}}}catch(e){}"
                + "return '';"
                + "}catch(err){return '';}"
                + "})();";

        // El callback recibe la URL del embed (si embed69 la retornÃ³)
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
        if (webView == null || isFinishing() || isDestroyed()) {
            return;
        }

        if (isSeriesEpisodePlayback()) {
            if (nextEpisodeInfo == null && !loadingSeasonData) {
                preloadSeasonData();
            }
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
                + "return {hasMedia:true,duration:duration,currentTime:current,remaining:remaining,ended:!!selected.ended,paused:!!selected.paused};"
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

    private void handlePlaybackProbe(String raw) {
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw)) {
            return;
        }

        try {
            JSONObject state = new JSONObject(raw);
            if (!state.optBoolean("hasMedia", false)) {
                if (isSeriesEpisodePlayback()) hideNextEpisodeButton(false);
                return;
            }

            double currentTime = state.optDouble("currentTime", 0d);
            double duration = state.optDouble("duration", 0d);
            double remaining = state.optDouble("remaining", Double.MAX_VALUE);
            boolean ended = state.optBoolean("ended", false);
            boolean isPaused = state.optBoolean("paused", false);

            if (!positionRestored && resumePositionMs > 0 && duration > 0) {
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
            savePlaybackProgress(currentTime, duration);

            if (!isSeriesEpisodePlayback()) {
                return;
            }

            if ((ended || remaining <= AUTO_NEXT_REMAINING_SEC) && !autoNextTriggeredForCurrent) {
                autoNextTriggeredForCurrent = true;
                if (contentId != null && !contentId.isEmpty()) {
                    ContinueWatchingStore.markCompleted(PlayerContenidoActivity.this, contentId);
                }
                playNextEpisode(true);
                return;
            }

            double trigger = resolveNextButtonTrigger(duration);
            if (remaining <= trigger && remaining > AUTO_NEXT_REMAINING_SEC) {
                showNextEpisodeButton(remaining);
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
            if (normalized.toLowerCase(Locale.ROOT).contains("embed69.org")) {
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
        ArrayList<String> directStreamUrls = extractDirectStreamUrls(urls);
        if (!directStreamUrls.isEmpty()) {
            String title = seriesTitle + " - " + formatEpisodeCode(episode.seasonNumber, episode.episodeNumber);
            String nextContentId = (seriesPostId > 0 && episode.episodeId > 0)
                    ? seriesPostId + "_" + episode.episodeId : contentId;
            String embedReferer = originOf(!urls.isEmpty() ? urls.get(0) : null);
            PlayerExoActivity.start(this, directStreamUrls, title,
                    nextContentId, seriesTitle, imageUrl,
                    seriesPostId, seriesPostType,
                    episode.episodeId, episode.seasonNumber, episode.episodeNumber,
                    0L, null, embedReferer);
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

    private boolean isDirectStreamUrl(String url) {
        if (url == null) {
            return false;
        }
        String lc = url.trim().toLowerCase(Locale.ROOT);
        return lc.contains(".m3u8") || lc.contains(".mpd") || lc.contains(".mp4");
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
                || lc.contains("filemoon");
    }

    private void switchToExoPlayer(String streamUrl) {
        if (disableExoSwitch || exoSwitchInProgress || streamUrl == null || streamUrl.trim().isEmpty()) {
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
                resumePositionMs, embedUrl, embedReferer);
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

    private void preloadSeasonData() {
        if (!isSeriesEpisodePlayback() || loadingSeasonData || seasonPreloadAttempted) {
            return;
        }
        seasonPreloadAttempted = true;
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
    private void savePlaybackProgress(double currentTimeSec, double durationSec) {
        if (contentId == null || contentId.isEmpty() || currentTimeSec < 0) return;

        contentDurationMs = (long) (durationSec * 1000);
        long positionMs = (long) (currentTimeSec * 1000);

        if (Math.abs(currentTimeSec - lastSavedPositionSec) < 8.0) return;
        lastSavedPositionSec = currentTimeSec;

        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setContentId(contentId);
        item.setTitle(getTitle() != null ? getTitle().toString() : "");
        item.setSeriesTitle(seriesTitle);
        item.setImageUrl(imageUrl);
        item.setPostId(seriesPostId > 0 ? seriesPostId : (currentEpisodeId > 0 ? currentEpisodeId : 0));
        if (isSeriesEpisodePlayback()) {
            item.setEpisodeId(currentEpisodeId);
            item.setSeasonNumber(currentSeasonNumber);
            item.setEpisodeNumber(currentEpisodeNumber);
        }
        item.setPositionMs(positionMs);
        item.setDurationMs(contentDurationMs);
        if (durationSec > 0) {
            item.setProgressPercent(Math.min(99, (int) (currentTimeSec / durationSec * 100)));
        }
        item.setCompleted(false);

        final ContinueWatchingItem itemToSave = item;
        saveExecutor.execute(() -> {
            try {
                ContinueWatchingStore.save(PlayerContenidoActivity.this, itemToSave);
            } catch (Exception ignored) {
            }
        });
    }

    private void schedulePositionRestore() {
        if (resumePositionMs <= 0 || positionRestored) return;
        final int[] retries = {0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (webView == null || isFinishing() || isDestroyed()) return;
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
        if (isSeriesEpisodePlayback() && currentEpisodeId > 0) {
            return seriesPostId + "_" + currentEpisodeId;
        }
        if (currentEpisodeId > 0) {
            return "ep_" + currentEpisodeId;
        }
        if (seriesPostId > 0) {
            return "post_" + seriesPostId;
        }
        return "";
    }

    private void loadCurrentServer() {
        if (currentServerIndex < 0 || currentServerIndex >= serverUrls.size()) {
            onAllServersFailed();
            return;
        }
        failoverInProgress = false;
        pageLoaded = false;
        lastLoadedEmbedUrl = "";
        String url = serverUrls.get(currentServerIndex);
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
        return headers;
    }

    private void scheduleServerTimeout() {
        cancelServerTimeout();
        timeoutRunnable = () -> {
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

    private void handleServerFailure() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (failoverInProgress) {
            return;
        }
        failoverInProgress = true;
        cancelServerTimeout();

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
                + "  style.textContent='html,body,#player,.jwplayer,.jw-wrapper,.jw-media,.jw-video,.jw-aspect{width:100% !important;height:100% !important;max-width:none !important;max-height:none !important;} video{width:100% !important;height:100% !important;object-fit:contain !important;background:#000 !important;}';"
                + "  document.head.appendChild(style);"
                + "}"
                + "if(window.jwplayer){"
                + "  var jw=window.jwplayer();"
                + "  if(jw){"
                + "    try{if(jw.setMute)jw.setMute(false);}catch(e){}"
                + "    try{if(jw.setVolume)jw.setVolume(100);}catch(e){}"
                + "    try{if(jw.play)jw.play();}catch(e){}"
                + "    try{if(jw.setFullscreen)jw.setFullscreen(true);}catch(e){}"
                + "    try{if(jw.on){jw.on('ready',function(){try{if(jw.setFullscreen)jw.setFullscreen(true);}catch(e){}});}}catch(e){}"
                + "  }"
                + "}"
                + "var videos=document.querySelectorAll('video');"
                + "for(var i=0;i<videos.length;i++){"
                + "  try{videos[i].muted=false;videos[i].volume=1;}catch(e){}"
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
        return lc.contains("cdn.jwplayer.com") || lc.contains("jwplayer.com");
    }

    private void finishWithResult() {
        if (lastSavedPositionSec >= 0) {
            savePlaybackProgress(lastSavedPositionSec,
                    contentDurationMs > 0 ? contentDurationMs / 1000.0 : 0);
        }
        setPlaybackResult();
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
        // En TV: cada pulsación de tecla reinicia el temporizador de ocultar controles
        if (isTvDevice && pageLoaded) {
            scheduleTvControlsHide();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        cancelServerTimeout();
        cancelAutoHide();
        if (lastSavedPositionSec >= 0) {
            savePlaybackProgress(lastSavedPositionSec,
                    contentDurationMs > 0 ? contentDurationMs / 1000.0 : 0);
        }
        setPlaybackResult();
        if (webView != null) {
            android.view.ViewParent parent = webView.getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView(webView);
            }
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        saveExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(playbackMonitorRunnable);
        handler.removeCallbacks(tvHideControlsRunnable);
        cancelAutoHide();
        if (lastSavedPositionSec >= 0) {
            savePlaybackProgress(lastSavedPositionSec,
                    contentDurationMs > 0 ? contentDurationMs / 1000.0 : 0);
        }
        if (webView != null) {
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

        tvTitle.setText(getTitle() != null ? getTitle() : "Reproduciendo");

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
        btnSkipBack.setOnClickListener(v -> webViewSkip(-10000));
        btnSkipForward.setOnClickListener(v -> webViewSkip(10000));

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

    private void toggleWebViewPlayback() {
        if (webView == null) return;
        webView.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');if(v){if(v.paused){v.play()}else{v.pause()}}return!!(v&&!v.paused)}catch(e){return false}})();",
            result -> {
                boolean playing = "true".equals(result);
                updatePlayPauseIcons(playing);
            });
        resetAutoHide();
    }

    private void webViewSkip(long deltaMs) {
        if (webView == null || lastVideoDurationMs <= 0) return;
        webView.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');if(v){var t=Math.max(0,Math.min(v.duration||0,v.currentTime+" + deltaMs / 1000.0 + "));v.currentTime=t;return true}return false}catch(e){return false}})();",
            null);
        resetAutoHide();
    }

    private void webViewSeekTo(long positionMs) {
        if (webView == null) return;
        double targetSec = positionMs / 1000.0;
        webView.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');if(v&&isFinite(v.duration)&&v.duration>0){v.currentTime=" + targetSec + ";return true}return false}catch(e){return false}})();",
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
        webView.evaluateJavascript(
            "(function(){"
            + "try{"
            // ── Style: ocultar controles nativos HTML5 y JW Player ──
            + "var styleId='rex-hide-all-controls';"
            + "if(!document.getElementById(styleId)){"
            + "  var s=document.createElement('style');"
            + "  s.id=styleId;"
            + "  s.textContent="
            // Nativo HTML5
            + "  'video::-webkit-media-controls{display:none!important}'"
            + "  +'video::-webkit-media-controls-enclosure{display:none!important}'"
            + "  +'video::-webkit-media-controls-panel{display:none!important}'"
            + "  +'video::-webkit-media-controls-overlay-play-button{display:none!important}'"
            + "  +'video::-webkit-media-controls-start-playback-button{display:none!important}'"
            // JW Player: barra inferior, display central, overlays
            + "  +'.jw-controlbar{display:none!important}'"
            + "  +'.jw-controls{display:none!important}'"
            + "  +'.jw-controls-backdrop{display:none!important}'"
            + "  +'.jw-display-controls{display:none!important}'"
            + "  +'.jw-display-icon-container{display:none!important}'"
            + "  +'.jw-title{display:none!important}'"
            + "  +'.jw-logo{display:none!important}'"
            + "  +'.jw-overlays{display:none!important}'"
            + "  +'.jw-dock-buttons{display:none!important}'"
            + "  +'.jw-nextup-container{display:none!important}'"
            + "  +'.jw-info-container{display:none!important}'"
            + "  +'.jw-featured{display:none!important}'"
            + "  +'.jw-rightclick{display:none!important}'"
            // Forzar video a llenar contenedor
            + "  +'.jw-video,.jw-media,.jw-wrapper{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;background:#000!important}'"
            + "  +'video{width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important}';"
            + "  document.head.appendChild(s);"
            + "}"
            // ── Remover atributo controls de todos los <video> ──
            + "document.querySelectorAll('video').forEach(function(v){"
            + "  v.removeAttribute('controls');"
            + "  v.setAttribute('playsinline','');"
            + "  v.setAttribute('webkit-playsinline','');"
            + "});"
            // ── JW Player API: desactivar controles ──
            + "try{if(window.playerInstance&&typeof window.playerInstance.setControls==='function'){window.playerInstance.setControls(false);}}catch(e){}"
            + "try{if(window.jwplayer){var jw=window.jwplayer();if(jw&&typeof jw.setControls==='function'){jw.setControls(false);}}}" +
            "catch(e){}"
            + "try{if(window.jwplayer){var jw2=window.jwplayer('vplayer');if(jw2&&typeof jw2.setControls==='function'){jw2.setControls(false);}}}" +
            "catch(e){}"
            // ── MutationObserver + polling SOLO para páginas con JW Player ──
            // (en sitios con hls.js/vimeos no aplica y observar todo el DOM es caro)
            + "var host=(location&&location.hostname?location.hostname.toLowerCase():'');"
            + "var hasJw=typeof window.jwplayer!=='undefined'"
            + "  ||typeof window.playerInstance!=='undefined'"
            + "  ||host.indexOf('jwplayer')!==-1||host.indexOf('jwpsrv')!==-1;"
            + "if(hasJw){"
            + "  if(!window.__rexVideoObserver){"
            + "    window.__rexVideoObserver=true;"
            + "    new MutationObserver(function(m){"
            + "      m.forEach(function(mut){"
            + "        mut.addedNodes.forEach(function(n){"
            + "          if(n.nodeName==='VIDEO'){"
            + "            n.removeAttribute('controls');"
            + "            n.setAttribute('playsinline','');"
            + "          }"
            + "          if(n.querySelectorAll){"
            + "            n.querySelectorAll('video').forEach(function(v){"
            + "              v.removeAttribute('controls');"
            + "              v.setAttribute('playsinline','');"
            + "            });"
            + "          }"
            + "        });"
            + "      });"
            + "    }).observe(document,{childList:true,subtree:true});"
            + "  }"
            + "  if(!window.__rexJwPolling){"
            + "    window.__rexJwPolling=true;"
            + "    var attempts=0;"
            + "    var pollTimer=setInterval(function(){"
            + "      attempts++;"
            + "      try{"
            + "        if(window.playerInstance&&typeof window.playerInstance.setControls==='function'){window.playerInstance.setControls(false);}"
            + "        if(window.jwplayer){"
            + "          var jw=window.jwplayer();"
            + "          if(jw&&typeof jw.setControls==='function'){jw.setControls(false);}"
            + "          var jw2=window.jwplayer('vplayer');"
            + "          if(jw2&&typeof jw2.setControls==='function'){jw2.setControls(false);}"
            + "        }"
            + "      }catch(e){}"
            + "      if(attempts>10)clearInterval(pollTimer);"
            + "    },1000);"
            + "  }"
            + "}"
            + "}catch(e){}"
            + "})();", null
        );
    }

    private boolean isTelevision() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    // ── TV: auto-ocultar controles del reproductor por inactividad ──────────────

    private void scheduleTvControlsHide() {
        handler.removeCallbacks(tvHideControlsRunnable);
        handler.postDelayed(tvHideControlsRunnable, TV_CONTROLS_HIDE_DELAY_MS);
    }

    /**
     * Inyecta JS que simula mouseleave/mouseout para que el reproductor
     * oculte sus controles de forma nativa, y aplica un fade-out de CSS
     * sobre cualquier barra de controles restante.
     */
    private void injectTvControlsHide() {
        if (webView == null || !pageLoaded || isFinishing() || isDestroyed()) {
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


