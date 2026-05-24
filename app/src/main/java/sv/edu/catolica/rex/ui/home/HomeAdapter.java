package sv.edu.catolica.rex.ui.home;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
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
        if (initialSections != null) {
            this.sections.addAll(initialSections);
        }
        setHasStableIds(true);
    }

    public void setOnFocusPositionChangedListener(OnFocusPositionChangedListener listener) {
        this.focusPositionChangedListener = listener;
    }

    public void setSections(List<Section> newSections) {
        final List<Section> oldCopy = new ArrayList<>(sections);
        final List<Section> newCopy = newSections == null ? new ArrayList<>() : new ArrayList<>(newSections);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldCopy.size();
            }

            @Override
            public int getNewListSize() {
                return newCopy.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return sectionIdOf(oldCopy.get(oldItemPosition)) == sectionIdOf(newCopy.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return sectionSignature(oldCopy.get(oldItemPosition))
                        .equals(sectionSignature(newCopy.get(newItemPosition)));
            }
        });

        sections.clear();
        sections.addAll(newCopy);
        diffResult.dispatchUpdatesTo(this);
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
            rvItems.setHasFixedSize(true);
            rvItems.setItemAnimator(null);
            rvItems.setItemViewCacheSize(isTv ? 20 : 8);
            rvItems.setRecycledViewPool(sharedViewPool);
            rvItems.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            rvItems.setFocusable(false);
            rvItems.setFocusableInTouchMode(false);

            itemAdapter = new HorizontalItemAdapter();
            rvItems.setAdapter(itemAdapter);
        }

        void bind(Section section, int sectionPosition) {
            tvSectionTitle.setText(section.getTitle());
            itemAdapter.submitItems(section.getItems(), sectionPosition);

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
        private int sectionPosition = RecyclerView.NO_POSITION;

        HorizontalItemAdapter() {
            setHasStableIds(true);
        }

        void submitItems(List<MediaItem> newItems, int sectionPosition) {
            this.sectionPosition = sectionPosition;
            items.clear();
            if (newItems != null) {
                items.addAll(newItems);
            }
            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
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
            return new ViewHolder(view, isTv, context);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position), listener, sectionPosition, position, getItemCount());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private static final int TV_CARD_WIDTH_DP = 150;
            private static final int TV_CARD_HEIGHT_DP = 225;
            private static final int TV_IMAGE_HEIGHT_DP = 200;
            private static final int PHONE_CARD_WIDTH_DP = 130;
            private static final int PHONE_CARD_HEIGHT_DP = 224;
            private static final int PHONE_IMAGE_HEIGHT_DP = 170;

            final CardView cardView;
            final ImageView ivPoster;
            final TextView tvTitle;
            final TextView tvYear;
            final TextView tvRating;
            final View progressBg;
            final View progressFg;
            final boolean isTv;

            ViewHolder(@NonNull View itemView, boolean isTv, Context context) {
                super(itemView);
                this.isTv = isTv;
                cardView = (CardView) itemView;
                ivPoster = itemView.findViewById(R.id.iv_poster);
                tvTitle = itemView.findViewById(R.id.tv_title);
                tvYear = itemView.findViewById(R.id.tv_year);
                tvRating = itemView.findViewById(R.id.tv_rating);
                progressBg = itemView.findViewById(R.id.progress_bg);
                progressFg = itemView.findViewById(R.id.progress_fg);

                float density = context.getResources().getDisplayMetrics().density;

                cardView.setCardBackgroundColor(Color.parseColor("#161A24"));
                cardView.setRadius(10 * density);
                cardView.setCardElevation(0);

                if (isTv) {
                    applyTvDimensions(density);
                    setupTvFocus(density);
                } else {
                    applyPhoneDimensions(density);
                    disableFocusForPhone();
                }
            }

            private void applyTvDimensions(float density) {
                int cardW = (int) (TV_CARD_WIDTH_DP * density);
                int cardH = (int) (TV_CARD_HEIGHT_DP * density);
                int imgH = (int) (TV_IMAGE_HEIGHT_DP * density);

                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) cardView.getLayoutParams();
                if (lp == null) {
                    lp = new ViewGroup.MarginLayoutParams(cardW, cardH);
                }
                lp.width = cardW;
                lp.height = cardH;
                lp.setMarginEnd((int) (12 * density));
                cardView.setLayoutParams(lp);

                ViewGroup.LayoutParams imgLp = ivPoster.getLayoutParams();
                if (imgLp != null) {
                    imgLp.height = imgH;
                    ivPoster.setLayoutParams(imgLp);
                }

                tvTitle.setTextSize(13f);
                tvYear.setTextSize(11f);
                if (tvRating != null) {
                    tvRating.setTextSize(10f);
                }
            }

            private void setupTvFocus(float density) {
                cardView.setFocusable(true);
                cardView.setFocusableInTouchMode(true);
                cardView.setClickable(true);
                cardView.setAlpha(0.86f);
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

            void bind(MediaItem item, OnItemClickListener listener, int sectionPos, int itemPos, int totalItems) {
                tvTitle.setText(item.getTitulo());
                tvYear.setText(item.getAnio());

                if (tvRating != null) {
                    double rating = item.getRating();
                    if (rating > 0) {
                        tvRating.setText("★ " + String.format(Locale.US, "%.1f", rating));
                        tvRating.setVisibility(View.VISIBLE);
                    } else {
                        tvRating.setVisibility(View.GONE);
                    }
                }

                if (progressBg != null && progressFg != null) {
                    int progress = item.getProgress();
                    if (progress > 0) {
                        progressBg.setVisibility(View.VISIBLE);
                        progressFg.setVisibility(View.VISIBLE);
                        progressFg.post(() -> {
                            int totalW = progressBg.getWidth();
                            ViewGroup.LayoutParams fgLp = progressFg.getLayoutParams();
                            fgLp.width = (int) (totalW * progress / 100f);
                            progressFg.setLayoutParams(fgLp);
                        });
                    } else {
                        progressBg.setVisibility(View.GONE);
                        progressFg.setVisibility(View.GONE);
                    }
                }

                String primaryPosterUrl = resolvePreferredImageUrl(item);
                String httpsFallbackUrl = toHttpsUrl(primaryPosterUrl);

                if (TextUtils.isEmpty(primaryPosterUrl)) {
                    ivPoster.setImageResource(R.drawable.placeholder_poster);
                } else if (!primaryPosterUrl.equals(httpsFallbackUrl)) {
                    Glide.with(itemView.getContext())
                            .load(primaryPosterUrl)
                            .transition(DrawableTransitionOptions.withCrossFade(120))
                            .placeholder(R.drawable.placeholder_poster)
                            .error(Glide.with(itemView.getContext())
                                    .load(httpsFallbackUrl)
                                    .placeholder(R.drawable.placeholder_poster)
                                    .error(R.drawable.placeholder_poster)
                                    .centerCrop())
                            .centerCrop()
                            .into(ivPoster);
                } else {
                    Glide.with(itemView.getContext())
                            .load(primaryPosterUrl)
                            .transition(DrawableTransitionOptions.withCrossFade(120))
                            .placeholder(R.drawable.placeholder_poster)
                            .error(R.drawable.placeholder_poster)
                            .centerCrop()
                            .into(ivPoster);
                }

                itemView.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onItemClick(item);
                    }
                });

                if (!isTv) {
                    itemView.setOnKeyListener(null);
                    return;
                }

                itemView.setOnFocusChangeListener((v, hasFocus) -> {
                    cardView.setSelected(hasFocus);
                    v.animate()
                            .scaleX(hasFocus ? 1.05f : 1.0f)
                            .scaleY(hasFocus ? 1.05f : 1.0f)
                            .alpha(hasFocus ? 1.0f : 0.86f)
                            .setDuration(100L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    float density = v.getResources().getDisplayMetrics().density;
                    cardView.setCardElevation(hasFocus ? 2 * density : 0);
                    cardView.setForeground(hasFocus
                            ? v.getContext().getResources().getDrawable(R.drawable.bg_card_focused, null)
                            : null);
                    if (hasFocus) {
                        onItemFocused(sectionPos, itemPos);
                    }
                });

                itemView.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && itemPos <= 0) {
                        // Keep horizontal focus bounded to the current row.
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && itemPos >= totalItems - 1) {
                        // Keep focus on row end instead of jumping to unrelated rows.
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return handleVerticalFocusMove(sectionPos, itemPos, +1);
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        return handleVerticalFocusMove(sectionPos, itemPos, -1);
                    }
                    return false;
                });
            }

            private String resolvePreferredImageUrl(MediaItem item) {
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

        // Section changes always start at the first item and align the row at the start.
        sectionFocusedIndex.put(targetSection, 0);
        focusItemInSection(targetSection, 0, true, true);
        return true;
    }

    private void focusItemInSection(int sectionPosition, int itemPosition) {
        focusItemInSection(sectionPosition, itemPosition, false, false);
    }

    private void focusItemInSection(int sectionPosition, int itemPosition,
                                    boolean resetRowToStart,
                                    boolean alignSectionTop) {
        if (hostRecyclerView == null) {
            return;
        }

        if (alignSectionTop) {
            RecyclerView.LayoutManager manager = hostRecyclerView.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(sectionPosition, 0);
            } else {
                hostRecyclerView.scrollToPosition(sectionPosition);
            }
        }

        RecyclerView.ViewHolder holder = hostRecyclerView.findViewHolderForAdapterPosition(sectionPosition);
        if (!(holder instanceof SectionViewHolder)) {
            hostRecyclerView.scrollToPosition(sectionPosition);
            hostRecyclerView.post(() -> focusItemInSection(sectionPosition, itemPosition,
                    resetRowToStart, alignSectionTop));
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
            if (itemHolder != null) {
                itemHolder.itemView.requestFocus();
                return;
            }

            sectionHolder.rvItems.scrollToPosition(clamped);
            sectionHolder.rvItems.post(() -> {
                RecyclerView.ViewHolder fallback =
                        sectionHolder.rvItems.findViewHolderForAdapterPosition(clamped);
                if (fallback != null) {
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
