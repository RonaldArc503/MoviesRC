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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
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
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    private static final int TIMEOUT_MS = 20_000;
    private static final long VIDEO_WATCHDOG_MS = 15_000L;
    private static final long PROGRESS_SAVE_INTERVAL_MS = 10_000L;
    private static final long CONTROLS_AUTO_HIDE_MS = 3000L;
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
    private boolean audioSessionStartedForCurrentUrl;
    private boolean videoWatchdogScheduled;
    private int videoWatchdogGeneration;
    private String playbackTitle;
    private String requestReferer;
    private String requestOrigin;
    private String fallbackEmbedUrl;
    private String fallbackReferer;

    private String contentId = "";
    private String seriesTitle = "";
    private String imageUrl = "";
    private int postId = -1;
    private String mediaType = "";
    private int episodeId = -1;
    private int seasonNumber = -1;
    private int episodeNumber = -1;
    private long resumePositionMs = 0;
    private boolean contentEnded = false;
    private boolean progressSaveScheduled = false;

    // Controls overlay
    private View controlOverlay;
    private View scrimTop;
    private View scrimBottom;
    private View topBar;
    private View bottomBar;
    private View centerControls;
    private ImageButton btnPlayCenter;
    private ImageButton btnPlaySmall;
    private ImageButton btnSkipBack;
    private ImageButton btnSkipForward;
    private ImageButton btnLock;
    private ImageButton btnQuality;
    private ImageButton btnSubtitles;
    private ImageButton btnAudio;
    private ImageButton btnSpeed;
    private ImageButton btnFullscreen;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvDuration;
    private TextView tvTitle;
    private boolean controlsVisible = true;
    private boolean controlsLocked = false;
    private long lastVideoTapUpMs = 0L;
    private float lastVideoTapX = -1f;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        playerView = findViewById(R.id.player_view);
        progressBar = findViewById(R.id.buffer_progress);
        disableCache = getIntent().getBooleanExtra(EXTRA_DISABLE_CACHE, false);
        requestReferer = getIntent().getStringExtra(EXTRA_REFERER);
        requestOrigin = getIntent().getStringExtra(EXTRA_ORIGIN);
        fallbackEmbedUrl = getIntent().getStringExtra(EXTRA_FALLBACK_EMBED_URL);
        fallbackReferer = getIntent().getStringExtra(EXTRA_FALLBACK_REFERER);

        contentId = getIntent().getStringExtra(EXTRA_CONTENT_ID);
        seriesTitle = getIntent().getStringExtra(EXTRA_SERIES_TITLE);
        imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        postId = getIntent().getIntExtra(EXTRA_POST_ID, -1);
        mediaType = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
        episodeId = getIntent().getIntExtra(EXTRA_EPISODE_ID, -1);
        seasonNumber = getIntent().getIntExtra(EXTRA_SEASON_NUMBER, -1);
        episodeNumber = getIntent().getIntExtra(EXTRA_EPISODE_NUMBER, -1);
        resumePositionMs = getIntent().getLongExtra(EXTRA_RESUME_POSITION_MS, 0L);

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

        initControls();
        setupPlayer();
        playCurrentUrl();
    }

    private void initControls() {
        controlOverlay = findViewById(R.id.control_overlay);
        scrimTop = findViewById(R.id.scrim_top);
        scrimBottom = findViewById(R.id.scrim_bottom);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        centerControls = findViewById(R.id.center_controls);
        btnPlayCenter = findViewById(R.id.btn_play_center);
        btnPlaySmall = findViewById(R.id.btn_play_small);
        btnSkipBack = findViewById(R.id.btn_skip_back);
        btnSkipForward = findViewById(R.id.btn_skip_forward);
        btnLock = findViewById(R.id.btn_lock);
        btnQuality = findViewById(R.id.btn_quality);
        btnSubtitles = findViewById(R.id.btn_subtitles);
        btnAudio = findViewById(R.id.btn_audio);
        btnSpeed = findViewById(R.id.btn_speed);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        seekBar = findViewById(R.id.player_seekbar);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvDuration = findViewById(R.id.tv_duration);
        tvTitle = findViewById(R.id.tv_player_title);

        tvTitle.setText(playbackTitle);

        controlOverlay.setVisibility(View.VISIBLE);
        controlOverlay.setOnClickListener(v -> toggleControls());

        btnPlayCenter.setOnClickListener(v -> togglePlayPause());
        btnPlaySmall.setOnClickListener(v -> togglePlayPause());
        btnSkipBack.setOnClickListener(v -> skip(-10000));
        btnSkipForward.setOnClickListener(v -> skip(10000));

        btnLock.setOnClickListener(v -> toggleLock());
        btnQuality.setOnClickListener(v -> showTrackSelector(C.TRACK_TYPE_VIDEO));
        btnSubtitles.setOnClickListener(v -> showTrackSelector(C.TRACK_TYPE_TEXT));
        btnAudio.setOnClickListener(v -> showTrackSelector(C.TRACK_TYPE_AUDIO));
        btnSpeed.setOnClickListener(v -> showSpeedSelector());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        playerView.setOnTouchListener((v, event) -> handleVideoSurfaceTouch(v, event));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean fromUser = false;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                this.fromUser = fromUser;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                fromUser = true;
                cancelAutoHide();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (fromUser && player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        long seekPos = duration * seekBar.getProgress() / seekBar.getMax();
                        player.seekTo(seekPos);
                        updateTimeLabels();
                    }
                }
                fromUser = false;
                resetAutoHide();
            }
        });
    }

    private boolean handleVideoSurfaceTouch(View surface, MotionEvent event) {
        if (event == null || controlsLocked) return false;
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return false;

        long now = event.getEventTime();
        int doubleTapTimeout = android.view.ViewConfiguration.getDoubleTapTimeout();
        float maxDistance = 80f * getResources().getDisplayMetrics().density;
        boolean doubleTap = lastVideoTapUpMs > 0
                && now - lastVideoTapUpMs <= doubleTapTimeout
                && Math.abs(event.getX() - lastVideoTapX) <= maxDistance;

        lastVideoTapUpMs = now;
        lastVideoTapX = event.getX();

        if (doubleTap) {
            lastVideoTapUpMs = 0L;
            if (event.getX() < surface.getWidth() / 2f) {
                skip(-10000);
                Toast.makeText(this, "-10 s", Toast.LENGTH_SHORT).show();
            } else {
                skip(10000);
                Toast.makeText(this, "+10 s", Toast.LENGTH_SHORT).show();
            }
            showControls();
            return true;
        }

        showControls();
        return false;
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.getPlayWhenReady()) {
            player.pause();
        } else {
            player.play();
        }
        updatePlayPauseIcons();
        resetAutoHide();
    }

    private void skip(long deltaMs) {
        if (player == null) return;
        long newPos = player.getCurrentPosition() + deltaMs;
        long duration = player.getDuration();
        if (newPos < 0) newPos = 0;
        if (duration > 0 && newPos > duration) newPos = duration;
        player.seekTo(newPos);
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
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            int bars = WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (controller.getSystemBarsBehavior() ==
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                    controller.show(bars);
                    controller.setSystemBarsBehavior(
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_TOUCH);
                } else {
                    controller.hide(bars);
                    controller.setSystemBarsBehavior(
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
                if ((uiOptions & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0) {
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                } else {
                    enterImmersiveMode();
                }
            }
        }
        resetAutoHide();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showControls() {
        if (controlsLocked) return;
        controlsVisible = true;
        controlOverlay.setVisibility(View.VISIBLE);
        controlOverlay.setAlpha(0f);
        controlOverlay.animate().alpha(1f).setDuration(200).start();
        startPositionUpdater();
        cancelAutoHide();
        if (player != null && player.getPlayWhenReady()) {
            resetAutoHide();
        }
    }

    private void hideControls() {
        controlsVisible = false;
        controlOverlay.animate()
                .alpha(0f)
                .setDuration(300)
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
            if (player != null && player.getPlayWhenReady()) {
                hideControls();
            }
        } else {
            showControls();
        }
    }

    private final Runnable autoHideRunnable = this::hideControls;

    private void resetAutoHide() {
        cancelAutoHide();
        if (controlsVisible && !controlsLocked && player != null && player.getPlayWhenReady()) {
            mainHandler.postDelayed(autoHideRunnable, CONTROLS_AUTO_HIDE_MS);
        }
    }

    private void cancelAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable);
    }

    private void updatePlayPauseIcons() {
        boolean playing = player != null && player.getPlayWhenReady();
        int iconRes = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        btnPlayCenter.setImageResource(iconRes);
        if (btnPlaySmall != null) {
            btnPlaySmall.setImageResource(iconRes);
        }
    }

    private void updateLockIcon() {
        btnLock.setImageResource(controlsLocked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
    }

    private void updateSeekBar() {
        if (player == null || seekBar == null) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        if (duration > 0) {
            int progress = (int) (position * seekBar.getMax() / duration);
            seekBar.setProgress(progress);
        }
    }

    private void updateTimeLabels() {
        if (player == null) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        tvCurrentTime.setText(formatTime(position));
        tvDuration.setText(formatTime(duration));
    }

    private final Runnable positionUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (controlsVisible && player != null) {
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

    private void showSpeedSelector() {
        if (player == null) return;
        final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = {"0.5x", "0.75x", "Normal", "1.25x", "1.5x", "1.75x", "2x"};
        float current = player.getPlaybackParameters().speed;
        int checked = 2;
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(current - speeds[i]) < 0.01f) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Velocidad")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    player.setPlaybackSpeed(speeds[which]);
                    resetAutoHide();
                    dialog.dismiss();
                })
                .setNegativeButton("Cerrar", null)
                .show();
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
        httpDataSourceFactory = PlaybackCacheHelper.newHttpDataSourceFactory(null);
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

        if (resumePositionMs > 0) {
            player.seekTo(resumePositionMs);
            resumePositionMs = 0;
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
        showControls();
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
        if (player == null || contentId == null || contentId.isEmpty()) return;
        long positionMs = player.getCurrentPosition();
        long durationMs = player.getDuration();
        if (positionMs <= 0 || durationMs <= 0) return;

        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setContentId(contentId);
        item.setTitle(playbackTitle);
        item.setSeriesTitle(seriesTitle);
        item.setImageUrl(imageUrl);
        item.setPostId(postId > 0 ? postId : 0);
        item.setMediaType(mediaType);
        item.setEpisodeId(episodeId);
        item.setSeasonNumber(seasonNumber);
        item.setEpisodeNumber(episodeNumber);
        item.setPositionMs(positionMs);
        item.setDurationMs(durationMs);
        int pct = (int) (positionMs * 100 / durationMs);
        item.setProgressPercent(Math.min(99, pct));
        item.setCompleted(false);

        ContinueWatchingStore.save(this, item);
    }

    private void tryNextStream() {
        String currentUrl = (currentUrlIndex >= 0 && currentUrlIndex < urls.size())
                ? urls.get(currentUrlIndex) : "unknown";
        if (currentUrlIndex + 1 < urls.size()) {
            currentUrlIndex++;
            Toast.makeText(this, "Cambiando stream " + (currentUrlIndex + 1), Toast.LENGTH_SHORT).show();
            playCurrentUrl();
            return;
        }

        if (launchFallbackPlayerIfAvailable()) {
            return;
        }

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
        if (videoRenderedForCurrentUrl) {
            return;
        }
        videoWatchdogScheduled = true;
        final int generation = videoWatchdogGeneration;
        mainHandler.postDelayed(() -> {
            if (player == null || generation != videoWatchdogGeneration) {
                return;
            }
            if (videoRenderedForCurrentUrl) {
                return;
            }
            int state = player.getPlaybackState();
            if (state == Player.STATE_READY && player.getPlayWhenReady()) {
                if (audioSessionStartedForCurrentUrl) {
                    mainHandler.postDelayed(() -> {
                        if (player == null || generation != videoWatchdogGeneration) return;
                        if (videoRenderedForCurrentUrl) return;
                        if (audioSessionStartedForCurrentUrl) {
                            Toast.makeText(this, "Stream solo audio. Probando otra fuente.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Este stream no entrega video. Probando otra fuente.", Toast.LENGTH_SHORT).show();
                        }
                        tryNextStream();
                    }, 5_000L);
                } else {
                    mainHandler.postDelayed(() -> {
                        if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) return;
                        Toast.makeText(this, "Stream sin respuesta. Probando otra fuente.", Toast.LENGTH_SHORT).show();
                        tryNextStream();
                    }, 5_000L);
                }
            } else if (state == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(() -> {
                    if (player == null || generation != videoWatchdogGeneration || videoRenderedForCurrentUrl) return;
                    if (player.getPlaybackState() == Player.STATE_BUFFERING) {
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
        saveProgressNow();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        videoWatchdogGeneration++;
        stopProgressSaver();
        stopPositionUpdater();
        cancelAutoHide();
        saveProgressNow();
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
