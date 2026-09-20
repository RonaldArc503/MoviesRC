package sv.edu.catolica.rex.ui.detalle;

import android.content.Context;
import android.util.AttributeSet;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView que soporta altura WRAP_CONTENT sin congelar el hilo principal.
 *
 * <p>El problema: con `wrap_content` + `nestedScrollingEnabled=false` dentro de un
 * ScrollView, RecyclerView mide TODOS los items en cada pase de layout. Para series
 * con cientos de episodios eso infla/binde centenares de vistas en un solo frame
 * (freeze de 2-3 s).</p>
 *
 * <p>Solución: si hay pocos items se conserva el wrap-content real; si hay muchos,
 * se acota la altura y se habilita el scroll interno para que RecyclerView mida
 * solo lo visible (lazy).</p>
 */
public class WrapContentRecyclerView extends RecyclerView {

    /** Umbral de items para considerar "pocos" y medirlos todos sin problema. */
    private static final int MAX_WRAP_ITEMS = 40;

    public WrapContentRecyclerView(Context context) {
        super(context);
    }

    public WrapContentRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WrapContentRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        boolean wrapContentHeight = getLayoutParams() != null
                && getLayoutParams().height == LayoutParams.WRAP_CONTENT;
        int mode = MeasureSpec.getMode(heightSpec);

        if (wrapContentHeight && mode != MeasureSpec.EXACTLY) {
            Adapter<?> a = getAdapter();
            int count = a != null ? a.getItemCount() : 0;

            if (count > 0 && count <= MAX_WRAP_ITEMS) {
                // Pocos items: medir todos es barato, se conserva el wrap-content real.
                int expandSpec = MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, expandSpec);
            } else {
                // Muchos items (o lista vacía): altura acotada para no medir todo
                // en un frame. El RecyclerView se desplaza internamente (lazy).
                setNestedScrollingEnabled(true);
                int maxHeight = Math.max(
                        getResources().getDisplayMetrics().heightPixels / 2, 320);
                int boundedSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, boundedSpec);
            }
            return;
        }

        super.onMeasure(widthSpec, heightSpec);
    }
}
