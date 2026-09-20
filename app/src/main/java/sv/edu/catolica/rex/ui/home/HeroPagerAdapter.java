package sv.edu.catolica.rex.ui.home;

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

public class HeroPagerAdapter extends RecyclerView.Adapter<HeroPagerAdapter.HeroViewHolder> {
    public interface Listener {
        void onPlay(MediaItem item);
        void onInfo(MediaItem item);
    }

    private final List<MediaItem> items = new ArrayList<>();
    private final Listener listener;

    public HeroPagerAdapter(List<MediaItem> items, Listener listener) {
        if (items != null) this.items.addAll(items);
        this.listener = listener;
    }

    @NonNull
    @Override
    public HeroViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hero_slide, parent, false);
        return new HeroViewHolder(view);
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
        private final ImageButton info;

        HeroViewHolder(@NonNull View itemView) {
            super(itemView);
            backdrop = itemView.findViewById(R.id.img_hero_backdrop);
            title = itemView.findViewById(R.id.tv_hero_title);
            match = itemView.findViewById(R.id.tv_hero_match);
            year = itemView.findViewById(R.id.tv_hero_year);
            rating = itemView.findViewById(R.id.tv_hero_rating);
            synopsis = itemView.findViewById(R.id.tv_hero_synopsis);
            play = itemView.findViewById(R.id.btn_hero_play);
            info = itemView.findViewById(R.id.btn_hero_info);
        }

        void bind(MediaItem item) {
            title.setText(item.getTitulo());
            year.setText(item.getAnio() == null ? "" : item.getAnio());
            synopsis.setText(item.getSynopsis() == null ? "" : item.getSynopsis());
            match.setText("TMDB destacado");
            rating.setText(item.getRating() > 0
                    ? String.format(Locale.US, "%.1f", item.getRating()) : "");
            rating.setVisibility(item.getRating() > 0 ? View.VISIBLE : View.GONE);

            String image = item.getBackdrop();
            if (image == null || image.trim().isEmpty()) image = item.getImagen();
            Glide.with(itemView.getContext())
                    .load(image)
                    .transition(DrawableTransitionOptions.withCrossFade(180))
                    .placeholder(R.drawable.placeholder_backdrop)
                    .error(R.drawable.placeholder_backdrop)
                    .centerCrop()
                    .into(backdrop);

            play.setOnClickListener(v -> {
                if (listener != null) listener.onPlay(item);
            });
            info.setOnClickListener(v -> {
                if (listener != null) listener.onInfo(item);
            });
        }
    }
}
