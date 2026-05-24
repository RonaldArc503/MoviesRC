package sv.edu.catolica.rex.ui.player;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.database.StandaloneDatabaseProvider;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public final class PlaybackCacheHelper {

    private static final long CACHE_SIZE_BYTES = 512L * 1024L * 1024L;
    private static final long PRELOAD_BYTES = 5L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final Object CACHE_LOCK = new Object();
    private static final AtomicReference<SimpleCache> CACHE_REF = new AtomicReference<>();
    private static final AtomicReference<CacheWriter> ACTIVE_WRITER = new AtomicReference<>();
    private static final AtomicReference<Future<?>> ACTIVE_TASK = new AtomicReference<>();

    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rex-preload-cache");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private PlaybackCacheHelper() {
    }

    public static SimpleCache getCache(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null) {
            return null;
        }

        SimpleCache cache = CACHE_REF.get();
        if (cache != null) {
            return cache;
        }

        synchronized (CACHE_LOCK) {
            cache = CACHE_REF.get();
            if (cache != null) {
                return cache;
            }

            File cacheDir = new File(appContext.getCacheDir(), "rex_media_cache");
            LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES);
            StandaloneDatabaseProvider databaseProvider = new StandaloneDatabaseProvider(appContext);
            cache = new SimpleCache(cacheDir, evictor, databaseProvider);
            CACHE_REF.set(cache);
            return cache;
        }
    }

    public static CacheDataSource.Factory newCacheDataSourceFactory(Context context,
                                                                    DefaultHttpDataSource.Factory upstreamFactory) {
        CacheDataSource.Factory factory = new CacheDataSource.Factory();
        SimpleCache cache = getCache(context);
        if (cache != null) {
            factory.setCache(cache);
            factory.setCacheWriteDataSinkFactory(new CacheDataSink.Factory().setCache(cache));
            factory.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        }
        if (upstreamFactory != null) {
            factory.setUpstreamDataSourceFactory(upstreamFactory);
        }
        return factory;
    }

    public static DefaultHttpDataSource.Factory newHttpDataSourceFactory(Map<String, String> headers) {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS);
        if (headers != null && !headers.isEmpty()) {
            factory.setDefaultRequestProperties(headers);
        }
        return factory;
    }

    public static Map<String, String> buildPlaybackHeaders(String url) {
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        if (url == null) {
            return headers;
        }

        String lc = url.trim().toLowerCase(Locale.ROOT);
        if (lc.contains("vimeos.net") || lc.contains("s12.vimeos.net")) {
            headers.put("Referer", "https://allcalidad.re/");
            headers.put("Origin", "https://allcalidad.re");
        } else if (lc.contains("minochinos.com")
                || lc.contains("hglink.to")
                || lc.contains("bysedikamoum.com")
                || lc.contains("voe.sx")
                || lc.contains("voe.network")) {
            headers.put("Referer", "https://allcalidad.re/");
            headers.put("Origin", "https://allcalidad.re");
        }
        return headers;
    }

    public static void preloadFirstChunk(Context context, String url, Map<String, String> headers) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return;
        }

        cancelPreload();

        DefaultHttpDataSource.Factory upstreamFactory = newHttpDataSourceFactory(headers);
        CacheDataSource.Factory cacheFactory = newCacheDataSourceFactory(context, upstreamFactory);
        CacheDataSource dataSource = cacheFactory.createDataSource();
        DataSpec dataSpec = new DataSpec.Builder()
                .setUri(Uri.parse(url.trim()))
                .setPosition(0)
                .setLength(PRELOAD_BYTES)
                .build();

        CacheWriter writer = new CacheWriter(dataSource, dataSpec, null, null);
        ACTIVE_WRITER.set(writer);

        Future<?> task = PRELOAD_EXECUTOR.submit(() -> {
            try {
                writer.cache();
            } catch (IOException ignored) {
                // Preload is best-effort; playback must continue even if cache warm-up fails.
            } catch (Exception ignored) {
                // Ignore cancellation/interrupt races.
            } finally {
                if (ACTIVE_WRITER.get() == writer) {
                    ACTIVE_WRITER.compareAndSet(writer, null);
                }
                ACTIVE_TASK.compareAndSet(null, null);
            }
        });
        ACTIVE_TASK.set(task);
    }

    public static void cancelPreload() {
        CacheWriter writer = ACTIVE_WRITER.getAndSet(null);
        if (writer != null) {
            try {
                writer.cancel();
            } catch (Exception ignored) {
                // Safe cancellation path.
            }
        }

        Future<?> task = ACTIVE_TASK.getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }
    }
}
