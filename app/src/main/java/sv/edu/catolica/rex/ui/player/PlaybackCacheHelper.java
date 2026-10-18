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

    private static final long CACHE_SIZE_BYTES  = 40L * 1024L * 1024L; // 40 MB max para buffering de video
    private static final long PRELOAD_BYTES      = 3L  * 1024L * 1024L; //  3 MB
    private static final int  CONNECT_TIMEOUT_MS = 15_000;
    private static final int  READ_TIMEOUT_MS    = 20_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
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

    /**
     * Devuelve el caché ya inicializado (o null si aún no está listo). No bloquea:
     * la creación pesada (directorio + SQLite) se hace en {@link #initCacheAsync}.
     */
    public static SimpleCache getCache(Context context) {
        return CACHE_REF.get();
    }

    /**
     * Inicializa el caché en segundo plano para que esté listo antes de usarlo.
     * Llamar pronto en el arranque (Home/Detalle) evita bloquear el hilo principal.
     */
    public static void initCacheAsync(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null || CACHE_REF.get() != null) return;
        PRELOAD_EXECUTOR.submit(() -> ensureCache(appContext));
    }

    private static SimpleCache ensureCache(Context appContext) {
        if (appContext == null) return null;
        SimpleCache existing = CACHE_REF.get();
        if (existing != null) return existing;
        synchronized (CACHE_LOCK) {
            SimpleCache cache = CACHE_REF.get();
            if (cache != null) return cache;
            try {
                File cacheDir = new File(appContext.getCacheDir(), "rex_media_cache");
                LeastRecentlyUsedCacheEvictor evictor =
                        new LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES);
                StandaloneDatabaseProvider databaseProvider =
                        new StandaloneDatabaseProvider(appContext);
                cache = new SimpleCache(cacheDir, evictor, databaseProvider);
                CACHE_REF.set(cache);
                return cache;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ─── Factory helpers ─────────────────────────────────────────────────────

    public static CacheDataSource.Factory newCacheDataSourceFactory(
            Context context,
            DefaultHttpDataSource.Factory upstreamFactory) {

        SimpleCache cache = getCache(context);
        if (cache == null) {
            return null;
        }
        CacheDataSource.Factory factory = new CacheDataSource.Factory();
        factory.setCache(cache);
        factory.setCacheWriteDataSinkFactory(
                new CacheDataSink.Factory().setCache(cache));
        factory.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        if (upstreamFactory != null) {
            factory.setUpstreamDataSourceFactory(upstreamFactory);
        }
        return factory;
    }

    public static DefaultHttpDataSource.Factory newHttpDataSourceFactory(
            Map<String, String> headers) {
        return newHttpDataSourceFactory(null, headers);
    }

    public static DefaultHttpDataSource.Factory newHttpDataSourceFactory(
            Context context,
            Map<String, String> headers) {

        String ua = USER_AGENT;
        if (context != null) {
            try {
                String defaultUa = android.webkit.WebSettings.getDefaultUserAgent(context);
                if (defaultUa != null && !defaultUa.trim().isEmpty()) {
                    ua = defaultUa.trim();
                }
            } catch (Exception ignored) {
            }
        }

        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
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

        // ── CDN HLS de vimeos (master.m3u8 y segmentos) ───────────────────────
        } else if (lc.contains("vimeos.zip")) {
            headers.put("Referer", "https://vimeos.net/");
            headers.put("Origin",  "https://vimeos.net");
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

        // ── Cuevana 3 servidores (vidhide, streamwish, filemoon, etc.) ─────────
        } else if (lc.contains("cuevana")
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
            headers.put("Origin",  "https://wv3.cuevana3.eu");
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

        final DataSpec dataSpec = new DataSpec.Builder()
                .setUri(Uri.parse(trimmedUrl))
                .setPosition(0)
                .setLength(PRELOAD_BYTES)
                .build();

        Future<?> task = PRELOAD_EXECUTOR.submit(() -> {
            // La creación del SimpleCache (directorio + SQLite) y del dataSource
            // ocurren aquí, en un hilo de segundo plano, para no bloquear la UI.
            try {
                SimpleCache cache = ensureCache(appContext);
                if (cache == null) {
                    return;
                }
                CacheDataSource.Factory cf = new CacheDataSource.Factory();
                cf.setCache(cache);
                cf.setCacheWriteDataSinkFactory(
                        new CacheDataSink.Factory().setCache(cache));
                cf.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
                cf.setUpstreamDataSourceFactory(upstreamFactory);
                CacheDataSource dataSource = cf.createDataSource();

                CacheWriter writer = new CacheWriter(dataSource, dataSpec, /* temporaryBuffer */ null,
                        /* progressListener */ null);
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

    /**
     * Limpia completamente los fragmentos de video almacenados en disco.
     */
    public static void clearMediaCache(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        if (appContext == null) return;
        PRELOAD_EXECUTOR.submit(() -> {
            cancelPreload();
            synchronized (CACHE_LOCK) {
                try {
                    SimpleCache cache = CACHE_REF.get();
                    if (cache != null) {
                        for (String key : cache.getKeys()) {
                            try {
                                cache.removeResource(key);
                            } catch (Exception ignored) {}
                        }
                    } else {
                        File cacheDir = new File(appContext.getCacheDir(), "rex_media_cache");
                        deleteDirectory(cacheDir);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private static boolean deleteDirectory(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    deleteDirectory(new File(dir, child));
                }
            }
        }
        return dir != null && dir.delete();
    }
}
