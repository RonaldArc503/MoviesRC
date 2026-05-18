package sv.edu.catolica.rex.ui.home;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import java.util.List;
import java.util.Locale;
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
            // Deshabilitar scroll anidado para que el scroll vertical sea fluido
            rvItems.setNestedScrollingEnabled(false);
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

        // ── Dimensiones TV (Netflix style) ──
        // Menú lateral angosto (64dp) deja más espacio → cards más anchas
        private static final int TV_CARD_WIDTH_DP  = 150;   // Antes 88dp
        private static final int TV_CARD_HEIGHT_DP = 225;   // proporción póster 2:3
        private static final int TV_IMAGE_HEIGHT_DP = 200;

        // ── Dimensiones teléfono ──
        private static final int PHONE_CARD_WIDTH_DP  = 130;
        private static final int PHONE_CARD_HEIGHT_DP = 224;
        private static final int PHONE_IMAGE_HEIGHT_DP = 170;

        HorizontalItemAdapter(Context context, List<MediaItem> items,
                              OnItemClickListener listener, boolean isTv) {
            this.context = context;
            this.items   = items;
            this.listener = listener;
            this.isTv    = isTv;
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
            holder.bind(items.get(position), listener);
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        // ── ViewHolder ────────────────────────────────────────────────────────

        static class ViewHolder extends RecyclerView.ViewHolder {

            final CardView  cardView;
            final ImageView ivPoster;
            final TextView  tvTitle;
            final TextView  tvYear;
            final TextView  tvRating;   // badge de rating (★ 8.9)
            final View      progressBg; // barra de progreso fondo
            final View      progressFg; // barra de progreso relleno rojo
            final boolean   isTv;

            ViewHolder(@NonNull View itemView, boolean isTv, Context context) {
                super(itemView);
                this.isTv  = isTv;
                cardView   = (CardView) itemView;
                ivPoster   = itemView.findViewById(R.id.iv_poster);
                tvTitle    = itemView.findViewById(R.id.tv_title);
                tvYear     = itemView.findViewById(R.id.tv_year);
                tvRating   = itemView.findViewById(R.id.tv_rating);
                progressBg = itemView.findViewById(R.id.progress_bg);
                progressFg = itemView.findViewById(R.id.progress_fg);

                float d = context.getResources().getDisplayMetrics().density;

                // Colores base Netflix-dark
                cardView.setCardBackgroundColor(Color.parseColor("#161A24"));
                cardView.setRadius(10 * d);
                cardView.setCardElevation(0);

                if (isTv) {
                    applyTvDimensions(d);
                    setupTvFocus(d);
                } else {
                    applyPhoneDimensions(d);
                    disableFocusForPhone();
                }
            }

            // ── TV: tamaño y foco ─────────────────────────────────────────────

            private void applyTvDimensions(float d) {
                int cardW = (int)(TV_CARD_WIDTH_DP  * d);
                int cardH = (int)(TV_CARD_HEIGHT_DP * d);
                int imgH  = (int)(TV_IMAGE_HEIGHT_DP * d);

                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) cardView.getLayoutParams();
                if (lp == null) lp = new ViewGroup.MarginLayoutParams(cardW, cardH);
                lp.width = cardW;
                lp.height = cardH;
                lp.setMarginEnd((int)(12 * d));
                cardView.setLayoutParams(lp);

                ViewGroup.LayoutParams imgLp = ivPoster.getLayoutParams();
                if (imgLp != null) { imgLp.height = imgH; ivPoster.setLayoutParams(imgLp); }

                tvTitle.setTextSize(13f);
                tvYear.setTextSize(11f);
                if (tvRating != null) tvRating.setTextSize(10f);
            }

            private void setupTvFocus(float d) {
                cardView.setFocusable(true);
                cardView.setFocusableInTouchMode(true);
                cardView.setClickable(true);
                cardView.setAlpha(0.80f);

                cardView.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.animate()
                                .scaleX(1.08f).scaleY(1.08f)
                                .alpha(1.0f)
                                .setDuration(160)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        cardView.setCardElevation(2 * d);
                        // Borde rojo de foco
                        cardView.setForeground(
                                v.getContext().getResources().getDrawable(R.drawable.bg_card_focused, null));
                    } else {
                        v.animate()
                                .scaleX(1.0f).scaleY(1.0f)
                                .alpha(0.80f)
                                .setDuration(160)
                                .start();
                        cardView.setCardElevation(0);
                        cardView.setForeground(null);
                    }
                });
            }

            // ── Móvil ─────────────────────────────────────────────────────────

            private void applyPhoneDimensions(float d) {
                int cardW = (int)(PHONE_CARD_WIDTH_DP  * d);
                int cardH = (int)(PHONE_CARD_HEIGHT_DP * d);
                int imgH  = (int)(PHONE_IMAGE_HEIGHT_DP * d);

                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) cardView.getLayoutParams();
                if (lp == null) lp = new ViewGroup.MarginLayoutParams(cardW, cardH);
                lp.width = cardW;
                lp.height = cardH;
                lp.setMarginEnd((int)(10 * d));
                cardView.setLayoutParams(lp);

                ViewGroup.LayoutParams imgLp = ivPoster.getLayoutParams();
                if (imgLp != null) { imgLp.height = imgH; ivPoster.setLayoutParams(imgLp); }

                tvTitle.setTextSize(11f);
                tvYear.setTextSize(10f);
            }

            private void disableFocusForPhone() {
                cardView.setFocusable(false);
                cardView.setFocusableInTouchMode(false);
                cardView.setClickable(true);
                cardView.setAlpha(1.0f);
                cardView.setCardElevation(0);
            }

            // ── bind ──────────────────────────────────────────────────────────

            void bind(MediaItem item, OnItemClickListener listener) {
                tvTitle.setText(item.getTitulo());
                tvYear.setText(item.getAnio());

                // Rating badge (si el modelo lo expone)
                if (tvRating != null) {
                    double rating = item.getRating();
                    if (rating > 0) {
                        tvRating.setText("★ " + String.format(Locale.US, "%.1f", rating));
                        tvRating.setVisibility(View.VISIBLE);
                    } else {
                        tvRating.setVisibility(View.GONE);
                    }
                }

                // Barra de progreso (si el modelo expone progreso 0–100)
                if (progressBg != null && progressFg != null) {
                    int progress = item.getProgress(); // 0 = sin progreso
                    if (progress > 0) {
                        progressBg.setVisibility(View.VISIBLE);
                        progressFg.setVisibility(View.VISIBLE);
                        // Animar el ancho relativo del fg
                        progressFg.post(() -> {
                            int totalW = progressBg.getWidth();
                            ViewGroup.LayoutParams fgLp = progressFg.getLayoutParams();
                            fgLp.width = (int)(totalW * progress / 100f);
                            progressFg.setLayoutParams(fgLp);
                        });
                    } else {
                        progressBg.setVisibility(View.GONE);
                        progressFg.setVisibility(View.GONE);
                    }
                }

                Glide.with(itemView.getContext())
                        .load(item.getImagen())
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .placeholder(R.drawable.placeholder_poster)
                        .error(R.drawable.placeholder_poster)
                        .centerCrop()
                        .into(ivPoster);

                itemView.setOnClickListener(null);
                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onItemClick(item);
                });
            }
        }
    }
}