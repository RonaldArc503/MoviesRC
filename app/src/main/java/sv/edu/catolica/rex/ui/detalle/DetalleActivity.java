package sv.edu.catolica.rex.ui.detalle;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
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
import sv.edu.catolica.rex.network.smart.SmartScraperEngine;
import sv.edu.catolica.rex.ui.player.PlayerContenidoActivity;
import sv.edu.catolica.rex.ui.player.PlaybackCacheHelper;

public class DetalleActivity extends AppCompatActivity {

    // Constantes
    public static final String EXTRA_MEDIA_ITEM = "media_item";

    // Vistas compartidas
    private ImageView ivBackdrop;
    private TextView tvTitleHero;
    private TextView tvYearHero;
    private TextView tvRating;
    private TextView tvSeasonsCount;
    private TextView tvSynopsis;
    private TextView tvBadgeType;
    private TextView tvBadgeQuality;
    private Button btnPlay;
    private Button btnMyList;
    private LinearLayout llGenres;
    private WrapContentRecyclerView rvEpisodes;

    // Vistas solo Mobile
    private TextView tvSynopsisToggle;
    private LinearLayout llSeasonHeader;
    private TextView tvSeasonSelector;

    // Vistas solo TV
    private View panelEpisodes;
    private ImageView ivBack;

    // Estado
    private boolean isTV;
    private boolean synopsisExpanded = false;
    private MediaItem mediaItem;
    private final ArrayList<String> playUrls = new ArrayList<>();
    private final List<AllCalidadScraper.Season> seasonItems = new ArrayList<>();
    private final SmartScraperEngine smartScraperEngine = new SmartScraperEngine();
    private EpisodeAdapter episodeAdapter;
    private boolean isSeries = false;
    private AllCalidadScraper.Episode firstSeriesEpisode;
    private int selectedEpisodeId = -1;
    private int episodesFirstVisiblePos = 0;
    private int episodesTopOffset = 0;
    private String lastPrefetchedUrl = "";

