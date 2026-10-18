package sv.edu.catolica.rex.ui.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.smart.Cuevana3Scraper;
import sv.edu.catolica.rex.network.smart.SeriesSeasonsCache;
import sv.edu.catolica.rex.network.smart.SmartScraperEngine;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.cast.CastPlayer;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastState;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastStateListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;

public class PlayerExoActivity extends AppCompatActivity {

    private static final String TAG = "PlayerExo";

    private static final String EXTRA_URLS = "exo_urls";
    private static final String EXTRA_TITLE = "exo_title";
    private static final String EXTRA_DISABLE_CACHE = "exo_disable_cache";
    private static final String EXTRA_REFERER = "exo_referer";
    private static final String EXTRA_ORIGIN = "exo_origin";
    private static final String EXTRA_FALLBACK_EMBED_URL = "exo_fallback_embed_url";
    private static final String EXTRA_FALLBACK_REFERER = "exo_fallback_referer";

    private static final String EXTRA_CONTENT_ID = "exo_content_id";
    private static final String EXTRA_SERIES_TITLE = "exo_series_title";
    private static final String EXTRA_POST_ID = "exo_post_id";
    private static final String EXTRA_MEDIA_TYPE = "exo_media_type";
    private static final String EXTRA_EPISODE_ID = "exo_episode_id";
    private static final String EXTRA_SEASON_NUMBER = "exo_season_number";
    private static final String EXTRA_EPISODE_NUMBER = "exo_episode_number";
    private static final String EXTRA_IMAGE_URL = "exo_image_url";
    private static final String EXTRA_RESUME_POSITION_MS = "exo_resume_position_ms";
    public static final String EXTRA_IS_LIVE = "exo_is_live";
    private static final String EXTRA_SERIES_SOURCE = "exo_series_source";
    private static final String EXTRA_SERIES_DETAIL_URL = "exo_series_detail_url";

    private static final int TIMEOUT_MS = 20_000;
    private static final long VIDEO_WATCHDOG_MS = 25_000L;
    private static final long PROGRESS_SAVE_INTERVAL_MS = 10_000L;
    private static final long CONTROLS_AUTO_HIDE_MS = 3000L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private PlayerView playerView;
    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private ProgressBar progressBar;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory httpDataSourceFactory;
    private CacheDataSource.Factory cacheDataSourceFactory;
    private DefaultBandwidthMeter bandwidthMeter;
    private DefaultLoadControl loadControl;
    private final ArrayList<String> urls = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isExiting = false;
    private int currentUrlIndex = 0;
    private boolean disableCache;
    private boolean videoRenderedForCurrentUrl;
    private boolean audioSessionStartedForCurrentUrl;
    private boolean audioFallbackInProgress;
    private boolean videoWatchdogScheduled;
    private int videoWatchdogGeneration;
    private String playbackTitle;
    private String requestReferer;
    private String requestOrigin;
    private String fallbackEmbedUrl;
    private String fallbackReferer;
    private ArrayList<String> fallbackEmbedUrls;
    private boolean isLiveStream = false;
    private View liveBadge;
    private View seekbarContainer;
    private View flSubtitles;
    private View llSkipBack;
    private View llSkipForward;
    private PlayerDoubleTapHelper doubleTapHelper;

    private String contentId = "";
    private String seriesTitle = "";
    private String imageUrl = "";
    private int postId = -1;
    private String mediaType = "";
    private int episodeId = -1;
    private int seasonNumber = -1;
    private int episodeNumber = -1;
    private long resumePositionMs = 0;
    private String seriesSource = "";
    private String seriesDetailUrl = "";
    private boolean contentEnded = false;
    private boolean progressSaveScheduled = false;

    // Series next episode navigation
    private Button btnNextEpisode;
    private final List<AllCalidadScraper.Season> seasonCache = new ArrayList<>();
    private boolean loadingSeasonData = false;
    private boolean seasonPreloadAttempted = false;
    private boolean isTransitioningToNextEpisode = false;
    private boolean nextEpisodeQueuedInStore = false;

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
    private NextEpisodeInfo nextEpisodeInfo;

    // Controls overlay
    private View controlOverlay;
    private View scrimTop;
    private View scrimBottom;
    private View topBar;
    private View bottomBar;
    private View centerControls;
    private ImageButton btnPlayCenter;
    private ImageButton btnSkipBack;
    private ImageButton btnSkipForward;
    private ImageButton btnLock;
    private ImageButton btnQuality;
    private ImageButton btnSubtitles;
    private ImageButton btnAudio;
    private ImageButton btnFullscreen;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvDuration;
    private TextView tvTitle;
    private boolean controlsVisible = true;
    private boolean controlsLocked = false;
    private boolean seekBarDragging = false;
    private boolean isTvDevice = false;
    private float seekDownX = -1f;
    private long lastVideoTapUpMs = 0L;
    private float lastVideoTapX = -1f;

    // Casting
    private ImageButton btnCast;
    private ImageButton btnBack;
    private MediaRouteButton mediaRouteButton;
    private CastContext castContext;
    private CastPlayer castPlayer;
    private CastStateListener castStateListener;
    private boolean isCasting = false;
    private Toast currentToast;

    private void showToast(String message) {
        showToast(message, Toast.LENGTH_SHORT);
    }

