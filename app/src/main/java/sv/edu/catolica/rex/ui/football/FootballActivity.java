package sv.edu.catolica.rex.ui.football;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.FootballMatch;
import sv.edu.catolica.rex.ui.player.FootballWebPlayerActivity;
import sv.edu.catolica.rex.ui.player.PlayerExoActivity;
import sv.edu.catolica.rex.viewmodel.FootballViewModel;

public class FootballActivity extends AppCompatActivity {

    private static final String CANAL_4_LIVE_M3U8 = "https://cdn.jwplayer.com/live/broadcast/D3kaa3Ky.m3u8";
    private static final String CANAL_4_LIVE_MPD = "https://cdn.jwplayer.com/live/broadcast/D3kaa3Ky.mpd";
    private static final String CANAL_2_LIVE_M3U8 = "https://cdn.jwplayer.com/live/broadcast/48WUA30M.m3u8";
    private static final String CANAL_2_LIVE_MPD = "https://cdn.jwplayer.com/live/broadcast/48WUA30M.mpd";

    private RecyclerView rvMatches;
    private ProgressBar progressBar;
    private TextView emptyView;
    private FootballViewModel viewModel;
    private FootballMatchAdapter adapter;
    private String pendingPlaybackTitle;
    private FootballMatch.FootballStream pendingPlaybackStream;
    private boolean awaitingPlaybackFallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_football);

        setupToolbar();

        rvMatches = findViewById(R.id.rv_football_matches);
        progressBar = findViewById(R.id.progressBar);
        emptyView = findViewById(R.id.tv_empty_state);

        rvMatches.setLayoutManager(new LinearLayoutManager(this));
        rvMatches.setHasFixedSize(true);
        adapter = new FootballMatchAdapter(new ArrayList<>(), this::showStreamsDialog);
        rvMatches.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(FootballViewModel.class);
        observeViewModel();
        viewModel.loadMatches();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayShowTitleEnabled(false);
            }
        }

        ImageButton backButton = findViewById(R.id.btn_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }
    }

    private void observeViewModel() {
        viewModel.getMatches().observe(this, matches -> {
            adapter.submit(matches == null ? new ArrayList<>() : matches);
            updateEmptyState(matches == null || matches.isEmpty());
        });

        viewModel.getIsLoading().observe(this, loading ->
                progressBar.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE));

        viewModel.getErrorMessage().observe(this, message -> {
            if (message != null && !message.trim().isEmpty()) {
                // Si estamos esperando la resolución de un stream y falló,
                // ir directamente al WebView visible como fallback definitivo
                if (awaitingPlaybackFallback && pendingPlaybackStream != null) {
                    awaitingPlaybackFallback = false;
                    launchWebFallback();
                } else {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });

        viewModel.getResolvedUrl().observe(this, url -> {
            if (url == null || url.trim().isEmpty() || pendingPlaybackStream == null) {
                return;
            }
            String title = pendingPlaybackTitle == null || pendingPlaybackTitle.trim().isEmpty()
                    ? "Fútbol en vivo"
                    : pendingPlaybackTitle;
            ArrayList<String> urls = new ArrayList<>();
            urls.add(url.trim());
            PlayerExoActivity.start(
                    this,
                    urls,
                    title,
                    true,
                    pendingPlaybackStream.getEventsUrl(),
                    null,
                    pendingPlaybackStream.getEmbedUrl(),
                    pendingPlaybackStream.getEventsUrl()
            );
            awaitingPlaybackFallback = false;
            clearPendingPlayback();
            viewModel.clearResolvedUrl();
        });
    }

    private void updateEmptyState(boolean empty) {
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (rvMatches != null) {
            rvMatches.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void showStreamsDialog(FootballMatch match) {
        if (match == null || match.getStreams() == null || match.getStreams().isEmpty()) {
            Toast.makeText(this, "No hay streams para este partido", Toast.LENGTH_SHORT).show();
            return;
        }

        List<FootballMatch.FootballStream> streams = match.getStreams();
        String[] labels = new String[streams.size()];
        for (int i = 0; i < streams.size(); i++) {
            labels[i] = buildStreamLabel(streams.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle(match.getTitle())
                .setItems(labels, (dialog, which) -> playStream(match, streams.get(which)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void playStream(FootballMatch match, FootballMatch.FootballStream stream) {
        if (match == null || stream == null) {
            return;
        }
        pendingPlaybackTitle = buildPlaybackTitle(match, stream);
        if (stream.getEmbedUrl() == null || stream.getEmbedUrl().trim().isEmpty()) {
            Toast.makeText(this, "Stream no disponible", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Si es canal conocido (Canal 2 o Canal 4), usar URLs directas en ExoPlayer
        ArrayList<String> directChannelStreams = getDirectChannelStreams(stream);
        if (directChannelStreams != null) {
            awaitingPlaybackFallback = false;
            PlayerExoActivity.start(
                    this,
                    directChannelStreams,
                    pendingPlaybackTitle,
                    true,
                    null,
                    null,
                    stream.getEmbedUrl(),
                    stream.getEventsUrl()
            );
            clearPendingPlayback();
            return;
        }

        // 2. Intentar resolver rápido por HTTP (sin WebView headless).
        //    Si el HTTP falla, el error se captura en getErrorMessage() y lanza WebView visible.
        awaitingPlaybackFallback = true;
        pendingPlaybackStream = stream;
        viewModel.resolveStream(stream);
    }

    private void launchWebFallback() {
        if (pendingPlaybackStream == null) {
            return;
        }
        FootballWebPlayerActivity.start(
                this,
                pendingPlaybackStream.getEmbedUrl(),
                pendingPlaybackStream.getEventsUrl(),
                pendingPlaybackTitle
        );
        clearPendingPlayback();
    }

    private void clearPendingPlayback() {
        pendingPlaybackTitle = null;
        pendingPlaybackStream = null;
        awaitingPlaybackFallback = false;
    }

    private ArrayList<String> getDirectChannelStreams(FootballMatch.FootballStream stream) {
        if (stream == null || stream.getChannelName() == null) {
            return null;
        }
        String channel = stream.getChannelName().toLowerCase(Locale.ROOT);
        ArrayList<String> urls = new ArrayList<>();
        if (channel.contains("canal 4") || channel.contains("canal4")) {
            urls.add(CANAL_4_LIVE_M3U8);
            urls.add(CANAL_4_LIVE_MPD);
            // Removed MP4 fallback
            return urls;
        }
        if (channel.contains("canal 2") || channel.contains("canal2")) {
            urls.add(CANAL_2_LIVE_M3U8);
            urls.add(CANAL_2_LIVE_MPD);
            // Removed MP4 fallback
            return urls;
        }
        return null;
    }

    private String buildPlaybackTitle(FootballMatch match, FootballMatch.FootballStream stream) {
        StringBuilder builder = new StringBuilder();
        if (match.getTitle() != null && !match.getTitle().trim().isEmpty()) {
            builder.append(match.getTitle().trim());
        }
        String channel = stream.getChannelName();
        if (channel != null && !channel.trim().isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" - ");
            }
            builder.append(channel.trim());
        }
        return builder.length() == 0 ? "Fútbol en vivo" : builder.toString();
    }

    private String buildStreamLabel(FootballMatch.FootballStream stream) {
        if (stream == null) {
            return "Stream";
        }
        String channel = stream.getChannelName() == null ? "Stream" : stream.getChannelName().trim();
        String quality = stream.getQuality() == null ? "" : stream.getQuality().trim();
        if (quality.isEmpty()) {
            return channel;
        }
        return channel + " · " + quality;
    }

    private static class FootballMatchAdapter extends RecyclerView.Adapter<FootballMatchAdapter.MatchViewHolder> {

        interface OnMatchClickListener {
            void onMatchClick(FootballMatch match);
        }

        private final List<FootballMatch> items;
        private final OnMatchClickListener listener;

        FootballMatchAdapter(List<FootballMatch> items, OnMatchClickListener listener) {
            this.items = items == null ? new ArrayList<>() : items;
            this.listener = listener;
        }

        void submit(List<FootballMatch> matches) {
            items.clear();
            if (matches != null) {
                items.addAll(matches);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public MatchViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_football_match, parent, false);
            return new MatchViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull MatchViewHolder holder, int position) {
            holder.bind(items.get(position), listener);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class MatchViewHolder extends RecyclerView.ViewHolder {
            private final TextView titleView;
            private final TextView metaView;
            private final TextView streamCountView;
            private final Button playButton;

            MatchViewHolder(@NonNull View itemView) {
                super(itemView);
                titleView = itemView.findViewById(R.id.tv_match_title);
                metaView = itemView.findViewById(R.id.tv_match_meta);
                streamCountView = itemView.findViewById(R.id.tv_match_streams);
                playButton = itemView.findViewById(R.id.btn_play_streams);
            }

            void bind(FootballMatch match, OnMatchClickListener listener) {
                if (match == null) {
                    return;
                }

                titleView.setText(match.getTitle() == null ? "Partido" : match.getTitle());

                String competition = match.getCompetition() == null ? "" : match.getCompetition().trim();
                String time = match.getTime() == null ? "" : match.getTime().trim();
                StringBuilder meta = new StringBuilder();
                if (!competition.isEmpty()) {
                    meta.append(competition);
                }
                if (!time.isEmpty()) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(time);
                }
                if (meta.length() == 0) {
                    meta.append("En vivo");
                }
                metaView.setText(meta.toString());

                int streamCount = match.getStreams() == null ? 0 : match.getStreams().size();
                streamCountView.setText(String.format(Locale.getDefault(), "%d stream%s", streamCount, streamCount == 1 ? "" : "s"));

                View.OnClickListener clickListener = v -> {
                    if (listener != null) {
                        listener.onMatchClick(match);
                    }
                };

                itemView.setOnClickListener(clickListener);
                playButton.setOnClickListener(clickListener);
            }
        }
    }
}