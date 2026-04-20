package sv.edu.catolica.rex.ui.home;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.List;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;

public class HomeAdapter extends RecyclerView.Adapter<HomeAdapter.SectionViewHolder> {

    private List<Section> sections;
    private final Context context;
    private final OnItemClickListener listener;
    private final boolean isTv;

    public interface OnItemClickListener {
        void onItemClick(MediaItem item);
    }

    public HomeAdapter(Context context, List<Section> sections,
                       OnItemClickListener listener, boolean isTv) {
        this.context = context;
        this.sections = sections;
        this.listener = listener;
        this.isTv = isTv;
    }

    /**
     * Refresca las cards tras enriquecimiento TMDB en background
     */
    public void notifySectionsChanged() {
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_section, parent, false);
        return new SectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        holder.bind(sections.get(position));
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    class SectionViewHolder extends RecyclerView.ViewHolder {
        TextView tvSectionTitle;
        RecyclerView rvItems;

        SectionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSectionTitle = itemView.findViewById(R.id.section_title);
            rvItems = itemView.findViewById(R.id.rv_items);
            rvItems.setLayoutManager(new LinearLayoutManager(
                    context, LinearLayoutManager.HORIZONTAL, false));
        }

        void bind(Section section) {
            tvSectionTitle.setText(section.getTitle());
            HorizontalItemAdapter itemAdapter =
                    new HorizontalItemAdapter(context, section.getItems(), listener, isTv);
            rvItems.setAdapter(itemAdapter);
        }
    }

    // ─── Adapter horizontal (cards individuales) ──────────────────────────────

    static class HorizontalItemAdapter
            extends RecyclerView.Adapter<HorizontalItemAdapter.ViewHolder> {

        private final List<MediaItem> items;
        private final OnItemClickListener listener;
        private final boolean isTv;
        private final Context context;

        // Dimensiones TV (dp → px se calcula en ViewHolder)
        private static final int TV_CARD_WIDTH_DP = 220;
        private static final int TV_CARD_HEIGHT_DP = 340;
        private static final int TV_IMAGE_HEIGHT_DP = 260;

        // Dimensiones teléfono (igual que el XML original)
        private static final int PHONE_CARD_WIDTH_DP = 166;
        private static final int PHONE_CARD_HEIGHT_DP = 272;
        private static final int PHONE_IMAGE_HEIGHT_DP = 198;

        HorizontalItemAdapter(Context context, List<MediaItem> items,
                              OnItemClickListener listener, boolean isTv) {
            this.context = context;
            this.items = items;
            this.listener = listener;
            this.isTv = isTv;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_horizontal_card, parent, false);
            return new ViewHolder(view, isTv, context);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MediaItem item = items.get(position);
            holder.bind(item, listener);
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final CardView cardView;
            final ImageView ivPoster;
            final TextView tvTitle;
            final TextView tvYear;
            final boolean isTv;

            ViewHolder(@NonNull View itemView, boolean isTv, Context context) {
                super(itemView);
                this.isTv = isTv;
                cardView = (CardView) itemView;
                ivPoster = itemView.findViewById(R.id.iv_poster);
                tvTitle = itemView.findViewById(R.id.tv_title);
                tvYear = itemView.findViewById(R.id.tv_year);

                float density = context.getResources().getDisplayMetrics().density;

                if (isTv) {
                    // Configuración para TV
                    int cardW = (int) (TV_CARD_WIDTH_DP * density);
                    int cardH = (int) (TV_CARD_HEIGHT_DP * density);
                    int imgH = (int) (TV_IMAGE_HEIGHT_DP * density);

                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) cardView.getLayoutParams();
                    if (lp == null) lp = new ViewGroup.MarginLayoutParams(cardW, cardH);
                    lp.width = cardW;
                    lp.height = cardH;
                    lp.setMarginEnd((int) (12 * density));
                    cardView.setLayoutParams(lp);

                    ViewGroup.LayoutParams imgLp = ivPoster.getLayoutParams();
                    if (imgLp != null) imgLp.height = imgH;
                    ivPoster.setLayoutParams(imgLp);

                    tvTitle.setTextSize(14f);
                    tvYear.setTextSize(12f);

                    setupTvFocus(density);
                } else {
                    // ✅ CONFIGURACIÓN PARA MÓVIL - Deshabilitar focus completamente
                    int cardW = (int) (PHONE_CARD_WIDTH_DP * density);
                    int cardH = (int) (PHONE_CARD_HEIGHT_DP * density);
                    int imgH = (int) (PHONE_IMAGE_HEIGHT_DP * density);

                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) cardView.getLayoutParams();
                    if (lp == null) lp = new ViewGroup.MarginLayoutParams(cardW, cardH);
                    lp.width = cardW;
                    lp.height = cardH;
                    lp.setMarginEnd((int) (10 * density));
                    cardView.setLayoutParams(lp);

                    ViewGroup.LayoutParams imgLp = ivPoster.getLayoutParams();
                    if (imgLp != null) imgLp.height = imgH;
                    ivPoster.setLayoutParams(imgLp);

                    // ✅ IMPORTANTE: Deshabilitar focus en móvil
                    cardView.setFocusable(false);
                    cardView.setFocusableInTouchMode(false);
                    cardView.setClickable(true);

                    // Resetear alpha (por si acaso)
                    cardView.setAlpha(1.0f);
                    cardView.setCardElevation(0);
                }
            }

            private void setupTvFocus(float density) {
                cardView.setFocusable(true);
                cardView.setFocusableInTouchMode(true);
                cardView.setClickable(true);
                cardView.setAlpha(0.82f);

                cardView.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.animate()
                                .scaleX(1.10f)
                                .scaleY(1.10f)
                                .alpha(1.0f)
                                .setDuration(180)
                                .start();
                        cardView.setCardElevation(16 * density);
                        cardView.setForeground(
                                v.getContext().getResources()
                                        .getDrawable(R.drawable.bg_card_focused, null));
                    } else {
                        v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .alpha(0.82f)
                                .setDuration(180)
                                .start();
                        cardView.setCardElevation(2 * density);
                        cardView.setForeground(null);
                    }
                });
            }

            void bind(MediaItem item, OnItemClickListener listener) {
                tvTitle.setText(item.getTitulo());
                tvYear.setText(item.getAnio());

                Glide.with(itemView.getContext())
                        .load(item.getImagen())
                        .placeholder(R.drawable.placeholder_poster)
                        .error(R.drawable.placeholder_poster)
                        .into(ivPoster);

                // ✅ Limpiar listener anterior y asignar nuevo
                itemView.setOnClickListener(null);

                // Para móvil, aseguramos que el click funcione
                if (!isTv) {
                    itemView.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onItemClick(item);
                        }
                    });
                } else {
                    // Para TV, mantener el click
                    itemView.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onItemClick(item);
                        }
                    });
                }
            }
        }

    }

}

