package sv.edu.catolica.rex.network;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

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

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Sin opciones globales por ahora.
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    String url = request.url().toString().toLowerCase(Locale.ROOT);
                    Request.Builder builder = request.newBuilder();
                    if (url.contains("allcalidad.re") || url.contains("pelispedia")) {
                        builder.header("Referer", "https://allcalidad.re/");
                        builder.header("Origin", "https://allcalidad.re");
                        builder.header("Accept", "image/webp,image/*,*/*;q=0.8");
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
