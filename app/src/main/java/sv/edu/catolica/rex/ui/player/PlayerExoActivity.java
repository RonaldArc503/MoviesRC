package sv.edu.catolica.rex.ui.player;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import sv.edu.catolica.rex.R;

public class PlayerExoActivity extends AppCompatActivity {

    private static final String TAG = "PlayerExo";

    private static final String EXTRA_URLS = "exo_urls";
    private static final String EXTRA_TITLE = "exo_title";
    private static final String EXTRA_DISABLE_CACHE = "exo_disable_cache";
    private static final String EXTRA_REFERER = "exo_referer";
    private static final String EXTRA_ORIGIN = "exo_origin";
    private static final String EXTRA_FALLBACK_EMBED_URL = "exo_fallback_embed_url";
    private static final String EXTRA_FALLBACK_REFERER = "exo_fallback_referer";
    private static final int TIMEOUT_MS = 20_000;
    // Aumentado de 8s a 15s para streams HLS live que tardan más en renderizar
    // el primer frame de video. El watchdog solo mata streams que definitivamente
    // NO tienen video (solo audio), no streams lentos.
    private static final long VIDEO_WATCHDOG_MS = 15_000L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private PlayerView playerView;
    private ProgressBar progressBar;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory httpDataSourceFactory;
    private CacheDataSource.Factory cacheDataSourceFactory;
    private DefaultBandwidthMeter bandwidthMeter;
    private DefaultLoadControl loadControl;
    private final ArrayList<String> urls = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int currentUrlIndex = 0;
    private boolean disableCache;
    private boolean videoRenderedForCurrentUrl;
    // Indica si el stream actual entregó audio (para distinguir solo-audio de buffering lento)
    private boolean audioSessionStartedForCurrentUrl;
    private boolean videoWatchdogScheduled;
    private int videoWatchdogGeneration;
    private String playbackTitle;
    private String requestReferer;
    private String requestOrigin;
    private String fallbackEmbedUrl;
    private String fallbackReferer;

    public static void start(Context context, ArrayList<String> streamUrls, String title) {
        start(context, streamUrls, title, false);
    }

