package sv.edu.catolica.rex.network;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

import java.io.InputStream;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Registra un OkHttpClient para las cargas de imágenes de Glide que añade los
 * headers Referer/Origin a las imágenes de AllCalidad/Pelispedia (sus CDN usan
 * hotlink protection y devuelven 404 sin el Referer correcto).
 */
@GlideModule
public final class RexGlideModule extends AppGlideModule {

    private static final int DISK_CACHE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB límite en disco

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Limitar la caché en disco a 50 MB en lugar de los 250 MB por defecto
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, "rex_image_cache", DISK_CACHE_SIZE_BYTES));

        // Ajustar memoria y pool de bitmaps para evitar consumo excesivo en TV/móvil
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(1.5f)
                .setBitmapPoolScreens(2.0f)
                .build();
        builder.setMemoryCache(new LruResourceCache(calculator.getMemoryCacheSize()));
        builder.setBitmapPool(new LruBitmapPool(calculator.getBitmapPoolSize()));

        // Decodificación en RGB_565: consume 50% MENOS de RAM y acelera el renderizado en TV
        builder.setDefaultRequestOptions(
                new RequestOptions()
                        .format(DecodeFormat.PREFER_RGB_565)
                        .disallowHardwareConfig()
        );
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    String url = request.url().toString().toLowerCase(Locale.ROOT);
                    Request.Builder builder = request.newBuilder();
                    // User-Agent de navegador moderno para evitar bloqueos HTTP 403 de Cloudflare/CDNs
                    builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

                    if (url.contains("allcalidad") || url.contains("pelispedia")) {
                        builder.header("Referer", "https://allcalidad.re/");
                        builder.header("Origin", "https://allcalidad.re");
                        builder.header("Accept", "image/webp,image/*,*/*;q=0.8");
                    } else if (url.contains("cuevana")) {
                        builder.header("Referer", "https://wv3.cuevana3.eu/");
                        builder.header("Origin", "https://wv3.cuevana3.eu");
                        builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
                    } else if (url.contains("teleon.tv")) {
                        builder.header("Referer", "https://teleon.tv/");
                        builder.header("Origin", "https://teleon.tv");
                    }
                    return chain.proceed(builder.build());
                })
                .build();
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(client));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
