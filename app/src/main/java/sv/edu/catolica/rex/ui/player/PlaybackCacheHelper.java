package sv.edu.catolica.rex.ui.player;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
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

/**
 * Helper singleton para gestionar la caché de Media3 y el pre-cargado de chunks
 * de video en ExoPlayer. Compatible con Media3 1.5.x.
 *
 * <p>Todas las clases del paquete {@code datasource.cache} llevan la anotación
 * {@code @UnstableApi}; se usa {@code @OptIn} a nivel de clase para silenciar
 * los avisos en el IDE sin necesitar suprimir línea a línea.</p>
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackCacheHelper {

    private static final long CACHE_SIZE_BYTES  = 512L * 1024L * 1024L; // 512 MB
    private static final long PRELOAD_BYTES      = 5L  * 1024L * 1024L; //   5 MB
    private static final int  CONNECT_TIMEOUT_MS = 15_000;
    private static final int  READ_TIMEOUT_MS    = 20_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final Object                        CACHE_LOCK   = new Object();
    private static final AtomicReference<SimpleCache>  CACHE_REF    = new AtomicReference<>();
    private static final AtomicReference<Future<?>>    ACTIVE_TASK  = new AtomicReference<>();

    private static final ExecutorService PRELOAD_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "rex-preload-cache");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private PlaybackCacheHelper() { /* no instances */ }

    // ─── Cache singleton ─────────────────────────────────────────────────────

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
            LeastRecentlyUsedCacheEvictor evictor =
                    new LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES);
            StandaloneDatabaseProvider databaseProvider =
                    new StandaloneDatabaseProvider(appContext);
            cache = new SimpleCache(cacheDir, evictor, databaseProvider);
            CACHE_REF.set(cache);
            return cache;
        }
    }

    // ─── Factory helpers ─────────────────────────────────────────────────────

    public static CacheDataSource.Factory newCacheDataSourceFactory(
            Context context,
            DefaultHttpDataSource.Factory upstreamFactory) {

        CacheDataSource.Factory factory = new CacheDataSource.Factory();
        SimpleCache cache = getCache(context);
        if (cache != null) {
            factory.setCache(cache);
            factory.setCacheWriteDataSinkFactory(
                    new CacheDataSink.Factory().setCache(cache));
            factory.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        }
        if (upstreamFactory != null) {
            factory.setUpstreamDataSourceFactory(upstreamFactory);
        }
        return factory;
    }

    public static DefaultHttpDataSource.Factory newHttpDataSourceFactory(
            Map<String, String> headers) {

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

    // ─── Playback headers ────────────────────────────────────────────────────

    public static Map<String, String> buildPlaybackHeaders(String url) {
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        if (url == null) {
            return headers;
        }
        String lc = url.trim().toLowerCase(Locale.ROOT);

        // ── AllCalidad / Pelispedia ───────────────────────────────────────────
        if (lc.contains("vimeos.net") || lc.contains("s12.vimeos.net")) {
            headers.put("Referer", "https://allcalidad.re/");
            headers.put("Origin",  "https://allcalidad.re");
            headers.put("Accept",  "*/*");

        // ── Fútbol libre y dominios relacionados ───────────────────────────────
        } else if (lc.contains("futbol-libre")
                || lc.contains("esvideofy")
                || lc.contains("telerium")
                || lc.contains("daddylive")
                || lc.contains("topembed")) {
            headers.put("Referer", "https://futbol-libre.su/");
            headers.put("Origin",  "https://futbol-libre.su");
            headers.put("Accept",  "*/*");
            // Algunos servidores requieren conexión keep-alive
            headers.put("Connection", "keep-alive");

        // ── JWPlayer ──────────────────────────────────────────────────────────
        } else if (lc.contains("cdn.jwplayer.com") || lc.contains("jwpsrv.com")) {
            headers.put("Referer", "https://cdn.jwplayer.com/");
            headers.put("Origin",  "https://cdn.jwplayer.com");
            headers.put("Accept",  "*/*");

        // ── Otros providers de contenido ───────────────────────────────────────
        } else if (lc.contains("minochinos.com")
                || lc.contains("hglink.to")
                || lc.contains("bysedikamoum.com")
                || lc.contains("voe.sx")
                || lc.contains("voe.network")) {
            headers.put("Referer", "https://allcalidad.re/");
            headers.put("Origin",  "https://allcalidad.re");
            headers.put("Accept",  "*/*");

        // ── Streams genéricos HLS/DASH (probar con referer del propio dominio) ─
        } else {
            // Para URLs que terminan en .m3u8, .mpd o .mp4 sin dominio conocido,
            // intentar extraer un referer desde la propia URL (el origen del servidor)
            try {
                if (lc.endsWith(".m3u8") || lc.endsWith(".mpd") || lc.endsWith(".mp4")) {
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost();
                    if (host != null) {
                        String origin = uri.getScheme() + "://" + host;
                        headers.put("Referer", origin + "/");
                        headers.put("Origin", origin);
                        headers.put("Accept", "*/*");
                    }
                }
            } catch (Exception ignored) {
                // Si falla el parseo, no agregar headers extra
            }
        }

        return headers;
    }

    // ─── Pre-cargado de chunk inicial ────────────────────────────────────────

    /**
     * Pre-carga los primeros {@code PRELOAD_BYTES} del stream en segundo plano
     * (prioridad mínima) para reducir el tiempo de inicio de la reproducción.
     * Se cancela la tarea anterior antes de iniciar una nueva.
     *
     * <p>Nota: {@code CacheWriter} en Media3 1.5.x no expone un método
     * {@code cancel()}; la cancelación se logra interrumpiendo el hilo a través
     * de {@link Future#cancel(boolean)} con {@code mayInterruptIfRunning=true}.</p>
     */
    public static void preloadFirstChunk(Context context, String url,
                                         Map<String, String> headers) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return;
        }

        cancelPreload();

        final Context appContext = context.getApplicationContext();
        final String  trimmedUrl = url.trim();

        // Factories creadas fuera del hilo (thread-safe para construcción)
        final DefaultHttpDataSource.Factory upstreamFactory =
                newHttpDataSourceFactory(headers);
        final CacheDataSource.Factory cacheFactory =
                newCacheDataSourceFactory(appContext, upstreamFactory);

        final DataSpec dataSpec = new DataSpec.Builder()
                .setUri(Uri.parse(trimmedUrl))
                .setPosition(0)
                .setLength(PRELOAD_BYTES)
                .build();

        Future<?> task = PRELOAD_EXECUTOR.submit(() -> {
            // CacheDataSource se crea dentro del hilo: su ciclo de vida queda
            // confinado a la tarea y se cierra al finalizar o al ser interrumpida.
            CacheDataSource dataSource = cacheFactory.createDataSource();
            CacheWriter writer = new CacheWriter(dataSource, dataSpec, /* temporaryBuffer */ null,
                    /* progressListener */ null);
            try {
                writer.cache();
            } catch (IOException ignored) {
                // El pre-cargado es best-effort; el playback continúa igualmente.
            } catch (Exception ignored) {
                // Captura cualquier otra excepción inesperada sin crashear.
            }
        });

        ACTIVE_TASK.set(task);
    }

    /**
     * Cancela cualquier pre-cargado en curso interrumpiendo su hilo.
     * Se llama automáticamente antes de iniciar un nuevo pre-cargado.
     */
    public static void cancelPreload() {
        Future<?> task = ACTIVE_TASK.getAndSet(null);
        if (task != null) {
            // mayInterruptIfRunning=true: interrumpe el hilo, lo que provoca que
            // CacheWriter.cache() lance InterruptedException y termine limpiamente.
            task.cancel(true);
        }
    }
}