    private final ActivityResultLauncher<Intent> playerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result == null || result.getData() == null) {
                    restoreEpisodeNavigationState(false);
                    return;
                }
                Intent data = result.getData();
                selectedEpisodeId = data.getIntExtra(PlayerContenidoActivity.RESULT_EPISODE_ID, selectedEpisodeId);
                restoreEpisodeNavigationState(true);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isTV = isTelevision();
        setContentView(R.layout.activity_detail); // Android selecciona automático según carpeta
        if (findViewById(R.id.panel_episodes) != null) {
            isTV = true;
        }

        bindViews();
        setupToolbar();
        setupRecyclerView();
        setupListeners();

        // Recibir datos
        mediaItem = (MediaItem) getIntent().getSerializableExtra(EXTRA_MEDIA_ITEM);
        if (mediaItem == null) {
            Toast.makeText(this, "Error: no se recibiÃ³ informaciÃ³n", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        displayBasicInfo();
        if (savedInstanceState != null) {
            selectedEpisodeId = savedInstanceState.getInt("selected_episode_id", -1);
            episodesFirstVisiblePos = savedInstanceState.getInt("episodes_scroll_pos", 0);
            episodesTopOffset = savedInstanceState.getInt("episodes_scroll_offset", 0);
        }
        loadDetail();
    }

    private boolean isTelevision() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null &&
            uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        int uiMode = getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void bindViews() {
        ivBackdrop = findViewById(R.id.iv_backdrop);
        tvTitleHero = findViewById(R.id.tv_title_hero);
        tvYearHero = findViewById(R.id.tv_year_hero);
        tvRating = findViewById(R.id.tv_rating);
        tvSeasonsCount = findViewById(R.id.tv_seasons_count);
        tvSynopsis = findViewById(R.id.tv_synopsis);
        tvBadgeType = findViewById(R.id.tv_badge_type);
        tvBadgeQuality = findViewById(R.id.tv_badge_quality);
        btnPlay = findViewById(R.id.btn_play);
        btnMyList = findViewById(R.id.btn_my_list);
        llGenres = findViewById(R.id.ll_genres);
        rvEpisodes = findViewById(R.id.rv_episodes);

        if (!isTV) {
            tvSynopsisToggle = findViewById(R.id.tv_synopsis_toggle);
            llSeasonHeader = findViewById(R.id.ll_season_header);
            tvSeasonSelector = findViewById(R.id.tv_season_selector);
        } else {
            panelEpisodes = findViewById(R.id.panel_episodes);
            ivBack = findViewById(R.id.iv_back);
        }
    }

    private void setupToolbar() {
        if (!isTV) {
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setDisplayShowTitleEnabled(false);
            }
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        } else if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }

    private void setupRecyclerView() {
        episodeAdapter = new EpisodeAdapter(this::loadEpisodeServers, rvEpisodes);
        rvEpisodes.setLayoutManager(new LinearLayoutManager(this));
        rvEpisodes.setItemAnimator(null);
        rvEpisodes.setAdapter(episodeAdapter);
    }

    private void displayBasicInfo() {
        tvTitleHero.setText(mediaItem.getTitulo());
        tvYearHero.setText(mediaItem.getAnio());
        
        if (tvRating != null) {
            tvRating.setText("â˜… " + (mediaItem.getRating() > 0 ? mediaItem.getRating() : "8.0"));
        }

        String synopsis = mediaItem.getSynopsis();
        tvSynopsis.setText((synopsis != null && !synopsis.isEmpty()) ? synopsis : "Cargando sinopsis...");

        if (ivBackdrop != null) {
            String toLoad = mediaItem.getImagen();
            if (isTV && mediaItem.getBackdrop() != null && !mediaItem.getBackdrop().isEmpty()) {
                toLoad = mediaItem.getBackdrop();
            }
            if (toLoad != null && !toLoad.isEmpty()) {
                Glide.with(this)
                        .load(toLoad)
                        .placeholder(R.drawable.placeholder_poster)
                        .error(R.drawable.placeholder_poster)
                        .into(ivBackdrop);
            }
        }

        // Badges
        String mediaType = mediaItem.getMediaType();
        isSeries = isSeriesType(mediaType);
        if (tvBadgeType != null) {
            tvBadgeType.setText(isSeries ? "SERIE" : "PELÃCULA");
        }
        if (tvBadgeQuality != null) {
            tvBadgeQuality.setText("HD");
        }

        btnPlay.setEnabled(false);
        btnPlay.setText("Cargando...");
        if (isTV) {
            btnPlay.setBackgroundResource(R.drawable.bg_btn_play_red_tv);
            btnPlay.setTextColor(Color.WHITE);
        }
    }

    private void loadDetail() {
        new Thread(() -> {
            try {
                // Enriquecer con TMDB
                TmdbService.enrichMediaItem(mediaItem);

                List<String> urls = new ArrayList<>();
                List<AllCalidadScraper.Season> seasons = new ArrayList<>();
                int postId = mediaItem.getPostId();
                String mediaType = mediaItem.getMediaType();
                boolean series = isSeriesType(mediaType);
                if (!series) {
                    urls = smartScraperEngine.resolveMovieUrls(DetalleActivity.this, mediaItem);
                } else if (postId <= 0) {
                    // Non-AllCalidad series won't have seasons/episode ids; at least try S01E01 via smart providers.
                    urls = smartScraperEngine.resolveEpisodeUrls(DetalleActivity.this, mediaItem, 1, 1);
                    seasons = TmdbService.getTvSeasonsFallback(mediaItem);
                }

                if (postId > 0) {
                    AllCalidadScraper.hit(postId, normalizePostType(mediaType));
                    if (series) {
                        seasons = AllCalidadScraper.getSeasons(postId);
                    } else {
                        if (urls == null || urls.isEmpty()) {
                            AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(postId);
                            urls = AllCalidadScraper.getPlayableUrls(player);
                        }
                    }
                }

                final List<String> finalUrls = urls;
                final List<AllCalidadScraper.Season> finalSeasons = seasons;
                final String desc = (mediaItem.getSynopsis() != null && !mediaItem.getSynopsis().isEmpty())
                    ? mediaItem.getSynopsis()
                    : "Sin sinopsis disponible.";
                final String finalPoster = mediaItem.getImagen();
                final boolean finalIsSeries = series;

                runOnUiThread(() -> {
                    tvSynopsis.setText(desc);

                    // Provide series poster to adapter so episodes without a still use it
                    episodeAdapter.setSeriesPoster(finalPoster);
                    bindSeasons(finalSeasons, finalIsSeries);

                    btnPlay.setVisibility(View.VISIBLE);
                    if (finalIsSeries && finalSeasons != null && !finalSeasons.isEmpty()) {
                        showEpisodesSection();
                    }

                    playUrls.clear();
                    playUrls.addAll(finalUrls);
                    updatePlayButtonState();
                    requestPlayFocusIfTv();
                    if (selectedEpisodeId > 0) {
                        restoreEpisodeNavigationState(true);
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvSynopsis.setText("Error al cargar la sinopsis.");
                    if (!isSeries) {
                        btnPlay.setEnabled(false);
                        btnPlay.setText("No disponible");
                    }
                });
            }
        }).start();
    }

    private void bindSeasons(List<AllCalidadScraper.Season> seasons, boolean showEpisodes) {
        seasonItems.clear();
        firstSeriesEpisode = null;
        if (seasons != null) {
            for (AllCalidadScraper.Season season : seasons) {
                if (season == null || season.episodes == null || season.episodes.isEmpty()) {
                    continue;
                }
                seasonItems.add(season);
                if (firstSeriesEpisode == null) {
                    for (AllCalidadScraper.Episode episode : season.episodes) {
                        if (episode != null && episode.id > 0) {
                            firstSeriesEpisode = episode;
                            break;
                        }
                    }
                }
            }
        }
        episodeAdapter.setSeasons(seasonItems);

        // Actualizar contador de temporadas
        if (tvSeasonsCount != null && seasonItems != null) {
            int count = seasonItems.size();
            tvSeasonsCount.setText(count + (count == 1 ? " temporada" : " temporadas"));
        }

        int visibility = showEpisodes && !seasonItems.isEmpty() ? View.VISIBLE : View.GONE;
        if (rvEpisodes != null) rvEpisodes.setVisibility(visibility);
    }

    private void showEpisodesSection() {
        if (rvEpisodes != null) rvEpisodes.setVisibility(View.VISIBLE);
        if (!isTV) {
            if (llSeasonHeader != null) llSeasonHeader.setVisibility(View.VISIBLE);
        } else {
            if (panelEpisodes != null) panelEpisodes.setVisibility(View.VISIBLE);
        }
    }

    private void loadEpisodeServers(AllCalidadScraper.Episode episode) {
        if (episode == null || episode.id <= 0) {
            return;
        }

        new Thread(() -> {
            try {
                List<String> urls = smartScraperEngine.resolveEpisodeUrls(
                        DetalleActivity.this,
                        mediaItem,
                        episode.seasonNumber,
                        episode.episodeNumber,
                        episode.id
                );
                if (urls == null || urls.isEmpty()) {
                    if (mediaItem.getPostId() > 0) {
                        AllCalidadScraper.hit(episode.id, "episodes");
                        urls = AllCalidadScraper.getPlayableUrls(AllCalidadScraper.getPlayer(episode.id));
                    }
                }
                String label = formatEpisodeLabel(episode);
                final List<String> finalUrls = urls;

                runOnUiThread(() -> {
                    if (finalUrls == null || finalUrls.isEmpty()) {
                        Toast.makeText(DetalleActivity.this, "No hay servidores para " + label, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ArrayList<String> playList = new ArrayList<>(finalUrls);
                    String playTitle = mediaItem.getTitulo() + " - " + label;
                    selectedEpisodeId = episode.id;
                    captureEpisodeNavigationState();
                    Intent playerIntent = PlayerContenidoActivity.createEpisodeIntent(
                            DetalleActivity.this,
                            playList,
                            playTitle,
                            mediaItem.getTitulo(),
                            mediaItem.getPostId(),
                            normalizePostType(mediaItem.getMediaType()),
                            episode.id,
                            episode.seasonNumber,
                            episode.episodeNumber
                    );
                    playerLauncher.launch(playerIntent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(DetalleActivity.this, "Error cargando episodio", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void updatePlayButtonState() {
        btnPlay.setVisibility(View.VISIBLE);

        if (isSeries) {
            if (firstSeriesEpisode == null) {
                // Fallback: allow playback if we resolved at least one playable URL (S01E01).
                if (playUrls.isEmpty()) {
                    btnPlay.setEnabled(false);
                    btnPlay.setText("No disponible");
                    return;
                }

                btnPlay.setEnabled(true);
                btnPlay.setText("Reproducir T1:E1");
                return;
            }

            btnPlay.setEnabled(true);
            btnPlay.setText("Reproducir " + formatEpisodeLabel(firstSeriesEpisode));
            return;
        }

        if (playUrls.isEmpty()) {
            btnPlay.setEnabled(false);
            btnPlay.setText("No disponible");
            return;
        }

        btnPlay.setEnabled(true);
        btnPlay.setText("Reproducir");

        // Warm the first URL in the background so the first playback starts faster.
        triggerPlaybackPrefetch();
    }

    private void triggerPlaybackPrefetch() {
        if (playUrls.isEmpty()) {
            return;
        }

        String firstUrl = playUrls.get(0);
        if (firstUrl == null || firstUrl.trim().isEmpty()) {
            return;
        }

        String normalized = firstUrl.trim();
        if (normalized.equals(lastPrefetchedUrl)) {
            return;
        }

        lastPrefetchedUrl = normalized;
        PlaybackCacheHelper.preloadFirstChunk(this, normalized, PlaybackCacheHelper.buildPlaybackHeaders(normalized));
    }

    private void setupListeners() {
        if (isTV) {
            setupTvFocusStates();
        }

        // BotÃ³n reproducir
        if (btnPlay != null) {
            btnPlay.setOnClickListener(v -> {
                if (isSeries) {
                    if (firstSeriesEpisode != null) {
                        playFirstSeriesEpisode();
                        return;
                    }
                    if (!playUrls.isEmpty()) {
                        String playTitle = mediaItem.getTitulo() + " - T1:E1";
                        captureEpisodeNavigationState();
                        playerLauncher.launch(PlayerContenidoActivity.createIntent(this, playUrls, playTitle));
                        return;
                    }
                    Toast.makeText(this, "No hay episodios disponibles", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!playUrls.isEmpty()) {
                    String playTitle = mediaItem.getTitulo();
                    captureEpisodeNavigationState();
                    playerLauncher.launch(PlayerContenidoActivity.createIntent(this, playUrls, playTitle));
                } else {
                    Toast.makeText(this, "No se pudo obtener la URL de reproducciÃ³n", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // BotÃ³n Mi Lista
        if (btnMyList != null) {
            btnMyList.setOnClickListener(v -> toggleMyList());
        }

        // Expandir sinopsis (mobile)
        if (tvSynopsisToggle != null && tvSynopsis != null) {
            tvSynopsisToggle.setOnClickListener(v -> {
                synopsisExpanded = !synopsisExpanded;
                if (synopsisExpanded) {
                    tvSynopsis.setMaxLines(Integer.MAX_VALUE);
                    tvSynopsisToggle.setText("Ver menos");
                } else {
                    tvSynopsis.setMaxLines(4);
                    tvSynopsisToggle.setText("Ver mÃ¡s");
                }
            });
        }

        // BotÃ³n back TV
        if (ivBack != null) {
            ivBack.setOnClickListener(v -> onBackPressed());
        }

        // Selector de temporada
        if (tvSeasonSelector != null) {
            tvSeasonSelector.setOnClickListener(v -> showSeasonPicker());
        }
    }

    private void setupTvFocusStates() {
        if (btnPlay != null) {
            btnPlay.setFocusableInTouchMode(true);
            btnPlay.setOnFocusChangeListener((v, hasFocus) -> {
                v.setSelected(hasFocus);
                if (hasFocus) {
                    btnPlay.setBackgroundResource(R.drawable.bg_btn_play_white_tv);
                    btnPlay.setTextColor(Color.BLACK);
                } else {
                    btnPlay.setBackgroundResource(R.drawable.bg_btn_play_red_tv);
                    btnPlay.setTextColor(Color.WHITE);
                }
                v.animate()
                        .scaleX(hasFocus ? 1.06f : 1.0f)
                        .scaleY(hasFocus ? 1.06f : 1.0f)
                        .setDuration(150L)
                        .start();
                v.setAlpha(hasFocus ? 1.0f : 0.92f);
                v.setTranslationZ(hasFocus ? 10f : 0f);
            });
        }

        if (btnMyList != null) {
            btnMyList.setFocusableInTouchMode(true);
            btnMyList.setOnFocusChangeListener((v, hasFocus) -> {
                v.setSelected(hasFocus);
                v.animate()
                        .scaleX(hasFocus ? 1.05f : 1.0f)
                        .scaleY(hasFocus ? 1.05f : 1.0f)
                        .setDuration(150L)
                        .start();
                v.setAlpha(hasFocus ? 1.0f : 0.92f);
                v.setTranslationZ(hasFocus ? 8f : 0f);
            });
        }

        if (ivBack != null) {
            ivBack.setFocusableInTouchMode(true);
            ivBack.setOnFocusChangeListener((v, hasFocus) -> v.animate()
                    .scaleX(hasFocus ? 1.07f : 1.0f)
                    .scaleY(hasFocus ? 1.07f : 1.0f)
                    .setDuration(150L)
                    .start());
        }
    }

    private void playFirstSeriesEpisode() {
        if (firstSeriesEpisode == null || firstSeriesEpisode.id <= 0) {
            Toast.makeText(this, "No hay episodios disponibles", Toast.LENGTH_SHORT).show();
            return;
        }
        loadEpisodeServers(firstSeriesEpisode);
    }

    private void requestPlayFocusIfTv() {
        if (!isTV || btnPlay == null || !btnPlay.isShown()) {
            return;
        }
        btnPlay.post(() -> {
            if (btnPlay.isShown() && btnPlay.isEnabled()) {
                btnPlay.requestFocus();
                btnPlay.setSelected(true);
            }
        });
    }

    @Override
    protected void onPause() {
        captureEpisodeNavigationState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        PlaybackCacheHelper.cancelPreload();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        captureEpisodeNavigationState();
        outState.putInt("selected_episode_id", selectedEpisodeId);
        outState.putInt("episodes_scroll_pos", episodesFirstVisiblePos);
        outState.putInt("episodes_scroll_offset", episodesTopOffset);
    }

    private void captureEpisodeNavigationState() {
        if (rvEpisodes == null) {
            return;
        }

        RecyclerView.LayoutManager manager = rvEpisodes.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            return;
        }
        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int firstVisible = linear.findFirstVisibleItemPosition();
        if (firstVisible >= 0) {
            episodesFirstVisiblePos = firstVisible;
        }
        View topChild = rvEpisodes.getChildAt(0);
        if (topChild != null) {
            episodesTopOffset = topChild.getTop() - rvEpisodes.getPaddingTop();
        }

        if (episodeAdapter == null) {
            return;
        }
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        RecyclerView.ViewHolder holder = rvEpisodes.findContainingViewHolder(focused);
        if (holder == null) {
            return;
        }
        AllCalidadScraper.Episode focusedEpisode =
                episodeAdapter.getEpisodeAtAdapterPosition(holder.getBindingAdapterPosition());
        if (focusedEpisode != null && focusedEpisode.id > 0) {
            selectedEpisodeId = focusedEpisode.id;
        }
    }

    private void restoreEpisodeNavigationState(boolean requestEpisodeFocus) {
        if (rvEpisodes == null || episodeAdapter == null) {
            return;
        }
        rvEpisodes.post(() -> {
            RecyclerView.LayoutManager manager = rvEpisodes.getLayoutManager();
            if (!(manager instanceof LinearLayoutManager)) {
                return;
            }
            LinearLayoutManager linear = (LinearLayoutManager) manager;
            linear.scrollToPositionWithOffset(Math.max(0, episodesFirstVisiblePos), episodesTopOffset);

            if (!requestEpisodeFocus || selectedEpisodeId <= 0) {
                return;
            }

            int targetPos = episodeAdapter.findPositionByEpisodeId(selectedEpisodeId);
            if (targetPos == RecyclerView.NO_POSITION) {
                return;
            }

            linear.scrollToPositionWithOffset(Math.max(0, targetPos - 1), 0);
            rvEpisodes.post(() -> {
                RecyclerView.ViewHolder target =
                        rvEpisodes.findViewHolderForAdapterPosition(targetPos);
                if (target != null) {
                    target.itemView.requestFocus();
                }
            });
        });
    }

    private void showSeasonPicker() {
        // TODO: mostrar diÃ¡logo con selector de temporada
        Toast.makeText(this, "Selector de temporada - PrÃ³ximamente", Toast.LENGTH_SHORT).show();
    }

    private void toggleMyList() {
        // TODO: implementar guardado en lista local
        Toast.makeText(this, "Agregado a Mi Lista", Toast.LENGTH_SHORT).show();
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
        if (episode == null) return "";
        int season = episode.seasonNumber > 0 ? episode.seasonNumber : 1;
        int number = episode.episodeNumber > 0 ? episode.episodeNumber : 1;
        return String.format(Locale.getDefault(), "T%d:E%d", season, number);
    }

    // MÃ©todo estÃ¡tico para iniciar la actividad
    public static void start(Context context, MediaItem item) {
        Intent intent = new Intent(context, DetalleActivity.class);
        intent.putExtra(EXTRA_MEDIA_ITEM, item);
        context.startActivity(intent);
    }
}



