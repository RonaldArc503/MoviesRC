package sv.edu.catolica.rex.ui.player;

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
import android.view.ViewGroup;
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
    private static final String NEXT_EPISODE_LABEL_BASE = "Siguiente episodio";

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
    private boolean isTvDevice = false;
    private String lastLoadedEmbedUrl = "";

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
        isTvDevice = isTelevision();

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

        ArrayList<String> directStreamUrls = extractDirectStreamUrls(serverUrls);
        if (!directStreamUrls.isEmpty()) {
            PlayerExoActivity.start(this, directStreamUrls, title);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoaded = false;
                progressBar.setVisibility(View.VISIBLE);
                hideNextEpisodeButton(true);
                handler.removeCallbacks(autoPlayRunnable);
                handler.removeCallbacks(playbackMonitorRunnable);

                // Resetear flags para nueva página
                if (webView != null) {
                    webView.evaluateJavascript(
                            "try{window.__rexDone=false;window.__rexVidDone=false;}catch(e){}", null
                    );
                }

                // Neutralizar protección anti-embed de vidhide/minochinos
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

                // Inyectar script para bloquear redirección a sandboxed
                if (webView != null) {
                    webView.evaluateJavascript(
                            "try{"
                            + "var sandboxFrames=document.querySelectorAll('iframe[src*=\"sandboxed\"]');"
                            + "for(var i=0;i<sandboxFrames.length;i++){sandboxFrames[i].parentNode.removeChild(sandboxFrames[i]);}"
                            + "}catch(e){}", null
                    );
                }

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



        webView.setWebChromeClient(new WebChromeClient() {
            private View customView;

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showCustomView(view);
            }

            @Override
            public void onShowCustomView(View view, int requestedOrientation, CustomViewCallback callback) {
                showCustomView(view);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            private void showCustomView(View view) {
                if (customView != null) {
                    return;
                }
                customView = view;
                FrameLayout decorView = (FrameLayout) getWindow().getDecorView();
                decorView.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }

            private void hideCustomView() {
                if (customView == null) {
                    return;
                }
                FrameLayout decorView = (FrameLayout) getWindow().getDecorView();
                decorView.removeView(customView);
                customView = null;
            }
        });
        webView.setOnTouchListener((v, event) -> {
            if (event == null) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                enterImmersiveMode();
                // Limpiar overlays de anuncios antes de que el toque llegue al WebView
                if (pageLoaded) {
                    removeAdOverlays();
                }
            }
            // Pasar los toques al WebView para que el usuario pueda interactuar
            // con el reproductor de video (play, pausa, seek, fullscreen)
            return false;
        });
        webView.setVisibility(android.view.View.VISIBLE);
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
                // Desactivar pickDirect para que no cree más overlays
                + "if(typeof pickDirect!=='undefined'){window.pickDirect=function(){};}"
                + "}catch(e){}"
                + "})();", null
        );
    }

    private void triggerAutoPlayAttempts() {
        handler.removeCallbacks(autoPlayRunnable);
        handler.postDelayed(autoPlayRunnable, 150L);
        handler.postDelayed(autoPlayRunnable, 1200L);
        handler.postDelayed(autoPlayRunnable, 2800L);
        handler.postDelayed(autoPlayRunnable, 4500L);
        handler.postDelayed(autoPlayRunnable, 7000L);
        handler.postDelayed(autoPlayRunnable, 9500L);
        handler.postDelayed(autoPlayRunnable, 13000L);
        handler.postDelayed(autoPlayRunnable, 18000L);
        handler.postDelayed(autoPlayRunnable, 25000L);
    }

    private void requestAutoPlay() {
        if (webView == null) return;

        String js = "(function(){"
                + "try{"
                + "var host=(location&&location.hostname?location.hostname.toLowerCase():'');"
                + "var isEmbed69=host.indexOf('embed69.org')!==-1;"
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

                // === CAPA 2: vidhide/minochinos — JWPlayer ===
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

                // === OTROS PLAYERS genéricos ===
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

        // El callback recibe la URL del embed (si embed69 la retornó)
        webView.evaluateJavascript(js, result -> {
            if (result == null) return;
            // evaluateJavascript envuelve strings en comillas, limpiar
            String embedUrl = result.trim();
            if (embedUrl.startsWith("\"")) {
                embedUrl = embedUrl.substring(1);
            }
            if (embedUrl.endsWith("\"")) {
                embedUrl = embedUrl.substring(0, embedUrl.length() - 1);
            }
            // Decodificar escapes unicode que Android agrega (\u003d etc)
            embedUrl = embedUrl.replace("\\u003d", "=")
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");

            if (embedUrl.isEmpty() || embedUrl.equals("null")) return;

            // Verificar que es una URL de un host de video conocido
            String lc = embedUrl.toLowerCase(Locale.ROOT);
            boolean isVideoHost = lc.contains("minochinos.com")
                    || lc.contains("hglink.to")
                    || lc.contains("bysedikamoum.com")
                    || lc.contains("voe.sx")
                    || lc.contains("voe.network")
                    || lc.contains("vidhide")
                    || lc.contains("streamwish")
                    || lc.contains("filemoon");

            if (!isVideoHost) return;

            // Usar flag Java para evitar recargas redundantes
            if (embedUrl.equals(lastLoadedEmbedUrl)) return;
            lastLoadedEmbedUrl = embedUrl;

            // Cargar directamente la Capa 2 en el WebView principal
            String finalEmbedUrl = embedUrl;
            handler.post(() -> {
                serverUrls.clear();
                serverUrls.add(finalEmbedUrl);
                currentServerIndex = 0;
                pageLoaded = false;
                Map<String, String> headers = buildPlaybackHeaders(finalEmbedUrl);
                if (headers.isEmpty()) {
                    webView.loadUrl(finalEmbedUrl);
                } else {
                    webView.loadUrl(finalEmbedUrl, headers);
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
        if (nextEpisodeButton == null || nextEpisodeInfo == null || isTransitioningToNextEpisode) {
            return;
        }

        int countdown = (int) Math.ceil(Math.max(0d, remainingSec));
        String code = formatEpisodeCode(nextEpisodeInfo.seasonNumber, nextEpisodeInfo.episodeNumber);
        if (countdown > 0) {
            nextEpisodeButton.setText("Siguiente " + code + " (" + countdown + "s)");
        } else {
            nextEpisodeButton.setText("Siguiente " + code);
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
        ArrayList<String> directStreamUrls = extractDirectStreamUrls(urls);
        if (!directStreamUrls.isEmpty()) {
            String title = seriesTitle + " - " + formatEpisodeCode(episode.seasonNumber, episode.episodeNumber);
            PlayerExoActivity.start(this, directStreamUrls, title);
            finish();
            return;
        }

        currentEpisodeId = episode.episodeId;
        currentSeasonNumber = episode.seasonNumber;
        currentEpisodeNumber = episode.episodeNumber;
        nextEpisodeInfo = null;

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
            if (url == null) continue;
            String lc = url.trim().toLowerCase(Locale.ROOT);
            if (lc.contains(".m3u8") || lc.contains(".mpd") || lc.contains(".mp4")) {
                direct.add(url.trim());
            }
        }
        return direct;
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
        lastLoadedEmbedUrl = "";
        String url = serverUrls.get(currentServerIndex);
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
        if (lc.contains("minochinos.com")
                || lc.contains("hglink.to")
                || lc.contains("bysedikamoum.com")
                || lc.contains("voe.sx")
                || lc.contains("voe.network")) {
            headers.put("Referer", "https://embed69.org/");
            headers.put("Origin", "https://embed69.org");
        }
        return headers;
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
        if (isSeriesEpisodePlayback()) {
            finish();
            return;
        }

        if (webView != null && webView.canGoBack()) {
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
            triggerAutoPlayAttempts();
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

    private boolean isTelevision() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null &&
                uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        int uiMode = getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }
}