    public static void start(Context context, ArrayList<String> streamUrls, String title, boolean disableCache) {
        Intent intent = new Intent(context, PlayerExoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, streamUrls);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DISABLE_CACHE, disableCache);
        context.startActivity(intent);
    }

    public static void start(Context context,
                             ArrayList<String> streamUrls,
                             String title,
                             boolean disableCache,
                             String referer,
                             String origin,
                             String fallbackEmbedUrl,
                             String fallbackReferer) {
        Intent intent = new Intent(context, PlayerExoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, streamUrls);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DISABLE_CACHE, disableCache);
        intent.putExtra(EXTRA_REFERER, referer);
        intent.putExtra(EXTRA_ORIGIN, origin);
        intent.putExtra(EXTRA_FALLBACK_EMBED_URL, fallbackEmbedUrl);
        intent.putExtra(EXTRA_FALLBACK_REFERER, fallbackReferer);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_player_exo);
        enterImmersiveMode();

        playerView = findViewById(R.id.player_view);
        progressBar = findViewById(R.id.buffer_progress);
        disableCache = getIntent().getBooleanExtra(EXTRA_DISABLE_CACHE, false);
        requestReferer = getIntent().getStringExtra(EXTRA_REFERER);
        requestOrigin = getIntent().getStringExtra(EXTRA_ORIGIN);
        fallbackEmbedUrl = getIntent().getStringExtra(EXTRA_FALLBACK_EMBED_URL);
        fallbackReferer = getIntent().getStringExtra(EXTRA_FALLBACK_REFERER);

        ArrayList<String> incoming = getIntent().getStringArrayListExtra(EXTRA_URLS);
        if (incoming != null) {
            for (String url : incoming) {
                if (url != null && !url.trim().isEmpty()) {
                    urls.add(url.trim());
                }
            }
        }

        if (urls.isEmpty()) {
            Toast.makeText(this, "No hay streams HLS/MP4 disponibles", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        playbackTitle = title == null || title.trim().isEmpty() ? "Reproduciendo" : title;
        setTitle(playbackTitle);

        setupPlayer();
        playCurrentUrl();
    }

    private void setupPlayer() {
        // Shared network stack tuned for smoother playback and lower startup jitter.
        httpDataSourceFactory = PlaybackCacheHelper.newHttpDataSourceFactory(null);
        cacheDataSourceFactory = disableCache
            ? null
            : PlaybackCacheHelper.newCacheDataSourceFactory(this, httpDataSourceFactory);

        // Live streams need a short startup buffer; long initial buffering makes
        // channels look stuck and can fall behind the live edge.
        loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 1_500, 3_000)
            .setBackBuffer(30_000, true)
            .build();

        bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();

        player = new ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(
                    cacheDataSourceFactory != null ? cacheDataSourceFactory : httpDataSourceFactory))
                .build();
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
                if (playbackState == Player.STATE_READY) {
                    scheduleVideoWatchdog();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                tryNextStream();
            }

            @Override
            public void onRenderedFirstFrame() {
                videoRenderedForCurrentUrl = true;
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                // A manifest can announce a video track even when the device never
                // renders a frame. Only onRenderedFirstFrame confirms visible video.
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                // Si obtenemos un audio session ID válido (>0), el stream tiene audio
                if (audioSessionId > 0) {
                    audioSessionStartedForCurrentUrl = true;
                }
            }
        });
    }

    private void playCurrentUrl() {
        if (player == null) {
            return;
        }
        if (currentUrlIndex < 0 || currentUrlIndex >= urls.size()) {
            Toast.makeText(this, "No se pudo reproducir con los streams disponibles", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String url = urls.get(currentUrlIndex);
        videoRenderedForCurrentUrl = false;
        audioSessionStartedForCurrentUrl = false;
        videoWatchdogScheduled = false;
        videoWatchdogGeneration++;
        Map<String, String> headers = buildPlaybackHeaders(url);
        if (httpDataSourceFactory != null) {
            httpDataSourceFactory.setDefaultRequestProperties(headers);
        }

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .build();

        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void tryNextStream() {
        String currentUrl = (currentUrlIndex >= 0 && currentUrlIndex < urls.size())
                ? urls.get(currentUrlIndex) : "unknown";
        Log.d(TAG, "tryNextStream: falló stream #" + currentUrlIndex + " url=" + currentUrl +
                ". Fallback embed=" + fallbackEmbedUrl);

        if (currentUrlIndex + 1 < urls.size()) {
            currentUrlIndex++;
            Log.d(TAG, "tryNextStream: probando stream #" + (currentUrlIndex + 1) +
                    " url=" + urls.get(currentUrlIndex));
            Toast.makeText(this, "Cambiando stream " + (currentUrlIndex + 1), Toast.LENGTH_SHORT).show();
            playCurrentUrl();
            return;
        }

        if (launchFallbackPlayerIfAvailable()) {
            Log.d(TAG, "tryNextStream: lanzando fallback WebView");
            return;
        }

        Log.e(TAG, "tryNextStream: no más streams ni fallback. Cerrando.");
        Toast.makeText(this, "No se pudo reproducir con los streams disponibles", Toast.LENGTH_LONG).show();
        finish();
    }

    private Map<String, String> buildPlaybackHeaders(String url) {
        HashMap<String, String> headers = new HashMap<>(PlaybackCacheHelper.buildPlaybackHeaders(url));
        String referer = trimToNull(requestReferer);
        if (referer != null && !headers.containsKey("Referer")) {
            headers.put("Referer", referer);
        }
        String origin = trimToNull(requestOrigin);
        if (origin == null && referer != null) {
            origin = originFromUrl(referer);
        }
        if (origin != null && !headers.containsKey("Origin")) {
            headers.put("Origin", origin);
        }
        return headers;
    }

    private void scheduleVideoWatchdog() {
        if (videoWatchdogScheduled || player == null) {
            return;
        }
        // Si ya se renderizó video, no necesitamos watchdog
        if (videoRenderedForCurrentUrl) {
            return;
        }
        String currentUrl = (currentUrlIndex >= 0 && currentUrlIndex < urls.size())
                ? urls.get(currentUrlIndex) : "unknown";
        Log.d(TAG, "scheduleVideoWatchdog: programado para " + VIDEO_WATCHDOG_MS + "ms en url=" + currentUrl);
        videoWatchdogScheduled = true;
        final int generation = videoWatchdogGeneration;
        mainHandler.postDelayed(() -> {
            if (player == null || generation != videoWatchdogGeneration) {
                return;
            }
            // Si ya se renderizó video mientras esperábamos, todo bien
            if (videoRenderedForCurrentUrl) {
                Log.d(TAG, "scheduleVideoWatchdog: video renderizado OK, cancelando watchdog");
                return;
            }
            // Verificar estado del player
            int state = player.getPlaybackState();
            Log.d(TAG, "scheduleVideoWatchdog: estado=" + state +
                    " audioSession=" + audioSessionStartedForCurrentUrl +
                    " videoRendered=" + videoRenderedForCurrentUrl +
                    " playWhenReady=" + player.getPlayWhenReady());
            if (state == Player.STATE_READY && player.getPlayWhenReady()) {
                // El player está en READY pero no ha renderizado video.
                // Si hay audio activo pero sin video, es un stream solo-audio -> fallback
                // Si no hay ni audio ni video, el stream puede estar lento, dar más tiempo.
                if (audioSessionStartedForCurrentUrl) {
                    // Audio presente pero sin video después de 15s -> probablemente solo audio
                    // Aún así, darle 5s más de gracia por si acaso
                    Log.d(TAG, "scheduleVideoWatchdog: audio sí, video no. Dando 5s extra...");
                    mainHandler.postDelayed(() -> {
                        if (player == null || generation != videoWatchdogGeneration) {
                            return;
                        }
                        if (videoRenderedForCurrentUrl) {
                            return;
                        }
                        if (audioSessionStartedForCurrentUrl) {
                            Log.e(TAG, "scheduleVideoWatchdog: CONFIRMADO solo audio. Haciendo fallback.");
                            Toast.makeText(this, "Stream solo audio. Probando otra fuente.", Toast.LENGTH_SHORT).show();
                        } else {
                            Log.e(TAG, "scheduleVideoWatchdog: sin video ni audio. Haciendo fallback.");
                            Toast.makeText(this, "Este stream no entrega video. Probando otra fuente.", Toast.LENGTH_SHORT).show();
                        }
                        tryNextStream();
                    }, 5_000L);
                } else {
                    // Sin audio ni video aún -> puede estar buffeando lento, dar 5s más
                    Log.d(TAG, "scheduleVideoWatchdog: sin audio ni video. Dando 5s extra...");
                    mainHandler.postDelayed(() -> {
                        if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) {
                            return;
                        }
                        Log.e(TAG, "scheduleVideoWatchdog: sin respuesta tras 20s total. Fallback.");
                        Toast.makeText(this, "Stream sin respuesta. Probando otra fuente.", Toast.LENGTH_SHORT).show();
                        tryNextStream();
                    }, 5_000L);
                }
            } else if (state == Player.STATE_BUFFERING) {
                // Aún buffeando después de 15s, darle 5s más de chance
                Log.d(TAG, "scheduleVideoWatchdog: buffering tras 15s. Dando 5s extra...");
                mainHandler.postDelayed(() -> {
                    if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) {
                        return;
                    }
                    if (player.getPlaybackState() == Player.STATE_BUFFERING) {
                        Log.e(TAG, "scheduleVideoWatchdog: buffering tras 20s total. Fallback.");
                        Toast.makeText(this, "Stream lento. Probando otra fuente.", Toast.LENGTH_SHORT).show();
                        tryNextStream();
                    }
                }, 5_000L);
            }
        }, VIDEO_WATCHDOG_MS);
    }

    private boolean launchFallbackPlayerIfAvailable() {
        String embedUrl = trimToNull(fallbackEmbedUrl);
        if (embedUrl == null) {
            return false;
        }
        FootballWebPlayerActivity.start(this, embedUrl, fallbackReferer, playbackTitle, false);
        finish();
        return true;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String originFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (player != null) {
            player.play();
        }
    }

    @Override
    protected void onPause() {
        if (player != null) {
            player.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        videoWatchdogGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        PlaybackCacheHelper.cancelPreload();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
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
