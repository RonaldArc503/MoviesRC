package sv.edu.catolica.rex.ui.detalle;

import android.content.Context;
import android.util.AttributeSet;
import androidx.recyclerview.widget.RecyclerView;

public class WrapContentRecyclerView extends RecyclerView {

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

        // Only force wrap-content behavior when the parent truly requests WRAP_CONTENT.
        // For MATCH_PARENT/weighted containers this avoids measuring an unbounded height.
        if (wrapContentHeight && mode != MeasureSpec.EXACTLY) {
            int expandSpec = MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, expandSpec);
            return;
        }

        super.onMeasure(widthSpec, heightSpec);
    }
}
