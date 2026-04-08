package sv.edu.catolica.rex.ui.detalle;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;
import sv.edu.catolica.rex.ui.player.PlayerContenidoActivity;

public class DetalleContenidoActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_ITEM = "media_item";

    private ImageView ivPoster;
    private TextView tvTitle, tvYear, tvSynopsis, tvEpisodesTitle;
    private RecyclerView rvEpisodes;
    private Button btnPlay;
    private MediaItem mediaItem;
    private ArrayList<String> playUrls = new ArrayList<>();
    private final List<AllCalidadScraper.Season> seasonItems = new ArrayList<>();
    private EpisodeAdapter episodeAdapter;
    private boolean isSeriesContent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detalle_contenido);

        ivPoster = findViewById(R.id.iv_poster);
        tvTitle = findViewById(R.id.tv_title);
        tvYear = findViewById(R.id.tv_year);
        tvSynopsis = findViewById(R.id.tv_synopsis);
        tvEpisodesTitle = findViewById(R.id.tv_episodes_title);
        rvEpisodes = findViewById(R.id.rv_episodes);
        btnPlay = findViewById(R.id.btn_play);

        rvEpisodes.setLayoutManager(new LinearLayoutManager(this));
        episodeAdapter = new EpisodeAdapter(this::loadEpisodeServers);
        rvEpisodes.setAdapter(episodeAdapter);

        mediaItem = (MediaItem) getIntent().getSerializableExtra(EXTRA_MEDIA_ITEM);
        if (mediaItem == null) {
            Toast.makeText(this, "Error: no se recibió información", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        tvTitle.setText(mediaItem.getTitulo());
        tvYear.setText(mediaItem.getAnio());
        Glide.with(this)
            .load(mediaItem.getImagen())
            .placeholder(R.drawable.placeholder_poster)
            .error(R.drawable.placeholder_poster)
            .into(ivPoster);
        String synopsis = mediaItem.getSynopsis();
        tvSynopsis.setText((synopsis != null && !synopsis.isEmpty()) ? synopsis : "Cargando sinopsis...");
        btnPlay.setEnabled(false);
        btnPlay.setText("Cargando servidores...");

        loadDetail();

        btnPlay.setOnClickListener(v -> {
            if (!playUrls.isEmpty()) {
                String playTitle = mediaItem.getTitulo();
                PlayerContenidoActivity.start(this, playUrls, playTitle);
            } else {
                Toast.makeText(this, "No se pudo obtener la URL de reproducción", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadDetail() {
        new Thread(() -> {
            try {
                TmdbService.enrichMediaItem(mediaItem);

                List<String> urls = new ArrayList<>();
                List<AllCalidadScraper.Season> seasons = new ArrayList<>();
                int postId = mediaItem.getPostId();
                String mediaType = mediaItem.getMediaType();
                boolean isSeries = isSeriesType(mediaType);
                if (postId > 0) {
                    AllCalidadScraper.hit(postId, normalizePostType(mediaType));
                    if (isSeries) {
                        seasons = AllCalidadScraper.getSeasons(postId);
                    } else {
                        AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(postId);
                        urls = AllCalidadScraper.getPlayableUrls(player);
                    }
                }

                final List<String> finalUrls = urls;
                final List<AllCalidadScraper.Season> finalSeasons = seasons;
                final String desc = (mediaItem.getSynopsis() != null && !mediaItem.getSynopsis().isEmpty())
                        ? mediaItem.getSynopsis()
                        : "Sin sinopsis disponible.";
                final String finalPoster = mediaItem.getImagen();
                final boolean finalIsSeries = isSeries;

                runOnUiThread(() -> {
                    Glide.with(DetalleContenidoActivity.this)
                            .load(finalPoster)
                            .placeholder(R.drawable.placeholder_poster)
                            .error(R.drawable.placeholder_poster)
                            .into(ivPoster);
                    tvSynopsis.setText(desc);

                    isSeriesContent = finalIsSeries;
                    bindSeasons(finalSeasons, finalIsSeries);

                    if (finalIsSeries) {
                        btnPlay.setVisibility(View.GONE);
                    } else {
                        btnPlay.setVisibility(View.VISIBLE);
                    }

                    playUrls.clear();
                    playUrls.addAll(finalUrls);
                    updatePlayButtonState();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvSynopsis.setText("Error al cargar la sinopsis.");
                    if (!isSeriesContent) {
                        btnPlay.setEnabled(false);
                        btnPlay.setText("No disponible");
                    }
                });
            }
        }).start();
    }

    private void bindSeasons(List<AllCalidadScraper.Season> seasons, boolean showEpisodes) {
        seasonItems.clear();
        if (seasons != null) {
            for (AllCalidadScraper.Season season : seasons) {
                if (season == null || season.episodes == null || season.episodes.isEmpty()) {
                    continue;
                }
                seasonItems.add(season);
            }
        }
        episodeAdapter.setSeasons(seasonItems);

        int visibility = showEpisodes && !seasonItems.isEmpty() ? View.VISIBLE : View.GONE;
        tvEpisodesTitle.setVisibility(visibility);
        rvEpisodes.setVisibility(visibility);
    }

    private void loadEpisodeServers(AllCalidadScraper.Episode episode) {
        if (episode == null || episode.id <= 0) {
            return;
        }

        new Thread(() -> {
            try {
                AllCalidadScraper.hit(episode.id, "episodes");
                List<String> urls = AllCalidadScraper.getPlayableUrls(AllCalidadScraper.getPlayer(episode.id));
                String label = formatEpisodeLabel(episode);

                runOnUiThread(() -> {
                    if (urls == null || urls.isEmpty()) {
                        Toast.makeText(DetalleContenidoActivity.this, "No hay servidores para " + label, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ArrayList<String> playList = new ArrayList<>(urls);
                    String playTitle = mediaItem.getTitulo() + " - " + label;
                    PlayerContenidoActivity.startEpisode(
                            DetalleContenidoActivity.this,
                            playList,
                            playTitle,
                            mediaItem.getTitulo(),
                            mediaItem.getPostId(),
                            normalizePostType(mediaItem.getMediaType()),
                            episode.id,
                            episode.seasonNumber,
                            episode.episodeNumber
                    );
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(DetalleContenidoActivity.this, "Error cargando episodio", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void updatePlayButtonState() {
        if (isSeriesContent) {
            btnPlay.setVisibility(View.GONE);
            return;
        }

        btnPlay.setVisibility(View.VISIBLE);
        if (playUrls.isEmpty()) {
            btnPlay.setEnabled(false);
            btnPlay.setText("No disponible");
            return;
        }

        btnPlay.setEnabled(true);
        btnPlay.setText("Reproducir");
    }

    private boolean isSeriesType(String mediaType) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        return "tvshows".equals(type) || "animes".equals(type) || "series".equals(type) || "tv".equals(type);
    }

    private String normalizePostType(String mediaType) {
        if (mediaType == null || mediaType.trim().isEmpty()) {
            return "movies";
        }
        String type = mediaType.trim().toLowerCase(Locale.ROOT);
        if ("series".equals(type) || "tv".equals(type) || "tvshow".equals(type)) {
            return "tvshows";
        }
        if ("anime".equals(type)) {
            return "animes";
        }
        return type;
    }

    private String formatEpisodeLabel(AllCalidadScraper.Episode episode) {
        if (episode == null) {
            return "";
        }
        int season = episode.seasonNumber > 0 ? episode.seasonNumber : 1;
        int number = episode.episodeNumber > 0 ? episode.episodeNumber : 1;
        return String.format(Locale.getDefault(), "T%d:E%d", season, number);
    }

    public static void start(android.content.Context context, sv.edu.catolica.rex.models.MediaItem item) {
        android.content.Intent intent = new android.content.Intent(context, DetalleContenidoActivity.class);
        intent.putExtra(EXTRA_MEDIA_ITEM, item);
        context.startActivity(intent);
    }
}
