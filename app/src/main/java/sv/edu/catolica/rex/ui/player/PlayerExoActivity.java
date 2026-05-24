package sv.edu.catolica.rex.ui.player;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
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
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.Map;
import sv.edu.catolica.rex.R;

public class PlayerExoActivity extends AppCompatActivity {

    private static final String EXTRA_URLS = "exo_urls";
    private static final String EXTRA_TITLE = "exo_title";
    private static final int TIMEOUT_MS = 20_000;
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
    private int currentUrlIndex = 0;

    public static void start(Context context, ArrayList<String> streamUrls, String title) {
        Intent intent = new Intent(context, PlayerExoActivity.class);
        intent.putStringArrayListExtra(EXTRA_URLS, streamUrls);
        intent.putExtra(EXTRA_TITLE, title);
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
        setTitle(title == null || title.trim().isEmpty() ? "Reproduciendo" : title);

        setupPlayer();
        playCurrentUrl();
    }

    private void setupPlayer() {
        // Shared network stack tuned for smoother playback and lower startup jitter.
        httpDataSourceFactory = PlaybackCacheHelper.newHttpDataSourceFactory(null);
        cacheDataSourceFactory = PlaybackCacheHelper.newCacheDataSourceFactory(this, httpDataSourceFactory);

        // Buffer policy:
        // - minBufferMs: 15s before playback starts, so short stalls do not hit immediately.
        // - maxBufferMs: 60s cap to avoid over-buffering and memory pressure.
        // - bufferForPlaybackMs: 15s initial startup threshold for aggressive prebuffering.
        // - bufferForPlaybackAfterRebufferMs: 8s before resuming after a stall.
        // - backBuffer: 30s retained behind the playhead for fast seek-back.
        loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 15_000, 8_000)
            .setBackBuffer(30_000, true)
            .build();

        bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();

        player = new ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheDataSourceFactory))
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
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                tryNextStream();
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
        if (currentUrlIndex + 1 < urls.size()) {
            currentUrlIndex++;
            Toast.makeText(this, "Cambiando stream " + (currentUrlIndex + 1), Toast.LENGTH_SHORT).show();
            playCurrentUrl();
            return;
        }

        Toast.makeText(this, "No se pudo reproducir con los streams disponibles", Toast.LENGTH_LONG).show();
        finish();
    }

    private Map<String, String> buildPlaybackHeaders(String url) {
        return PlaybackCacheHelper.buildPlaybackHeaders(url);
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
