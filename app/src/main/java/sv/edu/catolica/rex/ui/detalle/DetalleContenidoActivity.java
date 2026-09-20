package sv.edu.catolica.rex.ui.detalle;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import sv.edu.catolica.rex.models.MediaItem;

/**
 * Delegador al detalle rediseñado (HBOMax). Se mantiene por compatibilidad con
 * el manifest; toda apertura redirige a {@link DetalleActivity} con el diseño nuevo.
 */
public class DetalleContenidoActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_ITEM = "media_item";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaItem item = (MediaItem) getIntent().getSerializableExtra(EXTRA_MEDIA_ITEM);
        if (item != null) {
            DetalleActivity.start(this, item);
        }
        finish();
    }

    public static void start(Context context, MediaItem item) {
        Intent intent = new Intent(context, DetalleContenidoActivity.class);
        intent.putExtra(EXTRA_MEDIA_ITEM, item);
        context.startActivity(intent);
    }
}
