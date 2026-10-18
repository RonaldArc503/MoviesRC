package sv.edu.catolica.rex.ui.detalle;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.MediaItem;

/** Fila horizontal "Más como esta" (poster + título + match). */
public class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.PosterViewHolder> {

    public interface OnPosterClickListener {
        void onPosterClick(MediaItem item);
    }

    private final List<MediaItem> items = new ArrayList<>();
    private final OnPosterClickListener listener;

    public PosterAdapter(OnPosterClickListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitItems(List<MediaItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        MediaItem item = items.get(position);
        String key = (item.getTmdbId() > 0 ? item.getTmdbId() : item.getPostId())
                + "|" + (item.getTitulo() == null ? "" : item.getTitulo());
        return key.hashCode();
    }

    @NonNull
    @Override
    public PosterViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_poster_card, parent, false);
        return new PosterViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PosterViewHolder holder, int position) {
        MediaItem item = items.get(position);
        holder.tvPosterTitle.setText(item.getTitulo());

        String poster = sanitizeImageUrl(item.getImagen());
        String backdrop = sanitizeImageUrl(item.getBackdrop());
        String primary = !poster.isEmpty() ? poster : backdrop;
        String fallback = (!backdrop.isEmpty() && !backdrop.equals(primary)) ? backdrop : "";

        if (primary.isEmpty()) {
            holder.imgPoster.setImageResource(R.drawable.placeholder_poster);
        } else if (!fallback.isEmpty()) {
            Glide.with(holder.itemView.getContext())
                    .load(primary)
                    .placeholder(R.drawable.placeholder_poster)
                    .error(Glide.with(holder.itemView.getContext())
                            .load(fallback)
                            .placeholder(R.drawable.placeholder_poster)
                            .error(R.drawable.placeholder_poster)
                            .centerCrop())
                    .centerCrop()
                    .into(holder.imgPoster);
        } else {
            Glide.with(holder.itemView.getContext())
                    .load(primary)
                    .placeholder(R.drawable.placeholder_poster)
                    .error(R.drawable.placeholder_poster)
                    .centerCrop()
                    .into(holder.imgPoster);
        }

        double rating = item.getRating();
        holder.tvMatch.setVisibility(rating > 0 ? View.VISIBLE : View.GONE);
        if (rating > 0) {
            holder.tvMatch.setText(String.format(Locale.US, "%.0f%%", Math.min(99, rating * 10)));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPosterClick(item);
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            v.animate()
                    .scaleX(hasFocus ? 1.05f : 1f)
                    .scaleY(hasFocus ? 1.05f : 1f)
                    .setDuration(120L)
                    .start();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class PosterViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgPoster;
        final TextView tvPosterTitle;
        final TextView tvMatch;

        PosterViewHolder(@NonNull View itemView) {
            super(itemView);
            imgPoster = itemView.findViewById(R.id.img_poster);
            tvPosterTitle = itemView.findViewById(R.id.tv_poster_title);
            tvMatch = itemView.findViewById(R.id.tv_poster_match);
        }
    }

    private static String sanitizeImageUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String value = rawUrl.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) return "";
        if (value.toLowerCase(Locale.ROOT).contains("null")) return "";
        if (value.startsWith("//")) return "https:" + value;
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
}
