package sv.edu.catolica.rex.ui.home;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.repository.UserDataManager;
import sv.edu.catolica.rex.ui.novelas.NovelasActivity;

public class HomeAdapter extends RecyclerView.Adapter<HomeAdapter.SectionViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(MediaItem item);
    }

    public interface OnFocusPositionChangedListener {
        void onFocusPositionChanged(int sectionPosition, int itemPosition);
    }

    private final List<Section> sections = new ArrayList<>();
    private final Context context;
    private final OnItemClickListener listener;
    private final boolean isTv;
    private final RecyclerView.RecycledViewPool sharedViewPool = new RecyclerView.RecycledViewPool();
    private final SparseIntArray sectionFocusedIndex = new SparseIntArray();

    private OnFocusPositionChangedListener focusPositionChangedListener;
    private RecyclerView hostRecyclerView;
    private int lastFocusedSection = RecyclerView.NO_POSITION;
    private int lastFocusedItem = RecyclerView.NO_POSITION;

    public HomeAdapter(Context context, List<Section> initialSections, OnItemClickListener listener, boolean isTv) {
        this.context = context;
        this.listener = listener;
        this.isTv = isTv;
        this.sharedViewPool.setMaxRecycledViews(0, isTv ? 25 : 15);
        if (initialSections != null) {
            this.sections.addAll(initialSections);
        }
        setHasStableIds(true);
    }

    private View activeRemoveOverlay = null;

    public void setOnFocusPositionChangedListener(OnFocusPositionChangedListener listener) {
        this.focusPositionChangedListener = listener;
    }

    public boolean dismissOpenOverlays() {
        if (activeRemoveOverlay != null && activeRemoveOverlay.getVisibility() == View.VISIBLE) {
            activeRemoveOverlay.setVisibility(View.GONE);
            activeRemoveOverlay = null;
            return true;
        }
        return false;
    }

    public void setSections(List<Section> newSections) {
        sections.clear();
        if (newSections != null) {
            sections.addAll(newSections);
        }
        notifyDataSetChanged();
    }

    public int getLastFocusedSection() {
        return lastFocusedSection;
    }

    public int getLastFocusedItem() {
        return lastFocusedItem;
    }

    public void requestFocusAt(int sectionPosition, int itemPosition) {
        if (!isTv || hostRecyclerView == null || sections.isEmpty()) {
            return;
        }
        int safeSection = Math.max(0, Math.min(sectionPosition, sections.size() - 1));
        int safeItem = Math.max(0, itemPosition);
        hostRecyclerView.post(() -> focusItemInSection(safeSection, safeItem));
    }

    @Override
    public long getItemId(int position) {
        return sectionIdOf(sections.get(position));
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        hostRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        if (hostRecyclerView == recyclerView) {
            hostRecyclerView = null;
        }
        super.onDetachedFromRecyclerView(recyclerView);
    }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_section, parent, false);
        return new SectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        holder.bind(sections.get(position), position);
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    class SectionViewHolder extends RecyclerView.ViewHolder {
        final TextView tvSectionTitle;
        final RecyclerView rvItems;
        final LinearLayoutManager rowLayoutManager;
        final HorizontalItemAdapter itemAdapter;

        SectionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSectionTitle = itemView.findViewById(R.id.section_title);
            rvItems = itemView.findViewById(R.id.rv_items);

            rowLayoutManager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            rowLayoutManager.setInitialPrefetchItemCount(isTv ? 8 : 4);

            rvItems.setLayoutManager(rowLayoutManager);
            rvItems.setNestedScrollingEnabled(false);
            rvItems.setItemAnimator(null);
            rvItems.setHasFixedSize(true);
            rvItems.setItemViewCacheSize(4);
            rvItems.setRecycledViewPool(sharedViewPool);
            rvItems.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            rvItems.setFocusable(false);
            rvItems.setFocusableInTouchMode(false);
            if (isTv) {
                rvItems.setClipChildren(false);
                rvItems.setClipToPadding(false);
            }

            itemAdapter = new HorizontalItemAdapter(this);
            rvItems.setAdapter(itemAdapter);
        }

        void bind(Section section, int sectionPosition) {
            tvSectionTitle.setText(section.getTitle());
            itemAdapter.submitItems(section.getItems(), section.getTitle());

            if (isTv) {
                int rememberedIndex = sectionFocusedIndex.get(sectionPosition, 0);
                if (rememberedIndex > 0) {
                    rowLayoutManager.scrollToPositionWithOffset(Math.max(0, rememberedIndex - 1), 0);
                }
            }
        }
    }

    class HorizontalItemAdapter extends RecyclerView.Adapter<HorizontalItemAdapter.ViewHolder> {
        private final List<MediaItem> items = new ArrayList<>();
        private final SectionViewHolder sectionViewHolder;
        private String sectionTitle = "";

        HorizontalItemAdapter(SectionViewHolder sectionViewHolder) {
            this.sectionViewHolder = sectionViewHolder;
            setHasStableIds(true);
        }

        void submitItems(List<MediaItem> newItems, String sectionTitle) {
            this.sectionTitle = sectionTitle != null ? sectionTitle : "";
            items.clear();
            if (newItems != null) {
                items.addAll(newItems);
            }
            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= items.size()) return RecyclerView.NO_ID;
            MediaItem item = items.get(position);
            String key = (item.getDetailUrl() == null ? "" : item.getDetailUrl())
                    + "|" + (item.getTmdbId() > 0 ? item.getTmdbId() : item.getPostId())
                    + "|" + (item.getTitulo() == null ? "" : item.getTitulo());
            return key.hashCode();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_horizontal_card, parent, false);
            return new ViewHolder(view, isTv, context, sectionViewHolder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position), listener, sectionTitle);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private static final int TV_CARD_WIDTH_DP = 150;
            private static final int TV_CARD_HEIGHT_DP = 225;
            private static final int TV_WIDE_WIDTH_DP = 230;
            private static final int TV_WIDE_HEIGHT_DP = 135;

            private static final int PHONE_CARD_WIDTH_DP = 130;
            private static final int PHONE_CARD_HEIGHT_DP = 224;
            private static final int PHONE_IMAGE_HEIGHT_DP = 170;

            final CardView cardView;
            final ImageView ivPoster;
            final ImageView ivPlayOverlay;
            final TextView tvTitle;
            final TextView tvYear;
            final TextView tvRating;
            final View progressContainer;
            final View progressBg;
            final View progressFg;
            final View badgeWrapperHbo;
            final TextView tvBadgeHbo;
            final View tileOutlineTv;
            final View overlayRemoveFav;
            final boolean isTv;
            final SectionViewHolder sectionViewHolder;

            ViewHolder(@NonNull View itemView, boolean isTv, Context context, SectionViewHolder sectionViewHolder) {
                super(itemView);
                this.isTv = isTv;
                this.sectionViewHolder = sectionViewHolder;
                cardView = (CardView) itemView;
                ivPoster = itemView.findViewById(R.id.iv_poster);
                ivPlayOverlay = itemView.findViewById(R.id.iv_play_overlay);
                tvTitle = itemView.findViewById(R.id.tv_title);
                tvYear = itemView.findViewById(R.id.tv_year);
                tvRating = itemView.findViewById(R.id.tv_rating);
                progressContainer = itemView.findViewById(R.id.progress_container);
                progressBg = itemView.findViewById(R.id.progress_bg);
                progressFg = itemView.findViewById(R.id.progress_fg);
                badgeWrapperHbo = itemView.findViewById(R.id.badge_wrapper_hbo);
                tvBadgeHbo = itemView.findViewById(R.id.tv_badge_hbo);
                tileOutlineTv = itemView.findViewById(R.id.tile_outline_tv);
                overlayRemoveFav = itemView.findViewById(R.id.overlay_remove_fav);

                float density = context.getResources().getDisplayMetrics().density;

                cardView.setCardBackgroundColor(Color.parseColor("#141720"));
                cardView.setRadius(12 * density);
                cardView.setCardElevation(0);

                if (isTv) {
                    applyTvDimensions(density, false);
                    setupTvFocus(density);
                } else {
                    applyPhoneDimensions(density);
                    disableFocusForPhone();
                }
            }

            private int getLiveSectionPosition() {
                if (sectionViewHolder != null) {
                    int pos = sectionViewHolder.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) return pos;
                    return sectionViewHolder.getAbsoluteAdapterPosition();
                }
                return RecyclerView.NO_POSITION;
            }

            private int getLiveItemPosition() {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) return pos;
                return getAbsoluteAdapterPosition();
            }

            private void showRemoveOverlay() {
                if (overlayRemoveFav != null) {
                    if (activeRemoveOverlay != null && activeRemoveOverlay != overlayRemoveFav) {
                        activeRemoveOverlay.setVisibility(View.GONE);
                    }
                    overlayRemoveFav.setVisibility(View.VISIBLE);
                    activeRemoveOverlay = overlayRemoveFav;
                }
            }

            private void hideRemoveOverlay() {
                if (overlayRemoveFav != null) {
                    overlayRemoveFav.setVisibility(View.GONE);
                    if (activeRemoveOverlay == overlayRemoveFav) {
                        activeRemoveOverlay = null;
                    }
                }
            }

            private void performRemoveFavorite(MediaItem item) {
                if (item == null) return;
                hideRemoveOverlay();
                Context ctx = itemView.getContext();
                UserDataManager.getInstance(ctx).removeFromFavorites(item);
                Toast.makeText(ctx, "Quitado de favoritos", Toast.LENGTH_SHORT).show();
                if (ctx instanceof HomeActivity) {
                    ((HomeActivity) ctx).runOnUiThread(() -> ((HomeActivity) ctx).refreshUserDataSections());
                }
            }

            private void applyTvDimensions(float density, boolean isWide) {
                int cardW = (int) ((isWide ? TV_WIDE_WIDTH_DP : TV_CARD_WIDTH_DP) * density);
                int cardH = (int) ((isWide ? TV_WIDE_HEIGHT_DP : TV_CARD_HEIGHT_DP) * density);

                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) cardView.getLayoutParams();
                if (lp == null) {
                    lp = new ViewGroup.MarginLayoutParams(cardW, cardH);
                }
                lp.width = cardW;
                lp.height = cardH;
                lp.setMarginEnd((int) (14 * density));
                cardView.setLayoutParams(lp);

                ViewGroup.LayoutParams imgLp = ivPoster.getLayoutParams();
                if (imgLp != null) {
                    imgLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    imgLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    ivPoster.setLayoutParams(imgLp);
                }

                tvTitle.setTextSize(12f);
                tvYear.setTextSize(10f);
                if (tvRating != null) {
                    tvRating.setTextSize(10f);
                }
            }

            private void setupTvFocus(float density) {
                cardView.setFocusable(true);
                cardView.setFocusableInTouchMode(true);
                cardView.setClickable(true);
                cardView.setAlpha(0.88f);
                cardView.setCardElevation(0);
                cardView.setForeground(null);
            }

            private void applyPhoneDimensions(float density) {
                int cardW = (int) (PHONE_CARD_WIDTH_DP * density);
                int cardH = (int) (PHONE_CARD_HEIGHT_DP * density);
                int imgH = (int) (PHONE_IMAGE_HEIGHT_DP * density);

                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) cardView.getLayoutParams();
                if (lp == null) {
                    lp = new ViewGroup.MarginLayoutParams(cardW, cardH);
                }
                lp.width = cardW;
                lp.height = cardH;
                lp.setMarginEnd((int) (10 * density));
                cardView.setLayoutParams(lp);

                ViewGroup.LayoutParams imgLp = ivPoster.getLayoutParams();
                if (imgLp != null) {
                    imgLp.height = imgH;
                    ivPoster.setLayoutParams(imgLp);
                }

                tvTitle.setTextSize(12f);
                tvYear.setTextSize(11f);
            }

            private void disableFocusForPhone() {
                cardView.setFocusable(false);
                cardView.setFocusableInTouchMode(false);
                cardView.setClickable(true);
                cardView.setAlpha(1.0f);
                cardView.setCardElevation(0);
            }

            void bind(MediaItem item, OnItemClickListener listener, String sectionTitle) {
                hideRemoveOverlay();
                tvTitle.setText(item.getTitulo());
                tvYear.setText(item.getAnio());

                boolean isContinueWatching = (item.getFuente() != null && item.getFuente().startsWith("continue_watching:"))
                        || item.getProgress() > 0;
                if (ivPlayOverlay != null) {
                    ivPlayOverlay.setVisibility(isContinueWatching ? View.VISIBLE : View.GONE);
                }

                boolean isFavSection = sectionTitle != null && (
                        sectionTitle.contains("Favoritos") ||
                        sectionTitle.contains("Mi Lista") ||
                        sectionTitle.contains("Favorito")
                );

                if (isTv) {
                    float density = itemView.getResources().getDisplayMetrics().density;
                    applyTvDimensions(density, isContinueWatching);

                    if (badgeWrapperHbo != null && tvBadgeHbo != null) {
                        String badgeText = null;
                        if (!isContinueWatching) {
                            String anio = item.getAnio();
                            if (anio != null && (anio.contains("2026") || anio.contains("2025"))) {
                                badgeText = "Recién Añadido";
                            } else if ("tv".equalsIgnoreCase(item.getMediaType()) || (item.getFuente() != null && item.getFuente().contains("series"))) {
                                badgeText = "Nuevo Episodio";
                            } else if (getLiveItemPosition() == 0 && getLiveSectionPosition() < 2) {
                                badgeText = "Nuevo";
                            }
                        }
                        if (badgeText != null) {
                            tvBadgeHbo.setText(badgeText);
                            badgeWrapperHbo.setVisibility(View.VISIBLE);
                        } else {
                            badgeWrapperHbo.setVisibility(View.GONE);
                        }
                    }

                    View ivMore = itemView.findViewById(R.id.iv_more_tv);
                    if (ivMore != null) {
                        ivMore.setVisibility(View.VISIBLE);
                    }

                    if (isContinueWatching) {
                        tvTitle.setVisibility(View.VISIBLE);
                        tvYear.setVisibility(View.VISIBLE);
                    } else {
                        boolean hasImage = item.getImagen() != null && !item.getImagen().isEmpty();
                        tvTitle.setVisibility(hasImage ? View.GONE : View.VISIBLE);
                        tvYear.setVisibility(View.GONE);
                    }
                }

                if (tvRating != null) {
                    double rating = item.getRating();
                    if (rating > 0) {
                        tvRating.setText("★ " + String.format(Locale.US, "%.1f", rating));
                        tvRating.setVisibility(View.VISIBLE);
                    } else {
                        tvRating.setVisibility(View.GONE);
                    }
                }

                if (progressFg != null) {
                    int progress = item.getProgress();
                    if (progress > 0) {
                        if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
                        if (progressBg != null) progressBg.setVisibility(View.VISIBLE);
                        progressFg.setVisibility(View.VISIBLE);
                        progressFg.post(() -> {
                            int totalW = progressContainer != null ? progressContainer.getWidth()
                                    : (progressBg != null ? progressBg.getWidth() : 0);
                            if (totalW > 0) {
                                ViewGroup.LayoutParams fgLp = progressFg.getLayoutParams();
                                fgLp.width = Math.max(4, (int) (totalW * progress / 100f));
                                progressFg.setLayoutParams(fgLp);
                            }
                        });
                    } else {
                        if (progressContainer != null) progressContainer.setVisibility(View.GONE);
                        if (progressBg != null) progressBg.setVisibility(View.GONE);
                        progressFg.setVisibility(View.GONE);
                    }
                }

                String primaryPosterUrl = resolvePreferredImageUrl(item, isContinueWatching && isTv);
                String httpsFallbackUrl = toHttpsUrl(primaryPosterUrl);

                int overrideW = isTv ? (isContinueWatching ? 460 : 310) : 260;
                int overrideH = isTv ? (isContinueWatching ? 260 : 460) : 390;

                if (TextUtils.isEmpty(primaryPosterUrl)) {
                    ivPoster.setImageResource(R.drawable.placeholder_poster);
                } else if (!primaryPosterUrl.equals(httpsFallbackUrl)) {
                    Glide.with(itemView.getContext())
                            .load(primaryPosterUrl)
                            .transition(isTv
                                    ? DrawableTransitionOptions.withCrossFade(0)
                                    : DrawableTransitionOptions.withCrossFade(120))
                            .placeholder(R.drawable.placeholder_poster)
                            .override(overrideW, overrideH)
                            .error(Glide.with(itemView.getContext())
                                    .load(httpsFallbackUrl)
                                    .placeholder(R.drawable.placeholder_poster)
                                    .override(overrideW, overrideH)
                                    .error(R.drawable.placeholder_poster)
                                    .centerCrop())
                            .centerCrop()
                            .into(ivPoster);
                } else {
                    Glide.with(itemView.getContext())
                            .load(primaryPosterUrl)
                            .transition(isTv
                                    ? DrawableTransitionOptions.withCrossFade(0)
                                    : DrawableTransitionOptions.withCrossFade(120))
                            .placeholder(R.drawable.placeholder_poster)
                            .override(overrideW, overrideH)
                            .error(R.drawable.placeholder_poster)
                            .centerCrop()
                            .into(ivPoster);
                }

                itemView.setOnClickListener(v -> {
                    if (overlayRemoveFav != null && overlayRemoveFav.getVisibility() == View.VISIBLE) {
                        performRemoveFavorite(item);
                        return;
                    }
                    if (listener != null) {
                        listener.onItemClick(item);
                    }
                });

                itemView.setOnLongClickListener(v -> {
                    if (isFavSection) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        showRemoveOverlay();
                        return true;
                    }
                    return false;
                });

                if (overlayRemoveFav != null) {
                    overlayRemoveFav.setOnClickListener(v -> performRemoveFavorite(item));
                }

                if (!isTv) {
                    itemView.setOnKeyListener(null);
                    return;
                }

                itemView.setOnFocusChangeListener((v, hasFocus) -> {
                    cardView.setSelected(hasFocus);
                    v.animate()
                            .scaleX(hasFocus ? 1.06f : 1.0f)
                            .scaleY(hasFocus ? 1.06f : 1.0f)
                            .alpha(hasFocus ? 1.0f : 0.88f)
                            .setDuration(100L)
                            .withLayer()
                            .setInterpolator(new DecelerateInterpolator(1.5f))
                            .start();
                    cardView.setForeground(hasFocus
                            ? androidx.core.content.ContextCompat.getDrawable(v.getContext(), R.drawable.bg_card_focused_hbomax)
                            : null);

                    if (!hasFocus) {
                        hideRemoveOverlay();
                    } else {
                        int liveSec = getLiveSectionPosition();
                        int liveIt = getLiveItemPosition();
                        if (liveSec != RecyclerView.NO_POSITION && liveIt != RecyclerView.NO_POSITION) {
                            onItemFocused(liveSec, liveIt);
                            if (context instanceof HomeActivity) {
                                ((HomeActivity) context).scrollTvToSection(liveSec);
                            } else if (context instanceof NovelasActivity) {
                                ((NovelasActivity) context).scrollTvToSection(liveSec);
                            }
                        }
                    }
                });

                itemView.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        if (overlayRemoveFav != null && overlayRemoveFav.getVisibility() == View.VISIBLE) {
                            performRemoveFavorite(item);
                            return true;
                        }
                    }

                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        if (overlayRemoveFav != null && overlayRemoveFav.getVisibility() == View.VISIBLE) {
                            hideRemoveOverlay();
                            return true;
                        }
                    }

                    int liveSec = getLiveSectionPosition();
                    int liveIt = getLiveItemPosition();
                    int count = getItemCount();

                    if (liveSec == RecyclerView.NO_POSITION || liveIt == RecyclerView.NO_POSITION) {
                        return false;
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && liveIt <= 0) {
                        hideRemoveOverlay();
                        // Permitir que el foco salga hacia la izquierda (sidebar de menú)
                        return false;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && liveIt >= count - 1) {
                        // Keep focus on row end instead of jumping to unrelated rows.
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        hideRemoveOverlay();
                        return handleVerticalFocusMove(liveSec, liveIt, +1);
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        hideRemoveOverlay();
                        if (liveSec <= 0) {
                            if (context instanceof HomeActivity) {
                                ((HomeActivity) context).focusHeroPlayButton();
                                return true;
                            } else if (context instanceof NovelasActivity) {
                                ((NovelasActivity) context).scrollTvToSection(0);
                                if (liveIt > 0) {
                                    focusItemInSection(0, 0, true, false);
                                    return true;
                                }
                                return false;
                            }
                            // En SearchActivity u otras vistas, permitir que el foco suba al buscador o botón volver
                            return false;
                        }
                        return handleVerticalFocusMove(liveSec, liveIt, -1);
                    }
                    return false;
                });
            }

            private String resolvePreferredImageUrl(MediaItem item, boolean isWide) {
                if (isWide && item != null) {
                    String backdrop = sanitizeImageUrl(item.getBackdrop());
                    if (!backdrop.isEmpty()) {
                        return backdrop;
                    }
                }
                String poster = sanitizeImageUrl(item != null ? item.getImagen() : null);
                if (!poster.isEmpty()) {
                    return poster;
                }
                return sanitizeImageUrl(item != null ? item.getBackdrop() : null);
            }

            private String sanitizeImageUrl(String rawUrl) {
                if (rawUrl == null) {
                    return "";
                }
                String value = rawUrl.trim();
                if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
                    return "";
                }
                if (value.toLowerCase(Locale.ROOT).contains("null")) {
                    return "";
                }
                if (value.startsWith("//")) {
                    return "https:" + value;
                }
                if (value.startsWith("/")) {
                    if (value.startsWith("/poster/")) {
                        return "https://wv3.cuevana3.eu" + value;
                    }
                    return "https://allcalidad.re" + value;
                }
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    return "https://" + value;
                }
                return value;
            }

            private String toHttpsUrl(String url) {
                if (url == null) {
                    return "";
                }
                if (url.startsWith("http://")) {
                    return "https://" + url.substring("http://".length());
                }
                return url;
            }
        }
    }

    private void onItemFocused(int sectionPosition, int itemPosition) {
        lastFocusedSection = sectionPosition;
        lastFocusedItem = itemPosition;
        sectionFocusedIndex.put(sectionPosition, itemPosition);
        if (focusPositionChangedListener != null) {
            focusPositionChangedListener.onFocusPositionChanged(sectionPosition, itemPosition);
        }
    }

    private boolean handleVerticalFocusMove(int currentSection, int currentItem, int direction) {
        if (!isTv || hostRecyclerView == null || sections.isEmpty()) {
            return false;
        }

        int targetSection = currentSection + direction;
        if (targetSection < 0 || targetSection >= sections.size()) {
            return true;
        }

        // Mantener la posición horizontal del elemento actual para navegación espacial natural 2D
        int preferredItem = sectionFocusedIndex.get(targetSection, currentItem);
        Section targetSecObj = sections.get(targetSection);
        int maxItems = (targetSecObj != null && targetSecObj.getItems() != null) ? targetSecObj.getItems().size() : 1;
        int clampedItem = Math.max(0, Math.min(preferredItem, maxItems - 1));

        sectionFocusedIndex.put(targetSection, clampedItem);
        focusItemInSection(targetSection, clampedItem, false, false);
        return true;
    }

    private void focusItemInSection(int sectionPosition, int itemPosition) {
        focusItemInSection(sectionPosition, itemPosition, false, false);
    }

    private void focusItemInSection(int sectionPosition, int itemPosition,
                                    boolean resetRowToStart,
                                    boolean alignSectionTop) {
        focusItemInSectionWithRetry(sectionPosition, itemPosition, resetRowToStart, alignSectionTop, 0);
    }

    private void focusItemInSectionWithRetry(int sectionPosition, int itemPosition,
                                            boolean resetRowToStart,
                                            boolean alignSectionTop,
                                            int retryCount) {
        if (hostRecyclerView == null || sectionPosition < 0 || sectionPosition >= sections.size()) {
            return;
        }

        if (context instanceof HomeActivity) {
            ((HomeActivity) context).scrollTvToSection(sectionPosition);
        } else if (context instanceof NovelasActivity) {
            ((NovelasActivity) context).scrollTvToSection(sectionPosition);
        } else {
            RecyclerView.LayoutManager manager = hostRecyclerView.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(sectionPosition, 0);
            } else {
                hostRecyclerView.scrollToPosition(sectionPosition);
            }
        }

        RecyclerView.ViewHolder holder = hostRecyclerView.findViewHolderForAdapterPosition(sectionPosition);
        if (!(holder instanceof SectionViewHolder)) {
            if (retryCount < 8) {
                RecyclerView.LayoutManager manager = hostRecyclerView.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) manager).scrollToPositionWithOffset(sectionPosition, 0);
                } else {
                    hostRecyclerView.scrollToPosition(sectionPosition);
                }
                hostRecyclerView.postDelayed(() -> focusItemInSectionWithRetry(sectionPosition, itemPosition,
                        resetRowToStart, alignSectionTop, retryCount + 1), 35L);
            }
            return;
        }

        SectionViewHolder sectionHolder = (SectionViewHolder) holder;
        int count = sectionHolder.itemAdapter.getItemCount();
        if (count <= 0) {
            return;
        }

        int clamped = Math.max(0, Math.min(itemPosition, count - 1));
        int targetRowPos = resetRowToStart ? 0 : Math.max(0, clamped - 1);
        sectionHolder.rowLayoutManager.scrollToPositionWithOffset(targetRowPos, 0);

        sectionHolder.rvItems.post(() -> {
            RecyclerView.ViewHolder itemHolder =
                    sectionHolder.rvItems.findViewHolderForAdapterPosition(clamped);
            if (itemHolder != null && itemHolder.itemView != null) {
                itemHolder.itemView.requestFocus();
                return;
            }

            sectionHolder.rvItems.scrollToPosition(clamped);
            sectionHolder.rvItems.post(() -> {
                RecyclerView.ViewHolder fallback =
                        sectionHolder.rvItems.findViewHolderForAdapterPosition(clamped);
                if (fallback != null && fallback.itemView != null) {
                    fallback.itemView.requestFocus();
                }
            });
        });
    }

    private long sectionIdOf(Section section) {
        if (section == null) {
            return 0L;
        }
        String title = section.getTitle() == null ? "" : section.getTitle();
        return title.hashCode();
    }

    private String sectionSignature(Section section) {
        if (section == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(section.getTitle() == null ? "" : section.getTitle()).append('|');
        List<MediaItem> items = section.getItems();
        if (items == null) {
            sb.append("0");
            return sb.toString();
        }
        sb.append(items.size());
        for (MediaItem item : items) {
            if (item == null) {
                continue;
            }
            sb.append('#').append(item.getTmdbId()).append(':');
            sb.append(item.getPostId()).append(':');
            sb.append(item.getDetailUrl() == null ? "" : item.getDetailUrl());
        }
        return sb.toString();
    }
}
