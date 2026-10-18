package sv.edu.catolica.rex.ui.detalle;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.network.AllCalidadScraper;

/**
 * Lista de episodios de la temporada seleccionada (diseño HBOMax: fila con
 * thumbnail + play + título + descripción). No muestra headers de temporada:
 * la temporada se elige con el chip selector del detalle.
 */
public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder> {

    public interface OnEpisodeClickListener {
        void onEpisodeClick(AllCalidadScraper.Episode episode);
    }

    private final List<AllCalidadScraper.Season> seasons = new ArrayList<>();
    private final List<AllCalidadScraper.Episode> episodes = new ArrayList<>();
    private final OnEpisodeClickListener listener;
    private int selectedSeason = -1;
    private String seriesPosterUrl = "";
    private boolean isTvDevice = false;
    private int activeEpisodeId = -1;
    private int activeProgressPercent = 0;

    public EpisodeAdapter(OnEpisodeClickListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setResumeProgress(int episodeId, int progressPercent) {
        this.activeEpisodeId = episodeId;
        this.activeProgressPercent = progressPercent;
        notifyDataSetChanged();
    }

    public void setSeriesPoster(String posterUrl) {
        this.seriesPosterUrl = posterUrl != null ? posterUrl : "";
    }

    public void setSeasons(List<AllCalidadScraper.Season> seasons) {
        this.seasons.clear();
        if (seasons != null) {
            for (AllCalidadScraper.Season s : seasons) {
                if (s != null && s.episodes != null && !s.episodes.isEmpty()) {
                    this.seasons.add(s);
                }
            }
        }
        if (!this.seasons.isEmpty()) {
            int first = this.seasons.get(0).seasonNumber > 0
                    ? this.seasons.get(0).seasonNumber : 1;
            showSeason(first);
        } else {
            selectedSeason = -1;
            episodes.clear();
            notifyDataSetChanged();
        }
    }

    public void showSeason(int seasonNumber) {
        selectedSeason = seasonNumber;
        episodes.clear();
        for (AllCalidadScraper.Season s : seasons) {
            int sn = s.seasonNumber > 0 ? s.seasonNumber : 1;
            if (sn == seasonNumber) {
                episodes.addAll(s.episodes);
                break;
            }
        }
        notifyDataSetChanged();
    }

    public int getSelectedSeasonNumber() {
        return selectedSeason;
    }

    public int getEpisodeCountInSelectedSeason() {
        return episodes.size();
    }

    @NonNull
    @Override
    public EpisodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();
        if (!isTvDevice) {
            isTvDevice = detectTvDevice(ctx);
        }
        View view = LayoutInflater.from(ctx).inflate(R.layout.item_episode, parent, false);
        return new EpisodeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EpisodeViewHolder holder, int position) {
        AllCalidadScraper.Episode episode = episodes.get(position);
        int episodeNumber = (episode != null && episode.episodeNumber > 0) ? episode.episodeNumber : 1;
        String title = (episode != null && episode.title != null && !episode.title.trim().isEmpty())
                ? episode.title : "Episodio " + episodeNumber;
        String overview = (episode != null && episode.overview != null && !episode.overview.trim().isEmpty())
                ? episode.overview : "";

        holder.tvTitle.setText(episodeNumber + ". " + title);
        holder.tvDescription.setText(overview);
        holder.tvDescription.setVisibility(overview.isEmpty() ? View.GONE : View.VISIBLE);

        // Thumbnail: still del episodio o poster de la serie
        String still = (episode != null && episode.stillUrl != null && !episode.stillUrl.trim().isEmpty())
                ? episode.stillUrl.trim() : "";
        String primary = sanitizeImageUrl(!still.isEmpty() ? still : seriesPosterUrl);
        String fallback = sanitizeImageUrl(!still.isEmpty() ? seriesPosterUrl : "");

        if (primary.isEmpty()) {
            holder.imgThumb.setImageResource(R.drawable.placeholder_backdrop);
        } else if (!fallback.isEmpty() && !fallback.equals(primary)) {
            Glide.with(holder.itemView.getContext())
                    .load(primary)
                    .transition(DrawableTransitionOptions.withCrossFade(120))
                    .placeholder(R.drawable.placeholder_backdrop)
                    .error(Glide.with(holder.itemView.getContext())
                            .load(fallback)
                            .placeholder(R.drawable.placeholder_backdrop)
                            .error(R.drawable.placeholder_backdrop)
                            .centerCrop())
                    .centerCrop()
                    .into(holder.imgThumb);
        } else {
            Glide.with(holder.itemView.getContext())
                    .load(primary)
                    .transition(DrawableTransitionOptions.withCrossFade(120))
                    .placeholder(R.drawable.placeholder_backdrop)
                    .error(R.drawable.placeholder_backdrop)
                    .centerCrop()
                    .into(holder.imgThumb);
        }

        if (holder.progressContainer != null && holder.progressFg != null) {
            boolean isResume = (episode != null && episode.id > 0
                    && episode.id == activeEpisodeId && activeProgressPercent > 0);
            if (isResume) {
                holder.progressContainer.setVisibility(View.VISIBLE);
                holder.progressFg.post(() -> {
                    int totalW = holder.progressContainer.getWidth();
                    if (totalW > 0) {
                        ViewGroup.LayoutParams lp = holder.progressFg.getLayoutParams();
                        lp.width = Math.max(4, (int) (totalW * activeProgressPercent / 100f));
                        holder.progressFg.setLayoutParams(lp);
                    }
                });
            } else {
                holder.progressContainer.setVisibility(View.GONE);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null && episode != null) {
                listener.onEpisodeClick(episode);
            }
        });

        if (isTvDevice) {
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.setSelected(hasFocus);
                v.setAlpha(hasFocus ? 1f : 0.85f);
                v.animate().scaleX(hasFocus ? 1.03f : 1f)
                        .scaleY(hasFocus ? 1.03f : 1f)
                        .setDuration(120L)
                        .start();
            });
        }
    }

    @Override
    public int getItemCount() {
        return episodes.size();
    }

    @Override
    public long getItemId(int position) {
        AllCalidadScraper.Episode ep = episodes.get(position);
        return (ep != null && ep.id > 0) ? ep.id : (selectedSeason * 100000L + position);
    }

    public int findPositionByEpisodeId(int episodeId) {
        if (episodeId <= 0) return RecyclerView.NO_POSITION;
        for (int i = 0; i < episodes.size(); i++) {
            if (episodes.get(i) != null && episodes.get(i).id == episodeId) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    public AllCalidadScraper.Episode getEpisodeAtAdapterPosition(int position) {
        if (position < 0 || position >= episodes.size()) return null;
        return episodes.get(position);
    }

    static class EpisodeViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgThumb;
        final TextView tvTitle;
        final TextView tvDescription;
        final View progressContainer;
        final View progressBg;
        final View progressFg;

        EpisodeViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.img_episode_thumb);
            tvTitle = itemView.findViewById(R.id.tv_episode_title);
            tvDescription = itemView.findViewById(R.id.tv_episode_description);
            progressContainer = itemView.findViewById(R.id.episode_progress_container);
            progressBg = itemView.findViewById(R.id.episode_progress_bg);
            progressFg = itemView.findViewById(R.id.episode_progress_fg);
        }
    }

    private boolean detectTvDevice(Context context) {
        return sv.edu.catolica.rex.utils.TvUtils.isTv(context);
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
