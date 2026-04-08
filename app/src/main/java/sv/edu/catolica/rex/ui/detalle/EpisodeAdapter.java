package sv.edu.catolica.rex.ui.detalle;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.network.AllCalidadScraper;

public class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SEASON_HEADER = 0;
    private static final int TYPE_EPISODE = 1;

    public interface OnEpisodeClickListener {
        void onEpisodeClick(AllCalidadScraper.Episode episode);
    }

    private final List<RowItem> rows = new ArrayList<>();
    private final OnEpisodeClickListener listener;

    private static class RowItem {
        int type;
        int seasonNumber;
        AllCalidadScraper.Episode episode;
    }

    public EpisodeAdapter(OnEpisodeClickListener listener) {
        this.listener = listener;
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
        if (viewType == TYPE_SEASON_HEADER) {
            View view = inflater.inflate(R.layout.item_season_header, parent, false);
            return new SeasonHeaderViewHolder(view);
        }
        View view = inflater.inflate(R.layout.item_episode, parent, false);
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
    }

    @Override
    public int getItemCount() {
        return rows.size();
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

        EpisodeViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCode = itemView.findViewById(R.id.tv_episode_code);
            tvTitle = itemView.findViewById(R.id.tv_episode_title);
        }
    }
}