    private void showToast(String message, int length) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (currentToast != null) {
                currentToast.cancel();
            }
            currentToast = Toast.makeText(this, message, length);
            currentToast.show();
        });
    }

    /** Guarda el progreso en un hilo de fondo para no saturar el main thread. */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();

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

    public static void start(Context context, ArrayList<String> streamUrls, String title, boolean disableCache, boolean isLive) {
        Intent intent = new Intent(context, PlayerExoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, streamUrls);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DISABLE_CACHE, disableCache);
        intent.putExtra(EXTRA_IS_LIVE, isLive);
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

    public static void start(Context context,
                             ArrayList<String> streamUrls,
                             String title,
                             String contentId,
                             String seriesTitle,
                             String imageUrl,
                             int postId,
                             String mediaType,
                             int episodeId,
                             int seasonNumber,
                             int episodeNumber,
                             long resumePositionMs,
                             String fallbackEmbedUrl,
                             String fallbackReferer) {
        start(context, streamUrls, title, contentId, seriesTitle, imageUrl,
                postId, mediaType, episodeId, seasonNumber, episodeNumber,
                resumePositionMs, fallbackEmbedUrl, fallbackReferer, null, null);
    }

    public static void start(Context context,
                             ArrayList<String> streamUrls,
                             String title,
                             String contentId,
                             String seriesTitle,
                             String imageUrl,
                             int postId,
                             String mediaType,
                             int episodeId,
                             int seasonNumber,
                             int episodeNumber,
                             long resumePositionMs,
                             String fallbackEmbedUrl,
                             String fallbackReferer,
                             String seriesSource,
                             String seriesDetailUrl) {
        Intent intent = new Intent(context, PlayerExoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, streamUrls);
        intent.putStringArrayListExtra("urls", streamUrls);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra("title", title);
        intent.putExtra(EXTRA_CONTENT_ID, contentId == null ? "" : contentId);
        intent.putExtra("content_id", contentId == null ? "" : contentId);
        intent.putExtra(EXTRA_SERIES_TITLE, seriesTitle == null ? "" : seriesTitle);
        intent.putExtra("series_title", seriesTitle == null ? "" : seriesTitle);
        intent.putExtra(EXTRA_IMAGE_URL, imageUrl == null ? "" : imageUrl);
        intent.putExtra("image_url", imageUrl == null ? "" : imageUrl);
        intent.putExtra(EXTRA_POST_ID, postId);
        intent.putExtra("post_id", postId);
        intent.putExtra(EXTRA_MEDIA_TYPE, mediaType == null ? "" : mediaType);
        intent.putExtra("media_type", mediaType == null ? "" : mediaType);
        intent.putExtra(EXTRA_EPISODE_ID, episodeId);
        intent.putExtra("episode_id", episodeId);
        intent.putExtra(EXTRA_SEASON_NUMBER, seasonNumber);
        intent.putExtra("season_number", seasonNumber);
        intent.putExtra(EXTRA_EPISODE_NUMBER, episodeNumber);
        intent.putExtra("episode_number", episodeNumber);
        if (resumePositionMs > 0) {
            intent.putExtra(EXTRA_RESUME_POSITION_MS, resumePositionMs);
            intent.putExtra("resume_position_ms", resumePositionMs);
        }
        if (seriesSource != null && !seriesSource.isEmpty()) {
            intent.putExtra(EXTRA_SERIES_SOURCE, seriesSource);
            intent.putExtra("series_source", seriesSource);
        }
        if (seriesDetailUrl != null && !seriesDetailUrl.isEmpty()) {
            intent.putExtra(EXTRA_SERIES_DETAIL_URL, seriesDetailUrl);
            intent.putExtra("series_detail_url", seriesDetailUrl);
        }
        if (fallbackEmbedUrl != null && !fallbackEmbedUrl.isEmpty()) {
            intent.putExtra(EXTRA_FALLBACK_EMBED_URL, fallbackEmbedUrl);
        }
        if (fallbackReferer != null && !fallbackReferer.isEmpty()) {
            intent.putExtra(EXTRA_FALLBACK_REFERER, fallbackReferer);
            intent.putExtra(EXTRA_REFERER, fallbackReferer);
            intent.putExtra(EXTRA_ORIGIN, fallbackReferer);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player_exo);
        isTvDevice = sv.edu.catolica.rex.utils.TvUtils.isTv(this);
        PlaybackCacheHelper.initCacheAsync(this);
        enterImmersiveMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        playerView = findViewById(R.id.player_view);
        if (playerView != null) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        progressBar = findViewById(R.id.buffer_progress);
        disableCache = getIntent().getBooleanExtra(EXTRA_DISABLE_CACHE, false);
        isLiveStream = getIntent().getBooleanExtra(EXTRA_IS_LIVE, false);
        requestReferer = getIntent().getStringExtra(EXTRA_REFERER);
        requestOrigin = getIntent().getStringExtra(EXTRA_ORIGIN);
        fallbackEmbedUrl = getIntent().getStringExtra(EXTRA_FALLBACK_EMBED_URL);
        fallbackReferer = getIntent().getStringExtra(EXTRA_FALLBACK_REFERER);
        fallbackEmbedUrls = getIntent().getStringArrayListExtra("exo_fallback_embed_urls");

        contentId = getIntent().getStringExtra(EXTRA_CONTENT_ID);
        if (contentId == null || contentId.isEmpty()) {
            contentId = getIntent().getStringExtra("content_id");
        }
        seriesTitle = getIntent().getStringExtra(EXTRA_SERIES_TITLE);
        if (seriesTitle == null || seriesTitle.isEmpty()) {
            seriesTitle = getIntent().getStringExtra("series_title");
        }
        imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = getIntent().getStringExtra("image_url");
        }
        postId = getIntent().getIntExtra(EXTRA_POST_ID, getIntent().getIntExtra("post_id", -1));
        mediaType = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
        if (mediaType == null || mediaType.isEmpty()) {
            mediaType = getIntent().getStringExtra("media_type");
        }
        episodeId = getIntent().getIntExtra(EXTRA_EPISODE_ID, getIntent().getIntExtra("episode_id", -1));
        seasonNumber = getIntent().getIntExtra(EXTRA_SEASON_NUMBER, getIntent().getIntExtra("season_number", -1));
        episodeNumber = getIntent().getIntExtra(EXTRA_EPISODE_NUMBER, getIntent().getIntExtra("episode_number", -1));
        resumePositionMs = getIntent().getLongExtra(EXTRA_RESUME_POSITION_MS, getIntent().getLongExtra("resume_position_ms", 0L));
        seriesSource = getIntent().getStringExtra(EXTRA_SERIES_SOURCE);
        if (seriesSource == null || seriesSource.isEmpty()) {
            seriesSource = getIntent().getStringExtra("series_source");
        }
        seriesDetailUrl = getIntent().getStringExtra(EXTRA_SERIES_DETAIL_URL);
        if (seriesDetailUrl == null || seriesDetailUrl.isEmpty()) {
            seriesDetailUrl = getIntent().getStringExtra("series_detail_url");
        }

        if (contentId == null || contentId.isEmpty()) {
            if (postId > 0 && episodeId > 0) {
                contentId = postId + "_" + episodeId;
            } else if (seriesTitle != null && !seriesTitle.isEmpty() && seasonNumber > 0 && episodeNumber > 0) {
                contentId = seriesTitle + "_s" + seasonNumber + "e" + episodeNumber;
            }
        }

        ArrayList<String> incoming = getIntent().getStringArrayListExtra(EXTRA_URLS);
        if (incoming == null || incoming.isEmpty()) {
            incoming = getIntent().getStringArrayListExtra("urls");
        }
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

        initControls();
        setupPlayer();
        initCasting();
        playCurrentUrl();

        if (isSeriesEpisodePlayback()) {
            nextEpisodeInfo = findNextEpisodeFromCache();
            preloadSeasonData();
            startSeriesNextMonitor();
        }
    }

    private void initCasting() {
        if (btnCast != null) {
            btnCast.setVisibility(View.VISIBLE);
            btnCast.setEnabled(false);
            btnCast.setAlpha(0.45f);
        }
        try {
            if (mediaRouteButton != null) {
                CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), mediaRouteButton);
            }
            castContext = CastContext.getSharedInstance(this);
            castPlayer = new CastPlayer(castContext);
            castStateListener = new CastStateListener() {
                @Override
                public void onCastStateChanged(int state) {
                    if (state == CastState.CONNECTED && !isCasting) {
                        switchToCast();
                    } else if (state != CastState.CONNECTED && isCasting) {
                        switchToLocal();
                    }
                    updateCastButtonState(state);
                }
            };
            castContext.addCastStateListener(castStateListener);
            updateCastButtonState(castContext.getCastState());
        } catch (Exception e) {
            if (btnCast != null) {
                btnCast.setVisibility(View.VISIBLE);
                btnCast.setEnabled(false);
                btnCast.setAlpha(0.45f);
            }
        }
    }

    private void updateCastButtonState(int state) {
        if (btnCast == null) return;
        boolean available = state != CastState.NO_DEVICES_AVAILABLE;
        btnCast.setVisibility(View.VISIBLE);
        btnCast.setEnabled(available);
        btnCast.setAlpha(available ? 1f : 0.45f);
        btnCast.setImageResource(isCasting ? R.drawable.ic_cast_connected : R.drawable.ic_tv);
    }

    private void switchToCast() {
        if (player == null || castPlayer == null) return;
        isCasting = true;
        long position = player.getCurrentPosition();
        boolean wasPlaying = player.getPlayWhenReady();
        player.pause();

        MediaItem currentItem = player.getCurrentMediaItem();
        if (currentItem != null) {
            castPlayer.setMediaItem(currentItem);
            castPlayer.prepare();
            castPlayer.seekTo(position);
            if (wasPlaying) castPlayer.play();
        }

        playerView.setPlayer(castPlayer);
        btnCast.setImageResource(R.drawable.ic_cast_connected);
        btnCast.setAlpha(1f);
        Toast.makeText(this, "Transmitiendo a TV", Toast.LENGTH_SHORT).show();
        showControls();
    }

    private void switchToLocal() {
        if (player == null || castPlayer == null) return;
        isCasting = false;
        long position = castPlayer.getCurrentPosition();
        boolean wasPlaying = castPlayer.getPlayWhenReady();

        castPlayer.stop();

        playerView.setPlayer(player);
        if (position > 0) player.seekTo(position);
        if (wasPlaying) player.play();

        btnCast.setImageResource(R.drawable.ic_tv);
        Toast.makeText(this, "Transmisión detenida", Toast.LENGTH_SHORT).show();
        showControls();
    }

    private Player getCurrentPlayer() {
        return isCasting && castPlayer != null ? castPlayer : player;
    }

    private void initControls() {
        controlOverlay = findViewById(R.id.control_overlay);
        scrimTop = findViewById(R.id.scrim_top);
        scrimBottom = findViewById(R.id.scrim_bottom);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        centerControls = findViewById(R.id.center_controls);
        btnPlayCenter = findViewById(R.id.btn_play_center);
        btnSkipBack = findViewById(R.id.btn_skip_back);
        btnSkipForward = findViewById(R.id.btn_skip_forward);
        btnLock = findViewById(R.id.btn_lock);
        btnQuality = findViewById(R.id.btn_quality);
        btnSubtitles = findViewById(R.id.btn_subtitles);
        btnAudio = findViewById(R.id.btn_audio);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        btnBack = findViewById(R.id.btn_back);
        btnCast = findViewById(R.id.btn_cast);
        mediaRouteButton = findViewById(R.id.media_route_button);
        seekBar = findViewById(R.id.player_seekbar);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvDuration = findViewById(R.id.tv_duration);
        tvTitle = findViewById(R.id.tv_player_title);
        if (tvTitle != null) {
            tvTitle.setText(resolveDisplayTitle());
        }
        liveBadge = findViewById(R.id.ll_live_badge);
        seekbarContainer = findViewById(R.id.ll_seekbar_container);
        flSubtitles = findViewById(R.id.fl_subtitles);
        llSkipBack = findViewById(R.id.ll_skip_back);
        llSkipForward = findViewById(R.id.ll_skip_forward);

        btnNextEpisode = findViewById(R.id.btn_next_episode);
        if (btnNextEpisode != null) {
            btnNextEpisode.setOnClickListener(v -> playNextEpisode(false));
        }

        updateLiveUi(isLiveStream);
        if (isAllCalidadSource() && flSubtitles != null) {
            flSubtitles.setVisibility(View.GONE);
        }

        controlOverlay.setVisibility(View.VISIBLE);
        controlOverlay.setClickable(false);

        btnPlayCenter.setOnClickListener(v -> togglePlayPause());
        btnSkipBack.setOnClickListener(v -> {
            skip(-10000);
            if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
        });
        btnSkipForward.setOnClickListener(v -> {
            skip(10000);
            if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
        });

        doubleTapHelper = new PlayerDoubleTapHelper(this);
        doubleTapHelper.initViews(
                btnSkipBack,
                btnSkipForward,
                deltaMs -> skip(deltaMs),
                () -> toggleControls(),
                () -> controlsVisible,
                () -> resetAutoHide()
        );

        btnLock.setOnClickListener(v -> toggleLock());
        btnQuality.setOnClickListener(v -> showTrackSelector(C.TRACK_TYPE_VIDEO));
        btnSubtitles.setOnClickListener(v -> showTrackSelector(C.TRACK_TYPE_TEXT));
        btnAudio.setOnClickListener(v -> showTrackSelector(C.TRACK_TYPE_AUDIO));
        btnBack.setOnClickListener(v -> finishPlayer());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        btnCast.setOnClickListener(v -> {
            if (mediaRouteButton != null) {
                mediaRouteButton.showDialog();
            } else {
                Toast.makeText(this, "Google Cast no esta disponible", Toast.LENGTH_SHORT).show();
            }
        });
        playerView.setOnTouchListener((v, event) -> handleVideoSurfaceTouch(v, event));

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
                Player p = getCurrentPlayer();
                if (fromUser && p != null) {
                    long duration = p.getDuration();
                    if (duration > 0) {
                        long seekPos = duration * seekBar.getProgress() / seekBar.getMax();
                        p.seekTo(seekPos);
                        updateTimeLabels();
                    }
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
                        Player p = getCurrentPlayer();
                        if (available > 0 && p != null) {
                            float fraction = Math.max(0f, Math.min(1f,
                                    (event.getX() - sb.getPaddingLeft()) / available));
                            sb.setProgress(Math.round(fraction * sb.getMax()));
                            long duration = p.getDuration();
                            if (duration > 0) {
                                p.seekTo(Math.round(fraction * duration));
                                updateTimeLabels();
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

    private boolean handleVideoSurfaceTouch(View surface, MotionEvent event) {
        return false;
    }

    /** Detecta toques "vacíos" a nivel de Activity: si ningún control consumió el
     *  evento, un toque simple muestra/oculta los controles. Robusto ante la capa
     *  de vídeo (SurfaceView) que en algunos dispositivos no entrega el toque a
     *  las vistas internas del overlay. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (doubleTapHelper != null && doubleTapHelper.dispatchTouchEvent(ev)) {
            return true;
        }
        boolean handled = super.dispatchTouchEvent(ev);
        if (ev != null && !handled && ev.getActionMasked() == MotionEvent.ACTION_UP) {
            handleBackgroundTap(ev.getRawX(), ev.getRawY());
        }
        return handled;
    }

    private void handleBackgroundTap(float rawX, float rawY) {
        if (controlsLocked || isPointInView(seekBar, rawX, rawY)) {
            return;
        }
        if (controlsVisible) {
            Player p = getCurrentPlayer();
            if (p != null && p.getPlayWhenReady()) {
                hideControls();
            }
        } else {
            showControls();
        }
    }

    private boolean isPointInView(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX <= location[0] + view.getWidth()
                && rawY >= location[1] && rawY <= location[1] + view.getHeight();
    }

    private void updateLiveUi(boolean isLive) {
        this.isLiveStream = isLive;
        if (liveBadge != null) {
            liveBadge.setVisibility(isLive ? View.VISIBLE : View.GONE);
        }
        if (seekbarContainer != null) {
            seekbarContainer.setVisibility(isLive ? View.GONE : View.VISIBLE);
        }
        if (llSkipBack != null) {
            llSkipBack.setVisibility(isLive ? View.GONE : View.VISIBLE);
        }
        if (llSkipForward != null) {
            llSkipForward.setVisibility(isLive ? View.GONE : View.VISIBLE);
        }
        if (flSubtitles != null && isLive) {
            flSubtitles.setVisibility(View.GONE);
        }
    }

    private String resolveDisplayTitle() {
        if (seriesTitle != null && !seriesTitle.trim().isEmpty() && !"Reproduciendo".equalsIgnoreCase(seriesTitle.trim()) && !"REX".equalsIgnoreCase(seriesTitle.trim())) {
            if (seasonNumber > 0 && episodeNumber > 0) {
                return String.format(Locale.US, "%s T%d:E%d", seriesTitle.trim(), seasonNumber, episodeNumber);
            }
            return seriesTitle.trim();
        }
        if (playbackTitle != null && !playbackTitle.trim().isEmpty() && !"REX".equalsIgnoreCase(playbackTitle.trim()) && !"Reproduciendo".equalsIgnoreCase(playbackTitle.trim())) {
            return playbackTitle.trim();
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

    private void togglePlayPause() {
        Player p = getCurrentPlayer();
        if (p == null) return;
        if (p.getPlayWhenReady()) {
            p.pause();
        } else {
            p.play();
        }
        updatePlayPauseIcons();
        resetAutoHide();
    }

    private void skip(long deltaMs) {
        Player p = getCurrentPlayer();
        if (p == null) return;
        long newPos = p.getCurrentPosition() + deltaMs;
        long duration = p.getDuration();
        if (newPos < 0) newPos = 0;
        if (duration > 0 && newPos > duration) newPos = duration;
        p.seekTo(newPos);
        updateSeekBar();
        updateTimeLabels();
        resetAutoHide();
    }

    private void toggleLock() {
        controlsLocked = !controlsLocked;
        updateLockIcon();
        if (controlsLocked) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void toggleFullscreen() {
        if (playerView != null) {
            if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                Toast.makeText(this, "Escalar: Rellenar pantalla", Toast.LENGTH_SHORT).show();
            } else if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                Toast.makeText(this, "Escalar: Zoom", Toast.LENGTH_SHORT).show();
            } else {
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                Toast.makeText(this, "Escalar: Ajustar a pantalla (Original)", Toast.LENGTH_SHORT).show();
            }
        }
        enterImmersiveMode();
        resetAutoHide();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showControls() {
        if (controlsLocked) return;
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

        startPositionUpdater();
        cancelAutoHide();
        Player p = getCurrentPlayer();
        if (p != null && p.getPlayWhenReady()) {
            resetAutoHide();
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
        controlsVisible = false;
        controlOverlay.animate()
                .alpha(0f)
                .setDuration(280)
                .withEndAction(() -> {
                    if (!controlsVisible) {
                        controlOverlay.setVisibility(View.GONE);
                    }
                })
                .start();
        stopPositionUpdater();
    }

    private void toggleControls() {
        if (controlsLocked) return;
        if (controlsVisible) {
            Player p = getCurrentPlayer();
            if (p != null && p.getPlayWhenReady()) {
                hideControls();
            }
        } else {
            showControls();
        }
    }

    private final Runnable autoHideRunnable = this::hideControls;

    private void resetAutoHide() {
        cancelAutoHide();
        Player p = getCurrentPlayer();
        if (controlsVisible && !controlsLocked && p != null && p.getPlayWhenReady()) {
            mainHandler.postDelayed(autoHideRunnable, CONTROLS_AUTO_HIDE_MS);
        }
    }

    private void cancelAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable);
    }

    private void updatePlayPauseIcons() {
        Player p = getCurrentPlayer();
        boolean playing = p != null && p.getPlayWhenReady();
        int iconRes = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        btnPlayCenter.setImageResource(iconRes);
    }

    private void updateLockIcon() {
        btnLock.setImageResource(controlsLocked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
    }

    private void updateSeekBar() {
        Player p = getCurrentPlayer();
        if (p == null || seekBar == null) return;
        long duration = p.getDuration();
        long position = p.getCurrentPosition();
        if (duration > 0) {
            int progress = (int) (position * seekBar.getMax() / duration);
            seekBar.setProgress(progress);
        }
    }

    private void updateTimeLabels() {
        Player p = getCurrentPlayer();
        if (p == null) return;
        long position = p.getCurrentPosition();
        long duration = p.getDuration();
        tvCurrentTime.setText(formatTime(position));
        tvDuration.setText(formatTime(duration));
    }

    private final Runnable positionUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (controlsVisible && getCurrentPlayer() != null) {
                updateSeekBar();
                updateTimeLabels();
                mainHandler.postDelayed(this, 250);
            }
        }
    };

    private void startPositionUpdater() {
        mainHandler.removeCallbacks(positionUpdateRunnable);
        mainHandler.post(positionUpdateRunnable);
    }

    private void stopPositionUpdater() {
        mainHandler.removeCallbacks(positionUpdateRunnable);
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

    private void showTrackSelector(int trackType) {
        if (trackType == C.TRACK_TYPE_TEXT && isAllCalidadSource()) {
            return;
        }
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();
        if (tracks == null || tracks.getGroups().isEmpty()) {
            Toast.makeText(this, "No hay pistas disponibles", Toast.LENGTH_SHORT).show();
            return;
        }

        String title;
        switch (trackType) {
            case C.TRACK_TYPE_VIDEO:
                title = "Calidad de video";
                break;
            case C.TRACK_TYPE_TEXT:
                title = "Subtítulos";
                break;
            case C.TRACK_TYPE_AUDIO:
                title = "Audio";
                break;
            default:
                return;
        }

        ArrayList<TrackOption> options = new ArrayList<>();
        if (trackType == C.TRACK_TYPE_TEXT) {
            options.add(new TrackOption("Desactivados", -1, -1, true));
        }

        for (int g = 0; g < tracks.getGroups().size(); g++) {
            Tracks.Group group = tracks.getGroups().get(g);
            if (group.getType() != trackType) continue;
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int t = 0; t < group.length; t++) {
                String label = buildTrackLabel(trackType, trackGroup, t);
                options.add(new TrackOption(label, g, t, group.isTrackSelected(t)));
            }
        }

        if (options.isEmpty()) {
            Toast.makeText(this, "No hay opciones disponibles", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_track_selector, null);
        TextView dialogTitle = dialogView.findViewById(R.id.dialog_title);
        RecyclerView trackList = dialogView.findViewById(R.id.track_list);

        dialogTitle.setText(title);
        trackList.setLayoutManager(new LinearLayoutManager(this));
        trackList.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = getLayoutInflater().inflate(R.layout.item_track, parent, false);
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                TrackOption opt = options.get(position);
                RadioButton radio = holder.itemView.findViewById(R.id.radio_track);
                TextView name = holder.itemView.findViewById(R.id.tv_track_name);
                radio.setChecked(opt.selected);
                name.setText(opt.label);
                holder.itemView.setOnClickListener(v -> {
                    if (opt.isOff) {
                        var p = player.getTrackSelectionParameters().buildUpon();
                        p.clearOverrides();
                        for (int gg = 0; gg < tracks.getGroups().size(); gg++) {
                            Tracks.Group grp = tracks.getGroups().get(gg);
                            if (grp.getType() == C.TRACK_TYPE_TEXT) {
                                p.addOverride(new TrackSelectionOverride(
                                        grp.getMediaTrackGroup(), Collections.<Integer>emptyList()));
                            }
                        }
                        player.setTrackSelectionParameters(p.build());
                    } else if (opt.groupIndex >= 0 && opt.trackIndex >= 0) {
                        applyTrackSelection(trackType, opt.groupIndex, opt.trackIndex);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return options.size();
            }
        });

        builder.setView(dialogView);
        builder.setNegativeButton("Cerrar", null);
        builder.show();
    }

    private static class TrackOption {
        final String label;
        final int groupIndex;
        final int trackIndex;
        final boolean selected;
        final boolean isOff;

        TrackOption(String label, int groupIndex, int trackIndex, boolean selected) {
            this.label = label;
            this.groupIndex = groupIndex;
            this.trackIndex = trackIndex;
            this.selected = selected;
            this.isOff = groupIndex < 0;
        }
    }

    private String buildTrackLabel(int trackType, TrackGroup group, int index) {
        String label = group.getFormat(index).label;
        String lang = group.getFormat(index).language;
        String mime = group.getFormat(index).sampleMimeType;
        int width = group.getFormat(index).width;
        int height = group.getFormat(index).height;

        if (trackType == C.TRACK_TYPE_VIDEO) {
            if (height > 0) {
                String quality = height >= 2160 ? "4K" : height >= 1440 ? "1440p" :
                                 height >= 1080 ? "1080p" : height >= 720 ? "720p" :
                                 height >= 480 ? "480p" : height >= 360 ? "360p" :
                                 height + "p";
                return quality;
            }
            return "Video";
        }
        if (trackType == C.TRACK_TYPE_AUDIO) {
            if (lang != null) {
                return lang.toUpperCase(Locale.US) + " (" + (label != null ? label : mime != null ? mime : "Audio") + ")";
            }
            return label != null ? label : "Audio";
        }
        if (trackType == C.TRACK_TYPE_TEXT) {
            if (lang != null) {
                return lang.toUpperCase(Locale.US);
            }
            return label != null ? label : "Subtítulos";
        }
        return label != null ? label : "Pista";
    }

    private void applyTrackSelection(int trackType, int groupIndex, int trackIndex) {
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();
        if (tracks == null || groupIndex >= tracks.getGroups().size()) return;

        var params = player.getTrackSelectionParameters();
        var builder = params.buildUpon();

        Tracks.Group selectedGroup = tracks.getGroups().get(groupIndex);
        TrackGroup trackGroup = selectedGroup.getMediaTrackGroup();

        if (trackType == C.TRACK_TYPE_VIDEO) {
            Format fmt = trackGroup.getFormat(trackIndex);
            if (fmt.width > 0 && fmt.height > 0) {
                builder.setMaxVideoSize(fmt.width, fmt.height);
                builder.setMinVideoSize(fmt.width, fmt.height);
            }
        } else {
            builder.clearOverrides();
            builder.addOverride(new TrackSelectionOverride(trackGroup, trackIndex));
        }

        player.setTrackSelectionParameters(builder.build());
        Toast.makeText(this, "Pista seleccionada", Toast.LENGTH_SHORT).show();
        resetAutoHide();
    }

    // --- Player setup (unchanged from original) ---

    private void setupPlayer() {
        httpDataSourceFactory = PlaybackCacheHelper.newHttpDataSourceFactory(this, null);
        cacheDataSourceFactory = disableCache
            ? null
            : PlaybackCacheHelper.newCacheDataSourceFactory(this, httpDataSourceFactory);

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
        disableAllCalidadTextTracks();
        playerView.setPlayer(player);
        playerView.setUseController(false);

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
                    boolean live = isLiveStream || (player != null && player.isCurrentMediaItemLive());
                    updateLiveUi(live);
                    updateTimeLabels();
                    if (controlsVisible) {
                        resetAutoHide();
                    } else {
                        showControls();
                    }
                }
                if (playbackState == Player.STATE_ENDED) {
                    onContentEnded();
                }
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                if (currentUrlIndex >= 0 && currentUrlIndex < urls.size()
                        && urls.get(currentUrlIndex).startsWith("https://pixeldrain.com/api/file/")) {
                    boolean hasAudio = false;
                    boolean hasSupportedAudio = false;
                    for (Tracks.Group group : tracks.getGroups()) {
                        if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
                        for (int i = 0; i < group.length; i++) {
                            hasAudio = true;
                            Format format = group.getTrackFormat(i);
                            hasSupportedAudio |= group.isTrackSupported(i);
                            Log.i(TAG, "PixelDrain audio: mime=" + format.sampleMimeType
                                    + ", channels=" + format.channelCount
                                    + ", supported=" + group.isTrackSupported(i)
                                    + ", selected=" + group.isTrackSelected(i));
                        }
                    }
                    if (hasAudio && !hasSupportedAudio && !audioFallbackInProgress) {
                        audioFallbackInProgress = true;
                        final int unsupportedUrlIndex = currentUrlIndex;
                        Log.w(TAG, "Audio PixelDrain no compatible; probando otro servidor TuDorama");
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed() || currentUrlIndex != unsupportedUrlIndex) return;
                            if (!launchFallbackPlayerIfAvailable()) {
                                showToast("Audio no compatible y no hay otro servidor disponible", Toast.LENGTH_LONG);
                                finishPlayer();
                            }
                        });
                    }
                }
                if (isAllCalidadSource()) {
                    disableAllCalidadTextTracks();
                    if (flSubtitles != null) flSubtitles.setVisibility(View.GONE);
                    return;
                }
                if (!isLiveStream && flSubtitles != null) {
                    boolean hasSubtitles = false;
                    for (Tracks.Group group : tracks.getGroups()) {
                        if (group.getType() == C.TRACK_TYPE_TEXT) {
                            hasSubtitles = true;
                            break;
                        }
                    }
                    flSubtitles.setVisibility(hasSubtitles ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcons();
                if (isPlaying) {
                    if (controlsVisible) resetAutoHide();
                } else {
                    cancelAutoHide();
                    showControls();
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
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                if (audioSessionId > 0) {
                    audioSessionStartedForCurrentUrl = true;
                }
            }
        });
    }

    private boolean isAllCalidadSource() {
        String source = seriesSource == null ? "" : seriesSource.trim();
        return postId > 0 && (source.isEmpty() || "allcalidad".equalsIgnoreCase(source));
    }

    private void disableAllCalidadTextTracks() {
        if (!isAllCalidadSource() || player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build());
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
        audioFallbackInProgress = false;
        videoWatchdogScheduled = false;
        videoWatchdogGeneration++;
        Map<String, String> headers = buildPlaybackHeaders(url);
        if (httpDataSourceFactory != null) {
            httpDataSourceFactory.setDefaultRequestProperties(headers);
        }

        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url.startsWith("https://pixeldrain.com/api/file/")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4);
        }
        MediaItem mediaItem = mediaItemBuilder.build();

        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);

        if (resumePositionMs > 0) {
            player.seekTo(resumePositionMs);
            resumePositionMs = 0;
        }

        if (isCasting && castPlayer != null) {
            castPlayer.setMediaItem(mediaItem);
            castPlayer.prepare();
        }

        startProgressSaver();
    }

    private void onContentEnded() {
        if (contentEnded) return;
        contentEnded = true;
        stopProgressSaver();

        if (contentId != null && !contentId.isEmpty()) {
            saveProgressNow();
            ContinueWatchingStore.markCompleted(this, contentId);
        }

        if (isSeriesEpisodePlayback() && nextEpisodeInfo != null) {
            playNextEpisode(true);
            return;
        }

        showControls();
    }

    private boolean isSeriesType(String type) {
        if (type == null) return false;
        String t = type.trim().toLowerCase(Locale.ROOT);
        return "tvshows".equals(t) || "animes".equals(t) || "series".equals(t) || "tv".equals(t);
    }

    private boolean isSeriesEpisodePlayback() {
        return (seasonNumber > 0 && episodeNumber > 0)
                || episodeId > 0
                || (postId > 0 && isSeriesType(mediaType));
    }

    private int parseEpisodeNumber() {
        if (episodeNumber > 0) return episodeNumber;
        String[] candidates = { playbackTitle, seriesTitle, seriesDetailUrl };
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
        if (seasonNumber > 0) return seasonNumber;
        String[] candidates = { playbackTitle, seriesTitle, seriesDetailUrl };
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
        List<AllCalidadScraper.Season> memorySeasons = SeriesSeasonsCache.get(
                seriesTitle, postId, seriesDetailUrl);
        if (memorySeasons != null && !memorySeasons.isEmpty()) {
            loadingSeasonData = false;
            seasonCache.clear();
            seasonCache.addAll(memorySeasons);
            nextEpisodeInfo = findNextEpisodeFromCache();
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
                        || (postId <= 0));

                if (isCuevana) {
                    try {
                        String query = (seriesDetailUrl != null && !seriesDetailUrl.isEmpty()) ? seriesDetailUrl : seriesTitle;
                        fetchedSeasons = new Cuevana3Scraper().getSeasons(query);
                    } catch (Exception ignored) {}
                }

                if ((fetchedSeasons == null || fetchedSeasons.isEmpty()) && postId > 0) {
                    try {
                        String postType = (mediaType == null || mediaType.trim().isEmpty()) ? "tvshows" : mediaType;
                        AllCalidadScraper.hit(postId, postType);
                        fetchedSeasons = AllCalidadScraper.getSeasons(postId);
                    } catch (Exception ignored) {}
                }

                if ((fetchedSeasons == null || fetchedSeasons.isEmpty()) && seriesTitle != null && !seriesTitle.trim().isEmpty()) {
                    try {
                        fetchedSeasons = new Cuevana3Scraper().getSeasons(seriesTitle);
                    } catch (Exception ignored) {}
                }

                final List<AllCalidadScraper.Season> resultSeasons = fetchedSeasons;
                runOnUiThread(() -> {
                    loadingSeasonData = false;
                    seasonCache.clear();
                    if (resultSeasons != null && !resultSeasons.isEmpty()) {
                        seasonCache.addAll(resultSeasons);
                        SeriesSeasonsCache.put(seriesTitle, postId, seriesDetailUrl, resultSeasons);
                    }
                    nextEpisodeInfo = findNextEpisodeFromCache();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> loadingSeasonData = false);
            }
        }).start();
    }

    private NextEpisodeInfo findNextEpisodeFromCache() {
        int effectiveSeason = parseSeasonNumber();
        int effectiveEpisode = parseEpisodeNumber();

        if (seasonCache.isEmpty()) {
            return null;
        }

        boolean foundCurrent = false;
        for (AllCalidadScraper.Season season : seasonCache) {
            if (season == null || season.episodes == null) continue;
            int sNum = season.seasonNumber > 0 ? season.seasonNumber : 1;
            for (AllCalidadScraper.Episode ep : season.episodes) {
                if (ep == null) continue;
                int eNum = ep.episodeNumber > 0 ? ep.episodeNumber : 1;
                int epId = ep.id > 0 ? ep.id : Math.abs((sNum + "x" + eNum).hashCode() % 1000000) + 100000;
                ep.id = epId;

                if (foundCurrent) {
                    return new NextEpisodeInfo(epId, sNum, eNum, ep.title);
                }

                if ((sNum == effectiveSeason && eNum == effectiveEpisode) || (episodeId > 0 && epId == episodeId)) {
                    seasonNumber = sNum;
                    episodeNumber = eNum;
                    foundCurrent = true;
                }
            }
        }

        if (foundCurrent) {
            return null;
        }

        for (AllCalidadScraper.Season season : seasonCache) {
            if (season == null || season.episodes == null) continue;
            int sNum = season.seasonNumber > 0 ? season.seasonNumber : 1;
            for (AllCalidadScraper.Episode ep : season.episodes) {
                if (ep == null) continue;
                int eNum = ep.episodeNumber > 0 ? ep.episodeNumber : 1;
                int epId = ep.id > 0 ? ep.id : Math.abs((sNum + "x" + eNum).hashCode() % 1000000) + 100000;
                ep.id = epId;
                boolean comesAfter = sNum > effectiveSeason || (sNum == effectiveSeason && eNum > effectiveEpisode);
                if (comesAfter) {
                    return new NextEpisodeInfo(epId, sNum, eNum, ep.title);
                }
            }
        }

        return null;
    }

    private final Runnable seriesNextMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            checkSeriesNextEpisodeTrigger();
            if (!contentEnded && !isDestroyed() && !isFinishing()) {
                mainHandler.postDelayed(this, 1500L);
            }
        }
    };

    private void startSeriesNextMonitor() {
        if (!isSeriesEpisodePlayback()) return;
        mainHandler.removeCallbacks(seriesNextMonitorRunnable);
        mainHandler.post(seriesNextMonitorRunnable);
    }

    private void checkSeriesNextEpisodeTrigger() {
        if (!isSeriesEpisodePlayback() || btnNextEpisode == null || isTransitioningToNextEpisode || contentEnded) {
            return;
        }
        if (nextEpisodeInfo == null && !loadingSeasonData && !seasonPreloadAttempted) {
            preloadSeasonData();
        }
        if (nextEpisodeInfo == null) {
            if (btnNextEpisode.getVisibility() == View.VISIBLE) {
                btnNextEpisode.setVisibility(View.GONE);
            }
            return;
        }

        Player p = getCurrentPlayer();
        if (p == null) return;
        long duration = p.getDuration();
        long position = p.getCurrentPosition();
        if (duration <= 0 || position <= 0) return;

        long remainingMs = duration - position;
        // Tres minutos antes del final, únicamente con siguiente validado.
        if (remainingMs <= 180_000L && remainingMs > 1500L) {
            String code = String.format(Locale.US, "T%d:E%d", nextEpisodeInfo.seasonNumber, nextEpisodeInfo.episodeNumber);
            if (remainingMs <= 30_000L) {
                int sec = (int) Math.ceil(remainingMs / 1000.0);
                btnNextEpisode.setText("Siguiente episodio (" + sec + "s)");
            } else {
                btnNextEpisode.setText("Siguiente episodio • " + code);
            }
            if (btnNextEpisode.getVisibility() != View.VISIBLE) {
                btnNextEpisode.setVisibility(View.VISIBLE);
                btnNextEpisode.setAlpha(0f);
                btnNextEpisode.animate().alpha(1f).setDuration(180L).start();
            }
        } else if (remainingMs > 195_000L && btnNextEpisode.getVisibility() == View.VISIBLE) {
            btnNextEpisode.setVisibility(View.GONE);
        }
    }

    private void playNextEpisode(boolean automatic) {
        if (nextEpisodeInfo == null) {
            if (!automatic) {
                Toast.makeText(this, "Ya estás en el último episodio disponible", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (isTransitioningToNextEpisode) return;
        isTransitioningToNextEpisode = true;
        if (!automatic && contentId != null && !contentId.isEmpty()) {
            ContinueWatchingStore.markCompleted(this, contentId);
        }
        if (btnNextEpisode != null) {
            btnNextEpisode.setVisibility(View.GONE);
        }
        progressBar.setVisibility(View.VISIBLE);

        final NextEpisodeInfo target = nextEpisodeInfo;
        String code = String.format(Locale.US, "T%d:E%d", target.seasonNumber, target.episodeNumber);
        Toast.makeText(this, "Cargando " + code + "...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                List<String> rawUrls = null;
                boolean isAllCalidad = postId > 0
                        && (seriesSource == null || seriesSource.isEmpty() || "allcalidad".equalsIgnoreCase(seriesSource));
                if (isAllCalidad && target.episodeId > 0) {
                    try {
                        AllCalidadScraper.hit(target.episodeId, "episodes");
                        AllCalidadScraper.PlayerData playerData = AllCalidadScraper.getPlayer(target.episodeId);
                        rawUrls = AllCalidadScraper.getPlayableUrls(playerData);
                    } catch (Exception ignored) {}
                }

                if (rawUrls == null || rawUrls.isEmpty()) {
                    try {
                        SmartScraperEngine engine = new SmartScraperEngine();
                        sv.edu.catolica.rex.models.MediaItem item = new sv.edu.catolica.rex.models.MediaItem(
                                seriesTitle, "", imageUrl != null ? imageUrl : "", seriesDetailUrl != null ? seriesDetailUrl : "", postId);
                        item.setMediaType(mediaType != null && !mediaType.isEmpty() ? mediaType : "tvshows");
                        item.setFuente(seriesSource);
                        rawUrls = engine.resolveEpisodeUrls(
                                PlayerExoActivity.this,
                                item,
                                target.seasonNumber,
                                target.episodeNumber,
                                target.episodeId
                        );
                    } catch (Exception ignored) {}
                }

                final List<String> resolvedUrls = rawUrls;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (resolvedUrls == null || resolvedUrls.isEmpty()) {
                        isTransitioningToNextEpisode = false;
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "No se pudo cargar el siguiente episodio", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ArrayList<String> directUrls = new ArrayList<>();
                    for (String u : resolvedUrls) {
                        if (u != null && (u.contains(".m3u8") || u.contains(".mp4"))) {
                            directUrls.add(u.trim());
                        }
                    }

                    if (!directUrls.isEmpty()) {
                        urls.clear();
                        urls.addAll(directUrls);
                        currentUrlIndex = 0;
                        episodeId = target.episodeId;
                        seasonNumber = target.seasonNumber;
                        episodeNumber = target.episodeNumber;
                        contentId = (postId > 0 && episodeId > 0)
                                ? (postId + "_" + episodeId)
                                : (seriesTitle + "_s" + seasonNumber + "e" + episodeNumber);
                        playbackTitle = seriesTitle + " - " + code;
                        setTitle(playbackTitle);
                        if (tvTitle != null) tvTitle.setText(playbackTitle);
                        resumePositionMs = 0L;
                        contentEnded = false;
                        isTransitioningToNextEpisode = false;
                        nextEpisodeInfo = null;
                        seasonPreloadAttempted = false;
                        nextEpisodeQueuedInStore = false;

                        playCurrentUrl();
                        preloadSeasonData();
                    } else {
                        Intent webIntent = PlayerContenidoActivity.createEpisodeIntent(
                                this,
                                new ArrayList<>(resolvedUrls),
                                seriesTitle + " - " + code,
                                seriesTitle,
                                postId,
                                mediaType,
                                target.episodeId,
                                target.seasonNumber,
                                target.episodeNumber
                        );
                        webIntent.putExtra("resume_position_ms", 0L);
                        webIntent.putExtra("image_url", imageUrl);
                        webIntent.putExtra("series_source", seriesSource);
                        webIntent.putExtra("series_detail_url", seriesDetailUrl);
                        startActivity(webIntent);
                        finish();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    isTransitioningToNextEpisode = false;
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Error cargando siguiente episodio", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void startProgressSaver() {
        if (progressSaveScheduled || contentId == null || contentId.isEmpty()) return;
        progressSaveScheduled = true;
        mainHandler.post(progressSaveRunnable);
    }

    private void stopProgressSaver() {
        progressSaveScheduled = false;
        mainHandler.removeCallbacks(progressSaveRunnable);
    }

    private final Runnable progressSaveRunnable = new Runnable() {
        @Override
        public void run() {
            saveProgressNow();
            if (progressSaveScheduled && player != null) {
                mainHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS);
            }
        }
    };

    private void saveProgressNow() {
        saveProgressNow(false);
    }

    private void saveProgressNow(boolean syncToRemote) {
        Player p = getCurrentPlayer();
        if (p == null || contentId == null || contentId.isEmpty()) return;
        long positionMs = p.getCurrentPosition();
        long durationMs = p.getDuration();
        if (positionMs <= 0) return;
        if (durationMs < 0) durationMs = 0;

        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setContentId(contentId);
        item.setTitle(playbackTitle);
        item.setSeriesTitle(seriesTitle);
        item.setImageUrl(imageUrl);
        item.setDetailUrl(seriesDetailUrl);
        item.setSource(seriesSource);
        String safeMediaType = mediaType;
        if (safeMediaType == null || safeMediaType.trim().isEmpty()) {
            safeMediaType = (episodeId > 0 || (seriesTitle != null && !seriesTitle.isEmpty())) ? "tvshows" : "movies";
        }
        item.setMediaType(safeMediaType);
        item.setEpisodeId(episodeId);
        item.setSeasonNumber(seasonNumber);
        item.setEpisodeNumber(episodeNumber);
        item.setPostId(postId > 0 ? postId : 0);
        ContinueWatchingStore.applyPlaybackProgress(item, positionMs, durationMs);
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
                    ContinueWatchingStore.save(PlayerExoActivity.this, itemToSave);
                    ContinueWatchingStore.queueNextEpisodeIfAvailable(PlayerExoActivity.this, itemToSave);
                } else if (!nextEpisodeQueuedInStore) {
                    if (syncToRemote) {
                        ContinueWatchingStore.save(PlayerExoActivity.this, itemToSave);
                    } else {
                        ContinueWatchingStore.saveLocal(PlayerExoActivity.this, itemToSave);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void tryNextStream() {
        String currentUrl = (currentUrlIndex >= 0 && currentUrlIndex < urls.size())
                ? urls.get(currentUrlIndex) : "unknown";
        if (currentUrlIndex + 1 < urls.size()) {
            currentUrlIndex++;
            showToast("Cambiando stream " + (currentUrlIndex + 1));
            playCurrentUrl();
            return;
        }

        if (launchFallbackPlayerIfAvailable()) {
            return;
        }

        showToast("No se pudo reproducir con los streams disponibles", Toast.LENGTH_LONG);
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
        String cookies = cookiesFor(url);
        if (cookies != null && !cookies.isEmpty() && !headers.containsKey("Cookie")) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    private String cookiesFor(String url) {
        try {
            android.webkit.CookieManager manager = android.webkit.CookieManager.getInstance();
            if (manager == null) {
                return null;
            }
            return manager.getCookie(url);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void scheduleVideoWatchdog() {
        if (videoWatchdogScheduled || player == null) {
            return;
        }
        videoWatchdogScheduled = true;
        final long generation = videoWatchdogGeneration;
        mainHandler.postDelayed(() -> {
            videoWatchdogScheduled = false;
            if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) {
                return;
            }
            int state = player.getPlaybackState();
            if (state == Player.STATE_READY) {
                if (player.isPlaying()) {
                    mainHandler.postDelayed(() -> {
                        if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) return;
                        if (!isFinishing()) {
                            showToast("Este stream no entrega video. Probando otra fuente.");
                        }
                        tryNextStream();
                    }, 10_000L);
                } else {
                    mainHandler.postDelayed(() -> {
                        if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) return;
                        showToast("Stream sin respuesta. Probando otra fuente.");
                        tryNextStream();
                    }, 10_000L);
                }
            } else if (state == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(() -> {
                    if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) return;
                    if (!isFinishing()) {
                        showToast("Stream lento. Probando otra fuente.");
                        tryNextStream();
                    }
                }, 10_000L);
            }
        }, VIDEO_WATCHDOG_MS);
    }

    private boolean launchFallbackPlayerIfAvailable() {
        String embedUrl = trimToNull(fallbackEmbedUrl);
        ArrayList<String> urls = new ArrayList<>();
        if (fallbackEmbedUrls != null) {
            for (String fallback : fallbackEmbedUrls) {
                if (trimToNull(fallback) != null && !urls.contains(fallback)) {
                    urls.add(fallback);
                }
            }
        }
        if (urls.isEmpty() && embedUrl != null) {
            urls.add(embedUrl);
        }
        if (urls.isEmpty()) {
            return false;
        }
        long resumeMs = 0L;
        Player p = getCurrentPlayer();
        if (p != null) {
            resumeMs = p.getCurrentPosition();
        }
        Intent intent = PlayerContenidoActivity.createFullIntent(this, urls, playbackTitle,
                seriesTitle, postId, mediaType,
                episodeId, seasonNumber, episodeNumber,
                imageUrl, resumeMs);
        intent.putExtra("disable_exo_switch", true);
        intent.putExtra("exo_content_id", contentId);
        intent.putExtra("series_source", seriesSource);
        intent.putExtra("series_detail_url", seriesDetailUrl);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
        return true;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String originFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void finishPlayer() {
        if (isExiting) return;
        isExiting = true;
        stopProgressSaver();
        stopPositionUpdater();
        cancelAutoHide();
        saveProgressNow(true);
        mainHandler.removeCallbacksAndMessages(null);
        Player p = getCurrentPlayer();
        if (p != null) {
            try {
                p.stop();
                p.release();
            } catch (Exception ignored) {}
        }
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
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

            if (btnNextEpisode != null && btnNextEpisode.getVisibility() == View.VISIBLE && btnNextEpisode.hasFocus()) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    playNextEpisode(false);
                    return true;
                }
            }

            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case android.view.KeyEvent.KEYCODE_HEADSETHOOK:
                    togglePlayPause();
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                    Player pPlay = getCurrentPlayer();
                    if (pPlay != null) pPlay.play();
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                    Player pPause = getCurrentPlayer();
                    if (pPause != null) pPause.pause();
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    skip(10000);
                    if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
                    return true;

                case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                    skip(-10000);
                    if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
                    return true;

                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!controlsVisible) {
                        showControls();
                    } else {
                        View focused = getCurrentFocus();
                        if (focused != null && focused != controlOverlay && (focused.isClickable() || focused instanceof android.widget.Button || focused instanceof android.widget.ImageButton)) {
                            focused.performClick();
                        } else {
                            togglePlayPause();
                        }
                    }
                    return true;

                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!controlsVisible) {
                        skip(-10000);
                        if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
                        return true;
                    } else {
                        View focused = getCurrentFocus();
                        if (focused instanceof SeekBar) {
                            skip(-10000);
                            if (doubleTapHelper != null) doubleTapHelper.showLeftFeedback(10);
                            return true;
                        }
                        resetAutoHide();
                        break;
                    }

                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!controlsVisible) {
                        skip(10000);
                        if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
                        return true;
                    } else {
                        View focused = getCurrentFocus();
                        if (focused instanceof SeekBar) {
                            skip(10000);
                            if (doubleTapHelper != null) doubleTapHelper.showRightFeedback(10);
                            return true;
                        }
                        resetAutoHide();
                        break;
                    }

                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!controlsVisible) {
                        showControls();
                        return true;
                    } else {
                        resetAutoHide();
                        break;
                    }
            }
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        finishPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        Player p = getCurrentPlayer();
        if (p != null) {
            p.play();
        }
    }

    @Override
    protected void onPause() {
        Player p = getCurrentPlayer();
        if (p != null) {
            p.pause();
        }
        saveProgressNow(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (doubleTapHelper != null) {
            doubleTapHelper.onDestroy();
        }
        videoWatchdogGeneration++;
        stopProgressSaver();
        stopPositionUpdater();
        cancelAutoHide();
        saveProgressNow(true);
        mainHandler.removeCallbacksAndMessages(null);
        PlaybackCacheHelper.cancelPreload();
        if (castContext != null && castStateListener != null) {
            castContext.removeCastStateListener(castStateListener);
            castStateListener = null;
        }
        if (castPlayer != null) {
            castPlayer.release();
            castPlayer = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        saveExecutor.shutdown();
        super.onDestroy();
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
}
