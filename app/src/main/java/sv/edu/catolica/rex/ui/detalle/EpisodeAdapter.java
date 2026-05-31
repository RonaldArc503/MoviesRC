package sv.edu.catolica.rex.ui.detalle;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

public class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SEASON_HEADER = 0;
    private static final int TYPE_EPISODE = 1;

    public interface OnEpisodeClickListener {
        void onEpisodeClick(AllCalidadScraper.Episode episode);
    }

    private final List<RowItem> rows = new ArrayList<>();
    private final OnEpisodeClickListener listener;
    private androidx.recyclerview.widget.RecyclerView recyclerView;
    private boolean isTvDevice = false;
    private String seriesPosterUrl = "";

    private static class RowItem {
        int type;
        int seasonNumber;
        AllCalidadScraper.Episode episode;
    }

    public EpisodeAdapter(OnEpisodeClickListener listener, androidx.recyclerview.widget.RecyclerView recyclerView) {
        this.listener = listener;
        this.recyclerView = recyclerView;
        setHasStableIds(true);
    }

    public void setSeriesPoster(String posterUrl) {
        this.seriesPosterUrl = posterUrl != null ? posterUrl : "";
    }

    public void setSeasons(List<AllCalidadScraper.Season> seasons) {
        rows.clear();
        if (seasons != null) {
            for (AllCalidadScraper.Season season : seasons) {
                if (season == null) {
                    continue;
                }

                RowItem header = new RowItem();
                header.type = TYPE_SEASON_HEADER;
                header.seasonNumber = season.seasonNumber > 0 ? season.seasonNumber : 1;
                rows.add(header);

                if (season.episodes == null) {
                    continue;
                }
                for (AllCalidadScraper.Episode episode : season.episodes) {
                    if (episode == null) {
                        continue;
                    }
                    RowItem episodeRow = new RowItem();
                    episodeRow.type = TYPE_EPISODE;
                    episodeRow.seasonNumber = header.seasonNumber;
                    episodeRow.episode = episode;
                    rows.add(episodeRow);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (!isTvDevice) {
            isTvDevice = detectTvDevice(parent.getContext());
        }
        if (viewType == TYPE_SEASON_HEADER) {
            View view = inflater.inflate(R.layout.item_season_header, parent, false);
            return new SeasonHeaderViewHolder(view);
        }
        int episodeLayout = isTvDevice ? R.layout.item_episode_tv : R.layout.item_episode;
        View view = inflater.inflate(episodeLayout, parent, false);
        return new EpisodeViewHolder(view);
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        RowItem row = rows.get(position);
        if (row.type == TYPE_SEASON_HEADER) {
            SeasonHeaderViewHolder headerHolder = (SeasonHeaderViewHolder) holder;
            headerHolder.tvSeasonTitle.setText("Temporada " + row.seasonNumber);
            return;
        }

        EpisodeViewHolder episodeHolder = (EpisodeViewHolder) holder;
        AllCalidadScraper.Episode episode = row.episode;
        int episodeNumber = (episode != null && episode.episodeNumber > 0) ? episode.episodeNumber : 1;
        String title = (episode != null && episode.title != null && !episode.title.trim().isEmpty())
                ? episode.title
                : "Episodio " + episodeNumber;

        episodeHolder.tvCode.setText("Episodio " + episodeNumber);
        episodeHolder.tvTitle.setText(title);
        episodeHolder.itemView.setOnClickListener(v -> {
            if (listener != null && episode != null) {
                listener.onEpisodeClick(episode);
            }
        });

        // TV focus behavior: scroll the RecyclerView to make the focused item visible
        episodeHolder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            if (hasFocus && recyclerView != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    recyclerView.post(() -> recyclerView.smoothScrollToPosition(pos));
                }
                v.animate()
                        .scaleX( hasFocus ? 1.03f : 1.0f)
                        .scaleY( hasFocus ? 1.03f : 1.0f)
                        .setDuration(150L)
                        .start();
            }
        });

        // Cargar poster del episodio (stillUrl) o poster de la serie como fallback
        if (episodeHolder.ivPoster != null) {
            String episodePoster = (episode != null && episode.stillUrl != null
                    && !episode.stillUrl.trim().isEmpty()) ? episode.stillUrl.trim() : "";
            String posterToLoad = !episodePoster.isEmpty() ? episodePoster
                    : (seriesPosterUrl != null && !seriesPosterUrl.isEmpty() ? seriesPosterUrl : "");
            if (!posterToLoad.isEmpty()) {
                Glide.with(episodeHolder.itemView.getContext())
                        .load(posterToLoad)
                        .transition(DrawableTransitionOptions.withCrossFade(100))
                        .placeholder(R.drawable.placeholder_poster)
                        .error(R.drawable.placeholder_poster)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(160, 100)
                        .centerCrop()
                        .into(episodeHolder.ivPoster);
            } else {
                episodeHolder.ivPoster.setImageResource(R.drawable.placeholder_poster);
            }
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= rows.size()) {
            return RecyclerView.NO_ID;
        }
        RowItem row = rows.get(position);
        if (row.type == TYPE_SEASON_HEADER) {
            return ("season:" + row.seasonNumber).hashCode();
        }
        int id = row.episode != null ? row.episode.id : 0;
        int season = row.seasonNumber;
        int episode = row.episode != null ? row.episode.episodeNumber : 0;
        return ("ep:" + id + ":" + season + ":" + episode).hashCode();
    }

    public int findPositionByEpisodeId(int episodeId) {
        if (episodeId <= 0) {
            return RecyclerView.NO_POSITION;
        }
        for (int i = 0; i < rows.size(); i++) {
            RowItem row = rows.get(i);
            if (row != null && row.type == TYPE_EPISODE && row.episode != null && row.episode.id == episodeId) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    public AllCalidadScraper.Episode getEpisodeAtAdapterPosition(int position) {
        if (position < 0 || position >= rows.size()) {
            return null;
        }
        RowItem row = rows.get(position);
        if (row == null || row.type != TYPE_EPISODE) {
            return null;
        }
        return row.episode;
    }

    static class SeasonHeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvSeasonTitle;

        SeasonHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSeasonTitle = itemView.findViewById(R.id.tv_season_title);
        }
    }

    static class EpisodeViewHolder extends RecyclerView.ViewHolder {
        TextView tvCode;
        TextView tvTitle;
        ImageView ivPoster;
        
        EpisodeViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCode = itemView.findViewById(R.id.tv_episode_code);
            tvTitle = itemView.findViewById(R.id.tv_episode_title);
            ivPoster = itemView.findViewById(R.id.iv_episode_poster);
        }
    }

    private boolean detectTvDevice(Context context) {
        if (context == null) {
            return false;
        }
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null &&
                uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        int uiMode = context.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }
}
