package sv.edu.catolica.rex.ui.home;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.repository.UserDataManager;

public class HeroPagerAdapter extends RecyclerView.Adapter<HeroPagerAdapter.HeroViewHolder> {
    public interface Listener {
        void onPlay(MediaItem item);
        void onInfo(MediaItem item);
        void onToggleMyList(MediaItem item, int position);
    }

    private final List<MediaItem> items = new ArrayList<>();
    private final Listener listener;
    private final boolean isTv;

    public HeroPagerAdapter(List<MediaItem> items, Listener listener) {
        this(items, listener, false);
    }

    public HeroPagerAdapter(List<MediaItem> items, Listener listener, boolean isTv) {
        if (items != null) this.items.addAll(items);
        this.listener = listener;
        this.isTv = isTv;
    }

    @NonNull
    @Override
    public HeroViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hero_slide, parent, false);
        return new HeroViewHolder(view, isTv);
    }

    @Override
    public void onBindViewHolder(@NonNull HeroViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class HeroViewHolder extends RecyclerView.ViewHolder {
        private final ImageView backdrop;
        private final TextView title;
        private final TextView match;
        private final TextView year;
        private final TextView rating;
        private final TextView synopsis;
        private final View play;
        private final TextView playLabel;
        private final ImageButton myList;
        private final ImageButton info;
        private final boolean isTv;

        HeroViewHolder(@NonNull View itemView, boolean isTv) {
            super(itemView);
            this.isTv = isTv;
            backdrop = itemView.findViewById(R.id.img_hero_backdrop);
            title = itemView.findViewById(R.id.tv_hero_title);
            match = itemView.findViewById(R.id.tv_hero_match);
            year = itemView.findViewById(R.id.tv_hero_year);
            rating = itemView.findViewById(R.id.tv_hero_rating);
            synopsis = itemView.findViewById(R.id.tv_hero_synopsis);
            play = itemView.findViewById(R.id.btn_hero_play);
            playLabel = itemView.findViewById(R.id.tv_hero_play_label);
            myList = itemView.findViewById(R.id.btn_hero_mylist);
            info = itemView.findViewById(R.id.btn_hero_info);

            if (isTv) {
                setupTvFocusEffects();
            }
        }

        private void setupTvFocusEffects() {
            View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
                v.animate()
                        .scaleX(hasFocus ? 1.08f : 1.0f)
                        .scaleY(hasFocus ? 1.08f : 1.0f)
                        .setDuration(120L)
                        .start();
                if (hasFocus && v.getContext() instanceof HomeActivity) {
                    ((HomeActivity) v.getContext()).onHeroFocused();
                }
            };

            if (play != null) {
                play.setFocusable(true);
                play.setFocusableInTouchMode(true);
                play.setOnFocusChangeListener(focusListener);
                play.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        v.performClick();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        Context ctx = v.getContext();
                        if (ctx instanceof HomeActivity) {
                            return ((HomeActivity) ctx).focusFirstSectionFromHero();
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        Context ctx = v.getContext();
                        if (ctx instanceof HomeActivity) {
                            return ((HomeActivity) ctx).focusSidebarFromHero();
                        }
                    }
                    return false;
                });
            }

            if (myList != null) {
                myList.setFocusable(true);
                myList.setFocusableInTouchMode(true);
                myList.setOnFocusChangeListener(focusListener);
                myList.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        v.performClick();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        Context ctx = v.getContext();
                        if (ctx instanceof HomeActivity) {
                            return ((HomeActivity) ctx).focusFirstSectionFromHero();
                        }
                    }
                    return false;
                });
            }

            if (info != null) {
                info.setFocusable(true);
                info.setFocusableInTouchMode(true);
                info.setOnFocusChangeListener(focusListener);
                info.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        v.performClick();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        Context ctx = v.getContext();
                        if (ctx instanceof HomeActivity) {
                            return ((HomeActivity) ctx).focusFirstSectionFromHero();
                        }
                    }
                    return false;
                });
            }
        }

        void bind(MediaItem item) {
            title.setText(item.getTitulo());
            String anioStr = item.getAnio() == null ? "" : item.getAnio();
            
            if (isTv) {
                // Branding HBO Max: [ CATEGORÍA ]
                if (item.getFuente() != null && item.getFuente().toLowerCase(Locale.ROOT).contains("novela")) {
                    match.setText("[ NOVELA ]");
                } else if ("tv".equalsIgnoreCase(item.getMediaType()) || (item.getFuente() != null && item.getFuente().contains("series"))) {
                    match.setText("[ SERIE ORIGINAL ]");
                } else {
                    match.setText("[ DESTACADO ]");
                }

                if (item.getRating() > 0) {
                    rating.setText(String.format(Locale.US, "★ %.1f", item.getRating()));
                    rating.setVisibility(View.VISIBLE);
                } else {
                    rating.setVisibility(View.GONE);
                }

                String metaText = anioStr;
                if ("tv".equalsIgnoreCase(item.getMediaType())) {
                    metaText += (metaText.isEmpty() ? "" : " • ") + "Serie";
                } else if ("movie".equalsIgnoreCase(item.getMediaType())) {
                    metaText += (metaText.isEmpty() ? "" : " • ") + "Película";
                }
                year.setText(metaText);

                if (playLabel != null) {
                    boolean isContinue = item.getProgress() > 0 || (item.getFuente() != null && item.getFuente().startsWith("continue_watching:"));
                    playLabel.setText(isContinue ? "Reanudar" : "Reproducir");
                }
            } else {
                year.setText(anioStr);
                if (item.getFuente() != null && item.getFuente().toLowerCase(Locale.ROOT).contains("novela")) {
                    match.setText("Novela destacada");
                } else {
                    match.setText("TMDB destacado");
                }
                rating.setText(item.getRating() > 0
                        ? String.format(Locale.US, "%.1f", item.getRating()) : "");
                rating.setVisibility(item.getRating() > 0 ? View.VISIBLE : View.GONE);
            }

            synopsis.setText(item.getSynopsis() == null ? "" : item.getSynopsis());

            String image = item.getBackdrop();
            if (image == null || image.trim().isEmpty()) image = item.getImagen();
            Glide.with(itemView.getContext())
                    .load(image)
                    .transition(DrawableTransitionOptions.withCrossFade(180))
                    .placeholder(R.drawable.placeholder_backdrop)
                    .error(R.drawable.placeholder_backdrop)
                    .override(isTv ? 1280 : 800, isTv ? 720 : 450)
                    .centerCrop()
                    .into(backdrop);

            boolean isFav = UserDataManager.getInstance(itemView.getContext()).isFavorite(item);
            updateMyListButton(isFav);

            if (play != null) {
                play.setOnClickListener(v -> {
                    if (listener != null) listener.onPlay(item);
                });
            }
            if (myList != null) {
                myList.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onToggleMyList(item, getBindingAdapterPosition());
                    }
                    boolean nowFav = UserDataManager.getInstance(itemView.getContext()).isFavorite(item);
                    updateMyListButton(nowFav);
                });
            }
            if (info != null) {
                info.setOnClickListener(v -> {
                    if (listener != null) listener.onInfo(item);
                });
            }
        }

        private void updateMyListButton(boolean isFav) {
            if (myList == null) return;
            if (isFav) {
                myList.setImageResource(R.drawable.ic_check);
                myList.setImageTintList(ColorStateList.valueOf(Color.parseColor("#4ADE80")));
                myList.setContentDescription("Eliminar de Mi lista");
            } else {
                myList.setImageResource(R.drawable.ic_add);
                myList.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                myList.setContentDescription("Añadir a Mi lista");
            }
        }
    }
}
