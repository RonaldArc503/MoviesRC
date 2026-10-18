package sv.edu.catolica.rex.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import sv.edu.catolica.rex.network.smart.SmartCacheStore;
import sv.edu.catolica.rex.source.SourceAwareCache;
import sv.edu.catolica.rex.ui.player.PlaybackCacheHelper;

/**
 * Gestor unificado de memoria caché y almacenamiento de Rex MoviesRC.
 * Previene la saturación de memoria en TV/móvil, limpia periódicamente
 * fragmentos huérfanos de video y entradas expiradas de scrapers.
 */
public final class AppCacheManager {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rex-cache-cleaner");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public interface CacheCleanCallback {
        void onCleanFinished(long bytesFreed, String formattedFreed);
    }

    private AppCacheManager() {}

    /**
     * Calcula el tamaño total de la caché en disco (archivos temporales, imágenes y video).
     */
    public static long getCacheSizeBytes(Context context) {
        if (context == null) return 0L;
        long size = 0L;
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                size += getDirectorySize(cacheDir);
            }
            File externalCache = context.getExternalCacheDir();
            if (externalCache != null && externalCache.exists()) {
                size += getDirectorySize(externalCache);
            }
        } catch (Exception ignored) {}
        return size;
    }

    /**
     * Devuelve el tamaño en formato legible (ej. "12.4 MB").
     */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        float mb = bytes / (1024f * 1024f);
        if (mb < 0.1f) return "< 0.1 MB";
        return String.format(Locale.US, "%.1f MB", mb);
    }

    /**
     * Mantenimiento ligero ejecutado en segundo plano al arrancar la app.
     * Si la caché supera 60 MB o tiene datos expirados, limpia automáticamente.
     */
    public static void autoPruneOnStartup(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                // 1. Limpieza de entradas de scraping ya caducadas
                SmartCacheStore.cleanExpiredEntries(appContext);
                SourceAwareCache.cleanExpiredEntries(appContext);

                // 2. Si la caché acumulada de fragmentos de video supera 60 MB, liberar video
                long currentSize = getCacheSizeBytes(appContext);
                if (currentSize > 60L * 1024L * 1024L) {
                    PlaybackCacheHelper.clearMediaCache(appContext);
                }
            } catch (Exception ignored) {}
        });
    }

    /**
     * Limpieza completa de caché invocada manualmente desde Ajustes o cuando se requiere liberar espacio.
     * Borra fragmentos de video no utilizados, limpia la caché de Glide y temporales.
     * NUNCA toca datos importantes (favoritos, historial ni sesión).
     */
    public static void cleanCache(Context context, CacheCleanCallback callback) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            long sizeBefore = getCacheSizeBytes(appContext);

            // 1. Limpiar fragmentos de video ExoPlayer
            PlaybackCacheHelper.clearMediaCache(appContext);

            // 2. Limpiar caché en disco de Glide
            try {
                Glide.get(appContext).clearDiskCache();
            } catch (Exception ignored) {}

            // 3. Limpiar SharedPreferences de scrapers expirados
            SmartCacheStore.cleanExpiredEntries(appContext);
            SourceAwareCache.cleanExpiredEntries(appContext);

            // 4. Limpiar temporales .tmp en cacheDir
            try {
                File cacheDir = appContext.getCacheDir();
                if (cacheDir != null && cacheDir.exists()) {
                    File[] files = cacheDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.getName().endsWith(".tmp")) {
                                f.delete();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            long sizeAfter = getCacheSizeBytes(appContext);
            long freed = Math.max(0L, sizeBefore - sizeAfter);

            // 5. Limpiar memoria RAM de Glide en el hilo principal
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Glide.get(appContext).clearMemory();
                } catch (Exception ignored) {}
                if (callback != null) {
                    callback.onCleanFinished(freed, formatSize(freed));
                }
            });
        });
    }

    private static long getDirectorySize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long size = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    size += getDirectorySize(file);
                } else {
                    size += file.length();
                }
            }
        }
        return size;
    }
}
