package sv.edu.catolica.rex.ui.detalle;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;
import sv.edu.catolica.rex.network.smart.SmartScraperEngine;
import sv.edu.catolica.rex.models.FavoriteItem;
import sv.edu.catolica.rex.models.UserSettings;
import sv.edu.catolica.rex.models.WatchedHistoryItem;
import sv.edu.catolica.rex.repository.UserDataManager;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;
import sv.edu.catolica.rex.ui.player.PlayerContenidoActivity;
import sv.edu.catolica.rex.ui.player.PlaybackCacheHelper;

public class DetalleActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_ITEM = "media_item";

    // Vistas (IDs compartidos entre phone y TV)
    private ImageView ivBackdrop;
    private TextView tvDetailTitle;
    private TextView tvMatch;
    private TextView tvYear;
    private TextView tvDuration;
    private TextView tvSeasons;
    private TextView tvRating;
    private TextView tvBadgeQuality;
    private TextView tvSynopsis;
    private TextView tvCastCrew;
    private View btnDetailPlay;
    private TextView tvDetailPlayText;
    private View btnMylist;
    private ImageView imgMylistIcon;
    private TextView tvMylistLabel;
    private View btnBack;
    private View seasonSelectorChip;
    private boolean isDetailLoading = true;
    private TextView tvSeasonSelected;
    private TextView tvEpisodeCount;
    private TextView tvMoreLikeTitle;
    private WrapContentRecyclerView rvEpisodes;
    private RecyclerView rvMoreLikeThis;

    private EpisodeAdapter episodeAdapter;
    private PosterAdapter posterAdapter;

    private boolean isTV;
    private boolean synopsisExpanded = false;
    private MediaItem mediaItem;
    private final ArrayList<String> playUrls = new ArrayList<>();
    private final List<AllCalidadScraper.Season> seasonItems = new ArrayList<>();
    private final SmartScraperEngine smartScraperEngine = new SmartScraperEngine();
    private boolean isSeries = false;
    private AllCalidadScraper.Episode firstSeriesEpisode;
    private int selectedEpisodeId = -1;
    private int episodesFirstVisiblePos = 0;
    private int episodesTopOffset = 0;
    private String lastPrefetchedUrl = "";
    private long resumePositionMs = 0;
    private String resumeContentId = "";
    private ContinueWatchingItem resumeItem;
    private boolean isFavorite = false;
    private boolean pendingPlayWhenLoaded = false;
    private UserDataManager.DataChangeListener userDataListener;

    private final ActivityResultLauncher<Intent> playerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                refreshResumeState();
                if (result == null || result.getData() == null) {
                    restoreEpisodeNavigationState(false);
                    return;
                }
                Intent data = result.getData();
                selectedEpisodeId = data.getIntExtra(PlayerContenidoActivity.RESULT_EPISODE_ID, selectedEpisodeId);
                restoreEpisodeNavigationState(true);
            });

    private void launchPlayer(Intent intent) {
        playerLauncher.launch(intent);
        overridePendingTransition(android.R.anim.fade_in, 0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PlaybackCacheHelper.initCacheAsync(this);

        mediaItem = (MediaItem) getIntent().getSerializableExtra(EXTRA_MEDIA_ITEM);
        if (mediaItem == null) {
            Toast.makeText(this, "Error: no se recibió información", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String detailUrl = mediaItem.getDetailUrl() != null ? mediaItem.getDetailUrl().trim().toLowerCase(Locale.ROOT) : "";
        boolean isCanela = "novelas".equalsIgnoreCase(mediaItem.getFuente())
                || "canela".equalsIgnoreCase(mediaItem.getFuente())
                || detailUrl.contains("canela.tv");
        if (isCanela && (detailUrl.contains("/movie/") || detailUrl.contains("/pelicula/") || detailUrl.contains("/tvepisode/"))) {
            isSeries = false;
            mediaItem.setMediaType("movies");
        } else {
            isSeries = isSeriesType(mediaItem.getMediaType());
        }
        isTV = isTelevision();

        // Auto-resuelve la variante TV (layout-television/) si aplica.
        setContentView(isSeries ? R.layout.activity_series_detail : R.layout.activity_movie_detail);

        bindViews();
        setupRecyclerView();
        setupListeners();

        refreshResumeState();

        // Pre-resolución instantánea (0 ms) en memoria:
        // 1. Películas de Canela TV / Novelas
        if (!isSeries && isCanela) {
            String slugOrId = sv.edu.catolica.rex.network.smart.CanelaScraper.extractMovieSlugOrId(mediaItem.getDetailUrl());
            if (slugOrId != null && !slugOrId.isEmpty()) {
                String moviePlayUrl = sv.edu.catolica.rex.network.smart.CanelaScraper.buildMoviePlayerUrl(slugOrId);
                playUrls.clear();
                playUrls.add(moviePlayUrl);
                isDetailLoading = false;
            }
        }

        // 2. Caché de reproducción para películas generales
        if (!isSeries && playUrls.isEmpty()) {
            List<String> cachedUrls = smartScraperEngine.getCachedUrls(this, mediaItem, 0, 0);
            if (cachedUrls != null && !cachedUrls.isEmpty()) {
                playUrls.clear();
                playUrls.addAll(cachedUrls);
                isDetailLoading = false;
            }
        }

        // 3. Caché de temporadas para series/novelas
        if (isSeries) {
            List<AllCalidadScraper.Season> cachedSeasons = sv.edu.catolica.rex.network.smart.SeriesSeasonsCache.get(
                    mediaItem.getTitulo(), mediaItem.getPostId(), mediaItem.getDetailUrl());
            if (cachedSeasons != null && !cachedSeasons.isEmpty()) {
                bindSeasons(cachedSeasons, true);
                showEpisodesSection();
                isDetailLoading = false;
            }
        }

        displayBasicInfo();
        updateFavoriteButtonState();
        registerUserDataListener();
        if (savedInstanceState != null) {
            selectedEpisodeId = savedInstanceState.getInt("selected_episode_id", -1);
            episodesFirstVisiblePos = savedInstanceState.getInt("episodes_scroll_pos", 0);
            episodesTopOffset = savedInstanceState.getInt("episodes_scroll_offset", 0);
        }

        loadDetail();
        requestPlayFocusIfTv();
    }

    private boolean isTelevision() {
        return sv.edu.catolica.rex.utils.TvUtils.isTv(this);
    }

    private void bindViews() {
        ivBackdrop = findViewById(R.id.img_backdrop);
        tvDetailTitle = findViewById(R.id.tv_detail_title);
        tvMatch = findViewById(R.id.tv_match);
        tvYear = findViewById(R.id.tv_year);
        tvDuration = findViewById(R.id.tv_duration);
        tvSeasons = findViewById(R.id.tv_seasons);
        tvRating = findViewById(R.id.tv_rating);
        tvBadgeQuality = findViewById(R.id.badge_quality);
        tvSynopsis = findViewById(R.id.tv_synopsis);
        tvCastCrew = findViewById(R.id.tv_cast_crew);
        btnDetailPlay = findViewById(R.id.btn_detail_play);
        tvDetailPlayText = findViewById(R.id.tv_detail_play_text);
        btnMylist = findViewById(R.id.btn_mylist);
        imgMylistIcon = findViewById(R.id.img_mylist_icon);
        tvMylistLabel = findViewById(R.id.tv_mylist_label);
        btnBack = findViewById(R.id.btn_back);
        seasonSelectorChip = findViewById(R.id.season_selector_chip);
        tvSeasonSelected = findViewById(R.id.tv_season_selected);
        tvEpisodeCount = findViewById(R.id.tv_episode_count);
        tvMoreLikeTitle = findViewById(R.id.tv_more_like_title);
        rvEpisodes = findViewById(R.id.rv_episodes);
        rvMoreLikeThis = findViewById(R.id.rv_more_like_this);
    }

    private void setupRecyclerView() {
        // El adaptador se crea siempre (métodos como bindSeasons lo referencian);
        // solo se asocia al RecyclerView cuando existe (layout de serie).
        episodeAdapter = new EpisodeAdapter(this::loadEpisodeServers);
        if (rvEpisodes != null) {
            rvEpisodes.setLayoutManager(new LinearLayoutManager(this));
            rvEpisodes.setItemAnimator(null);
            rvEpisodes.setAdapter(episodeAdapter);
        }

        posterAdapter = new PosterAdapter(this::openRelated);
        rvMoreLikeThis.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvMoreLikeThis.setItemAnimator(null);
        rvMoreLikeThis.setAdapter(posterAdapter);
        rvMoreLikeThis.setNestedScrollingEnabled(false);
    }

    private void displayBasicInfo() {
        tvDetailTitle.setText(mediaItem.getTitulo());
        tvYear.setText(mediaItem.getAnio());

        double rating = mediaItem.getRating();
        if (tvMatch != null) {
            tvMatch.setText(rating > 0
                    ? String.format(Locale.US, "%.0f%% para ti", Math.min(99, rating * 10))
                    : "97% para ti");
        }
        if (tvRating != null) {
            if (rating > 0) {
                tvRating.setText(String.format(Locale.US, "%.1f", rating));
                tvRating.setVisibility(View.VISIBLE);
            } else {
                tvRating.setVisibility(View.GONE);
            }
        }
        if (tvBadgeQuality != null) {
            tvBadgeQuality.setText(isSeries ? "HD" : "4K");
        }
        if (tvDuration != null) {
            // Sin dato de duración disponible por ahora.
            tvDuration.setVisibility(View.GONE);
        }
        if (tvCastCrew != null) {
            tvCastCrew.setVisibility(View.GONE);
        }

        TextView tvEditionTag = findViewById(R.id.tv_edition_tag);
        if (tvEditionTag != null) {
            tvEditionTag.setText(isSeries ? "SERIE" : "PELÍCULA");
        }

        String synopsis = mediaItem.getSynopsis();
        tvSynopsis.setText((synopsis != null && !synopsis.isEmpty()) ? synopsis : "Cargando sinopsis...");

        updateBackdropImage();
        updatePlayButtonState();
    }

    private void updateBackdropImage() {
        if (ivBackdrop == null || isFinishing() || isDestroyed()) return;
        String backdrop = sanitizeImageUrl(mediaItem != null ? mediaItem.getBackdrop() : null);
        String poster = sanitizeImageUrl(mediaItem != null ? mediaItem.getImagen() : null);

        String primary = !backdrop.isEmpty() ? backdrop : poster;
        String fallback = (!poster.isEmpty() && !poster.equals(primary)) ? poster : "";

        if (primary.isEmpty()) {
            ivBackdrop.setImageResource(R.drawable.placeholder_backdrop);
            return;
        }

        if (!fallback.isEmpty()) {
            Glide.with(this)
                    .load(primary)
                    .placeholder(R.drawable.placeholder_backdrop)
                    .error(Glide.with(this)
                            .load(fallback)
                            .placeholder(R.drawable.placeholder_backdrop)
                            .error(R.drawable.placeholder_backdrop)
                            .centerCrop())
                    .centerCrop()
                    .into(ivBackdrop);
        } else {
            Glide.with(this)
                    .load(primary)
                    .placeholder(R.drawable.placeholder_backdrop)
                    .error(R.drawable.placeholder_backdrop)
                    .centerCrop()
                    .into(ivBackdrop);
        }
    }

    private String sanitizeImageUrl(String rawUrl) {
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

    private void loadDetail() {
        if (!isSeries && !playUrls.isEmpty()) {
            isDetailLoading = false;
        } else if (isSeries && !seasonItems.isEmpty()) {
            isDetailLoading = false;
        } else {
            isDetailLoading = true;
        }
        updatePlayButtonState();

        new Thread(() -> {
            try {
                int postId = mediaItem.getPostId();
                String mediaType = mediaItem.getMediaType();
                final boolean series = isSeriesType(mediaType);
                String currentSource = mediaItem.getFuente() != null ? mediaItem.getFuente().trim().toLowerCase(Locale.ROOT) : "";
                boolean isTuDorama = "tudorama".equals(currentSource)
                        || (mediaItem.getDetailUrl() != null && mediaItem.getDetailUrl().contains("tudorama.com"));

                // 1) Resolver postId si no se tiene todavía (respaldo multi-fuente en AllCalidad)
                if (postId <= 0 && !"novelas".equals(currentSource) && !"canela".equals(currentSource)
                        && !isTuDorama) {
                    AllCalidadScraper.ContentItem found = AllCalidadScraper.findByTitleAndYear(
                            mediaItem.getTitulo(), mediaItem.getAnio(), series ? "tvshows" : "movies");
                    if (found != null && found.id > 0) {
                        postId = found.id;
                        mediaItem.setPostId(postId);
                    }
                }

                int activePostId = postId;

                // 2) Disparar hit en background (fire and forget en hilo dedicado, no bloquea)
                if (activePostId > 0 && ("allcalidad".equals(currentSource) || currentSource.isEmpty())) {
                    final int fPostId = activePostId;
                    final String fMediaType = mediaType;
                    new Thread(() -> {
                        try {
                            AllCalidadScraper.hit(fPostId, normalizePostType(fMediaType));
                        } catch (Exception ignored) { }
                    }, "hit-worker").start();
                }

                // 3) TMDB enrichment en hilo paralelo secundario (actualiza sinopsis/backdrop si encuentra mejores)
                new Thread(() -> {
                    try {
                        TmdbService.enrichMediaItem(mediaItem);
                        runOnUiThread(() -> {
                            updateBackdropImage();
                            String synopsis = mediaItem.getSynopsis();
                            if (synopsis != null && !synopsis.trim().isEmpty()) {
                                tvSynopsis.setText(synopsis);
                            }
                        });
                    } catch (Exception ignored) { }
                }, "tmdb-detail-enrich").start();

                // 4) Ruta crítica prioritaria:
                if (series) {
                    List<AllCalidadScraper.Season> seasons = new ArrayList<>();
                    boolean isNovelas = "novelas".equals(currentSource) || "canela".equals(currentSource)
                            || (mediaItem.getDetailUrl() != null && mediaItem.getDetailUrl().contains("canela.tv"));

                    if (isNovelas) {
                        try {
                            sv.edu.catolica.rex.network.smart.CanelaScraper canelaScraper =
                                    new sv.edu.catolica.rex.network.smart.CanelaScraper();
                            seasons = canelaScraper.getSeasons(mediaItem.getDetailUrl());
                            if (seasons != null && !seasons.isEmpty()) {
                                mediaItem.setFuente("novelas");
                                activePostId = 0;
                            } else {
                                // Canela no devolvió temporadas: es una película o unitario en el catálogo de novelas
                                sv.edu.catolica.rex.network.smart.SmartPlaybackResult canelaMovie =
                                        canelaScraper.resolveMovie(mediaItem);
                                if (canelaMovie != null && canelaMovie.hasPlayableUrls()) {
                                    playUrls.clear();
                                    playUrls.addAll(canelaMovie.toPlayableUrls());
                                    mediaItem.setMediaType("movies");
                                    isSeries = false;
                                }
                            }
                        } catch (Exception ignored) { }
                    } else if (isTuDorama) {
                        seasons = new sv.edu.catolica.rex.network.smart.TuDoramaScraper()
                                .getSeasons(mediaItem.getDetailUrl());
                        if (seasons != null && !seasons.isEmpty()) {
                            mediaItem.setFuente("tudorama");
                            mediaItem.setPostId(0);
                            activePostId = 0;
                        }
                    } else if (activePostId > 0) {
                        seasons = AllCalidadScraper.getSeasons(activePostId);
                    }

                    // Fallback multi-fuente para series: si AllCalidad no tiene temporadas
                    if (isSeries && !isTuDorama && (seasons == null || seasons.isEmpty())) {
                        try {
                            String cuevanaUrl = mediaItem.getDetailUrl();
                            sv.edu.catolica.rex.network.smart.Cuevana3Scraper c3Scraper =
                                    new sv.edu.catolica.rex.network.smart.Cuevana3Scraper();
                            if (cuevanaUrl == null || !cuevanaUrl.contains("cuevana3")) {
                                List<MediaItem> c3Matches = c3Scraper.search(mediaItem.getTitulo(), 5);
                                if (c3Matches != null) {
                                    for (MediaItem m : c3Matches) {
                                        if (m != null && "tvshows".equalsIgnoreCase(m.getMediaType())) {
                                            cuevanaUrl = m.getDetailUrl();
                                            break;
                                        }
                                    }
                                    if (cuevanaUrl == null && !c3Matches.isEmpty()) {
                                        cuevanaUrl = c3Matches.get(0).getDetailUrl();
                                    }
                                }
                            }
                            if (cuevanaUrl != null && !cuevanaUrl.isEmpty()) {
                                List<AllCalidadScraper.Season> c3Seasons = c3Scraper.getSeasons(cuevanaUrl);
                                if (c3Seasons != null && !c3Seasons.isEmpty()) {
                                    seasons = c3Seasons;
                                    mediaItem.setDetailUrl(cuevanaUrl);
                                    mediaItem.setFuente("cuevana3");
                                    mediaItem.setPostId(0);
                                    activePostId = 0;
                                }
                            }
                        } catch (Exception ignored) { }
                    }

                    if (isSeries && (seasons == null || seasons.isEmpty())) {
                        try {
                            // 1) Intentar recuperar temporadas pre-sincronizadas desde Firebase RTDB (< 50ms)
                            if (mediaItem.getTmdbId() > 0) {
                                sv.edu.catolica.rex.network.smart.FirebaseEndpointResolver fbResolver =
                                        new sv.edu.catolica.rex.network.smart.FirebaseEndpointResolver();
                                List<AllCalidadScraper.Season> fbSeasons = fbResolver.getSeasons(mediaItem.getTmdbId());
                                if (fbSeasons != null && !fbSeasons.isEmpty()) {
                                    seasons = fbSeasons;
                                    if (mediaItem.getFuente() == null || mediaItem.getFuente().isEmpty()) {
                                        mediaItem.setFuente("tudorama");
                                    }
                                    mediaItem.setPostId(0);
                                    activePostId = 0;
                                }
                            }

                            // 2) Si aún no hay temporadas, consultar TuDorama directamente
                            if (!isTuDorama && (seasons == null || seasons.isEmpty())) {
                                String tudoramaUrl = mediaItem.getDetailUrl();
                                sv.edu.catolica.rex.network.smart.TuDoramaScraper tdScraper =
                                        new sv.edu.catolica.rex.network.smart.TuDoramaScraper();
                                if (tudoramaUrl == null || !tudoramaUrl.contains("tudorama")) {
                                    List<MediaItem> tdMatches = tdScraper.search(mediaItem.getTitulo(), 3);
                                    if (tdMatches != null && !tdMatches.isEmpty()) {
                                        tudoramaUrl = tdMatches.get(0).getDetailUrl();
                                    }
                                }
                                if (tudoramaUrl != null && tudoramaUrl.contains("tudorama")) {
                                    List<AllCalidadScraper.Season> tdSeasons = tdScraper.getSeasons(tudoramaUrl);
                                    if (tdSeasons != null && !tdSeasons.isEmpty()) {
                                        seasons = tdSeasons;
                                        mediaItem.setDetailUrl(tudoramaUrl);
                                        mediaItem.setFuente("tudorama");
                                        mediaItem.setPostId(0);
                                        activePostId = 0;
                                    }
                                }
                            }
                        } catch (Exception ignored) { }
                    }

                    if ("tudorama".equalsIgnoreCase(mediaItem.getFuente()) && seasons != null) {
                        for (AllCalidadScraper.Season season : seasons) {
                            if (season == null || season.episodes == null) continue;
                            for (AllCalidadScraper.Episode ep : season.episodes) {
                                if (ep != null && ep.id <= 0) {
                                    ep.id = sv.edu.catolica.rex.network.smart.TuDoramaScraper.stableEpisodeId(ep.code);
                                }
                            }
                        }
                    }
                    final List<AllCalidadScraper.Season> finalSeasons = seasons;
                    final String finalPoster = mediaItem.getImagen();
                    final int relatedPostId = activePostId;

                    runOnUiThread(() -> {
                        isDetailLoading = false;
                        if (!isSeries && !playUrls.isEmpty()) {
                            // Se convirtió dinámicamente en película porque no tenía temporadas
                            updatePlayButtonState();
                            requestPlayFocusIfTv();
                            if (pendingPlayWhenLoaded) {
                                pendingPlayWhenLoaded = false;
                                btnDetailPlay.performClick();
                            }
                            loadRelated(relatedPostId);
                            return;
                        }

                        episodeAdapter.setSeriesPoster(finalPoster);
                        bindSeasons(finalSeasons, true);

                        if (finalSeasons != null && !finalSeasons.isEmpty()) {
                            showEpisodesSection();
                            sv.edu.catolica.rex.network.smart.SeriesSeasonsCache.put(
                                    mediaItem.getTitulo(), mediaItem.getPostId(), mediaItem.getDetailUrl(), finalSeasons);
                        }

                        updatePlayButtonState();
                        requestPlayFocusIfTv();
                        if (selectedEpisodeId > 0) {
                            restoreEpisodeNavigationState(true);
                        }
                        if (pendingPlayWhenLoaded) {
                            pendingPlayWhenLoaded = false;
                            btnDetailPlay.performClick();
                        }
                        loadRelated(relatedPostId);
                    });

                } else {
                    if (!playUrls.isEmpty()) {
                        // Ya resuelto de forma instantánea (Canela o caché), no volver a consultar la red
                        final int relatedPostId = activePostId;
                        runOnUiThread(() -> {
                            isDetailLoading = false;
                            updatePlayButtonState();
                            requestPlayFocusIfTv();
                            if (pendingPlayWhenLoaded) {
                                pendingPlayWhenLoaded = false;
                                btnDetailPlay.performClick();
                            }
                            loadRelated(relatedPostId);
                        });
                    } else {
                        // Película: resolver URLs directamente
                        List<String> urls = new ArrayList<>(playUrls);
                        boolean isCanelaMovie = "novelas".equalsIgnoreCase(currentSource) || "canela".equalsIgnoreCase(currentSource)
                                || (mediaItem.getDetailUrl() != null && mediaItem.getDetailUrl().contains("canela.tv"));

                        if (urls.isEmpty() && isCanelaMovie) {
                            try {
                                sv.edu.catolica.rex.network.smart.CanelaScraper canelaScraper =
                                        new sv.edu.catolica.rex.network.smart.CanelaScraper();
                                sv.edu.catolica.rex.network.smart.SmartPlaybackResult canelaResult =
                                        canelaScraper.resolveMovie(mediaItem);
                                if (canelaResult != null && canelaResult.hasPlayableUrls()) {
                                    urls = canelaResult.toPlayableUrls();
                                }
                            } catch (Exception ignored) { }
                        }

                        if (urls.isEmpty()) {
                            urls = smartScraperEngine.resolveMovieUrls(DetalleActivity.this, mediaItem);
                        }
                        if ((urls == null || urls.isEmpty()) && activePostId > 0) {
                            try {
                                AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(activePostId);
                                urls = AllCalidadScraper.getPlayableUrls(player);
                            } catch (Exception ignored) { }
                        }
                        if (urls == null || urls.isEmpty()) {
                            try {
                                sv.edu.catolica.rex.network.smart.Cuevana3Scraper c3 = new sv.edu.catolica.rex.network.smart.Cuevana3Scraper();
                                sv.edu.catolica.rex.network.smart.SmartPlaybackResult c3Res = c3.resolveMovie(mediaItem);
                                if (c3Res != null && c3Res.hasPlayableUrls()) {
                                    urls = c3Res.toPlayableUrls();
                                }
                            } catch (Exception ignored) { }
                        }

                        final List<String> finalUrls = urls != null ? urls : new ArrayList<>();
                        final int relatedPostId = activePostId;

                        runOnUiThread(() -> {
                            isDetailLoading = false;
                            playUrls.clear();
                            playUrls.addAll(finalUrls);
                            updatePlayButtonState();
                            requestPlayFocusIfTv();
                            if (pendingPlayWhenLoaded) {
                                pendingPlayWhenLoaded = false;
                                btnDetailPlay.performClick();
                            }
                            loadRelated(relatedPostId);
                        });
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    isDetailLoading = false;
                    pendingPlayWhenLoaded = false;
                    updatePlayButtonState();
                });
            }
        }, "detail-loader").start();
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

        if (tvSeasons != null && !seasonItems.isEmpty()) {
            int count = seasonItems.size();
            tvSeasons.setText(count + (count == 1 ? " temporada" : " temporadas"));
        }
        if (tvSeasonSelected != null && episodeAdapter.getSelectedSeasonNumber() > 0) {
            tvSeasonSelected.setText("Temporada " + episodeAdapter.getSelectedSeasonNumber());
        }
        if (tvEpisodeCount != null) {
            tvEpisodeCount.setText(episodeAdapter.getEpisodeCountInSelectedSeason() + " episodios");
        }

        int visibility = showEpisodes && !seasonItems.isEmpty() ? View.VISIBLE : View.GONE;
        if (rvEpisodes != null) {
            rvEpisodes.setVisibility(visibility);
        }
    }

    private void showEpisodesSection() {
        if (rvEpisodes != null) {
            rvEpisodes.setVisibility(View.VISIBLE);
        }
    }

    private void loadEpisodeServers(AllCalidadScraper.Episode episode) {
        if (episode == null) {
            return;
        }
        if (episode.id <= 0) {
            int sn = episode.seasonNumber > 0 ? episode.seasonNumber : 1;
            int en = episode.episodeNumber > 0 ? episode.episodeNumber : 1;
            boolean tudoramaEpisode = "tudorama".equalsIgnoreCase(mediaItem.getFuente())
                    || (episode.code != null && episode.code.contains("tudorama.com"));
            String key = tudoramaEpisode
                    ? (episode.code != null && !episode.code.isEmpty()
                    ? episode.code : mediaItem.getDetailUrl() + "|" + sn + "x" + en)
                    : sn + "x" + en;
            int stableId = key.hashCode() & 0x7fffffff;
            episode.id = tudoramaEpisode
                    ? (stableId == 0 ? 1 : stableId)
                    : Math.abs(key.hashCode() % 1000000) + 100000;
        }

        new Thread(() -> {
            try {
                List<String> urls = new ArrayList<>();

                // 1) Ruta directa para Novelas / Canela TV
                boolean isNovelas = (mediaItem.getFuente() != null && ("novelas".equalsIgnoreCase(mediaItem.getFuente()) || "canela".equalsIgnoreCase(mediaItem.getFuente())))
                        || (mediaItem.getDetailUrl() != null && mediaItem.getDetailUrl().contains("canela.tv"));
                if (isNovelas) {
                    try {
                        sv.edu.catolica.rex.network.smart.CanelaScraper canelaScraper =
                                new sv.edu.catolica.rex.network.smart.CanelaScraper();
                        sv.edu.catolica.rex.network.smart.SmartPlaybackResult canelaResult =
                                canelaScraper.resolveEpisode(mediaItem, episode.seasonNumber, episode.episodeNumber, episode.id);
                        if (canelaResult != null && canelaResult.hasPlayableUrls()) {
                            urls = canelaResult.toPlayableUrls();
                        }
                    } catch (Exception ignored) { }
                }

                // 1.5) TuDorama: doramas en español latino
                boolean isTuDorama = (mediaItem.getFuente() != null && "tudorama".equalsIgnoreCase(mediaItem.getFuente()))
                        || (mediaItem.getDetailUrl() != null && mediaItem.getDetailUrl().contains("tudorama.com"))
                        || (episode.code != null && episode.code.contains("tudorama.com"));
                if (isTuDorama && (urls == null || urls.isEmpty())) {
                    try {
                        sv.edu.catolica.rex.network.smart.TuDoramaScraper tdScraper =
                                new sv.edu.catolica.rex.network.smart.TuDoramaScraper();
                        String epUrl = episode.code != null && !episode.code.isEmpty() ? episode.code : mediaItem.getDetailUrl();
                        sv.edu.catolica.rex.network.smart.SmartPlaybackResult tdResult =
                                tdScraper.resolveEpisode(epUrl);
                        if (tdResult != null && tdResult.hasPlayableUrls()) {
                            urls = tdResult.toPlayableUrls();
                        }
                    } catch (Exception ignored) { }
                }

                // 2) Ruta directa y fiable: player del episodio por su id real en AllCalidad
                boolean isAllCalidad = !isNovelas && mediaItem.getPostId() > 0
                        && (mediaItem.getFuente() == null || "allcalidad".equalsIgnoreCase(mediaItem.getFuente()));
                if (isAllCalidad && episode.id > 0) {
                    try {
                        AllCalidadScraper.hit(episode.id, "episodes");
                        AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(episode.id);
                        urls = AllCalidadScraper.getPlayableUrls(player);
                    } catch (Exception e) {
                        // sigue al smart engine
                    }
                }

                // 2) Fallback: smart engine (caché / multi-fuente), con try/catch
                //    para que un fallo del enriquecimiento TMDB no rompa el episodio.
                if (!isTuDorama && (urls == null || urls.isEmpty())) {
                    try {
                        urls = smartScraperEngine.resolveEpisodeUrls(
                                DetalleActivity.this,
                                mediaItem,
                                episode.seasonNumber,
                                episode.episodeNumber,
                                episode.id
                        );
                    } catch (Exception e) {
                        urls = new ArrayList<>();
                    }
                }

                final List<String> finalUrls = urls == null ? new ArrayList<>() : urls;
                String label = formatEpisodeLabel(episode);

                runOnUiThread(() -> {
                    if (finalUrls.isEmpty()) {
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
                    addResumeExtras(playerIntent, episode.id);
                    launchPlayer(playerIntent);
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(DetalleActivity.this, "Error cargando episodio", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void addResumeExtras(Intent intent) {
        addResumeExtras(intent, -1);
    }

    private void addResumeExtras(Intent intent, int targetEpisodeId) {
        if (resumePositionMs > 0 && resumeContentId != null && !resumeContentId.isEmpty()) {
            if (targetEpisodeId <= 0 || (resumeItem != null && resumeItem.getEpisodeId() == targetEpisodeId)) {
                intent.putExtra("resume_position_ms", resumePositionMs);
                intent.putExtra("exo_resume_position_ms", resumePositionMs);
            }
        }
        if (resumeContentId != null && !resumeContentId.isEmpty()
                && (targetEpisodeId <= 0 || (resumeItem != null && resumeItem.getEpisodeId() == targetEpisodeId))) {
            intent.putExtra("exo_content_id", resumeContentId);
            intent.putExtra("exo_post_id", mediaItem.getPostId());
            intent.putExtra("exo_media_type", mediaItem.getMediaType());
            intent.putExtra("exo_series_title", mediaItem.getTitulo());
        }
        intent.putExtra("series_post_id", mediaItem.getPostId());
        intent.putExtra("series_title", mediaItem.getTitulo());
        intent.putExtra("series_post_type", mediaItem.getMediaType());
        intent.putExtra("series_source", mediaItem.getFuente());
        intent.putExtra("series_detail_url", mediaItem.getDetailUrl());
        intent.putExtra("exo_series_source", mediaItem.getFuente());
        intent.putExtra("exo_series_detail_url", mediaItem.getDetailUrl());
        String img = mediaItem.getImagen();
        if (img != null && !img.isEmpty()) {
            intent.putExtra("image_url", img);
            intent.putExtra("exo_image_url", img);
        }
    }

    private void updatePlayButtonState() {
        btnDetailPlay.setVisibility(View.VISIBLE);
        btnDetailPlay.setEnabled(true);

        if (isSeries) {
            AllCalidadScraper.Episode resumeEp = findResumeEpisode();
            if (resumeEp != null && resumeItem != null && ContinueWatchingStore.isCompleted(resumeItem)) {
                ContinueWatchingItem nextItem = ContinueWatchingStore.resolveNextEpisodeItem(resumeItem);
                AllCalidadScraper.Episode nextEpisode = nextItem != null
                        ? findEpisodeById(nextItem.getEpisodeId()) : null;
                if (nextEpisode != null) {
                    String epLabel = formatEpisodeLabel(nextEpisode);
                    tvDetailPlayText.setText("Continuar " + epLabel);
                    if (episodeAdapter != null) episodeAdapter.setResumeProgress(nextEpisode.id, 0);
                } else {
                    tvDetailPlayText.setText("Ver de nuevo " + formatEpisodeLabel(resumeEp));
                    if (episodeAdapter != null) episodeAdapter.setResumeProgress(-1, 0);
                }
                return;
            }
            if (resumeEp != null && resumeItem != null && !ContinueWatchingStore.isCompleted(resumeItem)) {
                String epLabel = formatEpisodeLabel(resumeEp);
                if (resumePositionMs > 0) {
                    int pct = resumeItem.getProgressPercent();
                    tvDetailPlayText.setText("Continuar " + epLabel + " (" + pct + "%)");
                    if (episodeAdapter != null) {
                        episodeAdapter.setResumeProgress(resumeEp.id, pct);
                    }
                } else {
                    tvDetailPlayText.setText("Continuar " + epLabel);
                    if (episodeAdapter != null) {
                        episodeAdapter.setResumeProgress(resumeEp.id, 0);
                    }
                }
                return;
            }

            if (firstSeriesEpisode == null) {
                if (resumePositionMs > 0) {
                    tvDetailPlayText.setText("Continuar viendo");
                } else {
                    tvDetailPlayText.setText("Reproducir");
                }
                return;
            }

            tvDetailPlayText.setText("Reproducir " + formatEpisodeLabel(firstSeriesEpisode));
            if (episodeAdapter != null) {
                episodeAdapter.setResumeProgress(-1, 0);
            }
            return;
        }

        // Película
        if (resumeItem != null && ContinueWatchingStore.isCompleted(resumeItem)) {
            tvDetailPlayText.setText("Ver de nuevo");
        } else if (resumePositionMs > 0 && resumeItem != null) {
            int pct = resumeItem.getProgressPercent();
            tvDetailPlayText.setText("Continuar viendo (" + pct + "%)");
        } else {
            tvDetailPlayText.setText("Reproducir");
        }

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

        btnDetailPlay.setOnClickListener(v -> {
            if (isSeries) {
                AllCalidadScraper.Episode resumeEp = findResumeEpisode();
                if (resumeEp != null && resumeItem != null && ContinueWatchingStore.isCompleted(resumeItem)) {
                    ContinueWatchingItem nextItem = ContinueWatchingStore.resolveNextEpisodeItem(resumeItem);
                    AllCalidadScraper.Episode nextEpisode = nextItem != null
                            ? findEpisodeById(nextItem.getEpisodeId()) : null;
                    if (nextEpisode != null) {
                        loadEpisodeServers(nextEpisode);
                    } else {
                        ContinueWatchingStore.prepareForReplay(DetalleActivity.this, resumeItem.getContentId());
                        loadEpisodeServers(resumeEp);
                    }
                    return;
                }
                if (resumeEp != null && resumeItem != null && !ContinueWatchingStore.isCompleted(resumeItem)) {
                    loadEpisodeServers(resumeEp);
                    return;
                }
                if (firstSeriesEpisode != null) {
                    playFirstSeriesEpisode();
                    return;
                }
                if (!playUrls.isEmpty()) {
                    String playTitle = mediaItem.getTitulo() + " - T1:E1";
                    captureEpisodeNavigationState();
                    Intent intent = PlayerContenidoActivity.createIntent(this, playUrls, playTitle);
                    addResumeExtras(intent);
                    launchPlayer(intent);
                    return;
                }
                if (isDetailLoading) {
                    pendingPlayWhenLoaded = true;
                    Toast.makeText(this, "Cargando episodios...", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean isCanelaItem = "novelas".equalsIgnoreCase(mediaItem.getFuente())
                        || "canela".equalsIgnoreCase(mediaItem.getFuente())
                        || (mediaItem.getDetailUrl() != null && mediaItem.getDetailUrl().contains("canela.tv"));
                if (isCanelaItem) {
                    try {
                        sv.edu.catolica.rex.network.smart.CanelaScraper canelaScraper =
                                new sv.edu.catolica.rex.network.smart.CanelaScraper();
                        sv.edu.catolica.rex.network.smart.SmartPlaybackResult canelaResult =
                                canelaScraper.resolveMovie(mediaItem);
                        if (canelaResult != null && canelaResult.hasPlayableUrls()) {
                            playUrls.clear();
                            playUrls.addAll(canelaResult.toPlayableUrls());
                            isSeries = false;
                            mediaItem.setMediaType("movies");
                            String playTitle = mediaItem.getTitulo();
                            captureEpisodeNavigationState();
                            Intent intent = PlayerContenidoActivity.createIntent(this, new ArrayList<>(playUrls), playTitle);
                            addResumeExtras(intent);
                            launchPlayer(intent);
                            return;
                        }
                    } catch (Exception ignored) {}
                }
                Toast.makeText(this, "No hay episodios disponibles", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!playUrls.isEmpty()) {
                if (resumeItem != null && ContinueWatchingStore.isCompleted(resumeItem)) {
                    ContinueWatchingStore.prepareForReplay(DetalleActivity.this, resumeItem.getContentId());
                }
                String playTitle = mediaItem.getTitulo();
                captureEpisodeNavigationState();
                Intent intent = PlayerContenidoActivity.createIntent(this, playUrls, playTitle);
                addResumeExtras(intent);
                launchPlayer(intent);
            } else if (isDetailLoading) {
                pendingPlayWhenLoaded = true;
                Toast.makeText(this, "Conectando servidor...", Toast.LENGTH_SHORT).show();
            } else {
                retryMoviePlaybackOnDemand();
            }
        });

        btnMylist.setOnClickListener(v -> toggleMyList());

        btnBack.setOnClickListener(v -> onBackPressed());

        View btnRate = findViewById(R.id.btn_rate);
        if (btnRate != null) {
            btnRate.setOnClickListener(v ->
                    Toast.makeText(this, "Calificación registrada ★★★★★", Toast.LENGTH_SHORT).show());
        }

        if (seasonSelectorChip != null) {
            seasonSelectorChip.setOnClickListener(v -> showSeasonPicker());
        }
    }

    private void setupTvFocusStates() {
        applyTvFocus(btnDetailPlay);
        applyTvFocus(btnMylist);
        applyTvFocus(btnBack);
        applyTvFocus(seasonSelectorChip);
        applyTvFocus(findViewById(R.id.btn_rate));
    }

    private void applyTvFocus(View view) {
        if (view == null) return;
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            v.animate()
                    .scaleX(hasFocus ? 1.05f : 1f)
                    .scaleY(hasFocus ? 1.05f : 1f)
                    .setDuration(120L)
                    .start();
            v.setAlpha(hasFocus ? 1f : 0.92f);
        });
    }

    private void retryMoviePlaybackOnDemand() {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, "Conectando servidores de reproducción...", Toast.LENGTH_SHORT).show();
        isDetailLoading = true;
        updatePlayButtonState();

        new Thread(() -> {
            List<String> urls = new ArrayList<>();
            try {
                urls = smartScraperEngine.resolveMovieUrls(DetalleActivity.this, mediaItem);
            } catch (Exception ignored) { }

            if ((urls == null || urls.isEmpty()) && mediaItem.getPostId() > 0) {
                try {
                    AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(mediaItem.getPostId());
                    urls = AllCalidadScraper.getPlayableUrls(player);
                } catch (Exception ignored) { }
            }

            if (urls == null || urls.isEmpty()) {
                try {
                    AllCalidadScraper.ContentItem found = AllCalidadScraper.findByTitleAndYear(
                            mediaItem.getTitulo(), mediaItem.getAnio(), "movies");
                    if (found != null && found.id > 0) {
                        mediaItem.setPostId(found.id);
                        AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(found.id);
                        urls = AllCalidadScraper.getPlayableUrls(player);
                    }
                } catch (Exception ignored) { }
            }

            if (urls == null || urls.isEmpty()) {
                try {
                    sv.edu.catolica.rex.network.smart.Cuevana3Scraper c3 = new sv.edu.catolica.rex.network.smart.Cuevana3Scraper();
                    sv.edu.catolica.rex.network.smart.SmartPlaybackResult c3Res = c3.resolveMovie(mediaItem);
                    if (c3Res != null && c3Res.hasPlayableUrls()) {
                        urls = c3Res.toPlayableUrls();
                    }
                } catch (Exception ignored) { }
            }

            final List<String> finalUrls = urls != null ? urls : new ArrayList<>();
            runOnUiThread(() -> {
                isDetailLoading = false;
                updatePlayButtonState();
                if (isFinishing() || isDestroyed()) return;

                if (!finalUrls.isEmpty()) {
                    playUrls.clear();
                    playUrls.addAll(finalUrls);
                    String playTitle = mediaItem.getTitulo();
                    captureEpisodeNavigationState();
                    Intent intent = PlayerContenidoActivity.createIntent(DetalleActivity.this, playUrls, playTitle);
                    addResumeExtras(intent);
                    launchPlayer(intent);
                } else {
                    Toast.makeText(DetalleActivity.this, "No se pudo obtener la URL de reproducción", Toast.LENGTH_SHORT).show();
                }
            });
        }, "on-demand-play-resolver").start();
    }

    private void playFirstSeriesEpisode() {
        if (firstSeriesEpisode == null || firstSeriesEpisode.id <= 0) {
            Toast.makeText(this, "No hay episodios disponibles", Toast.LENGTH_SHORT).show();
            return;
        }
        loadEpisodeServers(firstSeriesEpisode);
    }

    private void requestPlayFocusIfTv() {
        if (!isTV || btnDetailPlay == null) {
            return;
        }
        View current = getCurrentFocus();
        if (current != null && current != btnDetailPlay && current != getWindow().getDecorView()) {
            return;
        }
        btnDetailPlay.post(() -> {
            View postCurrent = getCurrentFocus();
            if (postCurrent != null && postCurrent != btnDetailPlay && postCurrent != getWindow().getDecorView()) {
                return;
            }
            if (btnDetailPlay != null && btnDetailPlay.isShown() && btnDetailPlay.isEnabled()) {
                btnDetailPlay.requestFocus();
                btnDetailPlay.setSelected(true);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (isTV) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                View focused = getCurrentFocus();
                if (focused != null && focused.isClickable()) {
                    focused.performClick();
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showSeasonPicker() {
        if (seasonItems.isEmpty()) {
            return;
        }
        String[] options = new String[seasonItems.size()];
        for (int i = 0; i < seasonItems.size(); i++) {
            int sn = seasonItems.get(i).seasonNumber > 0 ? seasonItems.get(i).seasonNumber : i + 1;
            options[i] = "Temporada " + sn;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Elegir temporada")
                .setItems(options, (dialog, which) -> {
                    int sn = seasonItems.get(which).seasonNumber > 0
                            ? seasonItems.get(which).seasonNumber : which + 1;
                    episodeAdapter.showSeason(sn);
                    if (tvSeasonSelected != null) {
                        tvSeasonSelected.setText("Temporada " + sn);
                    }
                    if (tvEpisodeCount != null) {
                        tvEpisodeCount.setText(episodeAdapter.getEpisodeCountInSelectedSeason() + " episodios");
                    }
                    rvEpisodes.scrollToPosition(0);
                    dialog.dismiss();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void loadRelated(int postId) {
        if (postId <= 0 || rvMoreLikeThis == null) {
            hideMoreLike();
            return;
        }
        new Thread(() -> {
            try {
                List<AllCalidadScraper.ContentItem> related = AllCalidadScraper.getRelated(postId);
                List<MediaItem> items = new ArrayList<>();
                if (related != null) {
                    for (AllCalidadScraper.ContentItem c : related) {
                        if (c != null && c.id > 0) {
                            items.add(AllCalidadScraper.toMediaItem(c));
                        }
                    }
                }
                runOnUiThread(() -> {
                    if (items.isEmpty()) {
                        hideMoreLike();
                    } else {
                        posterAdapter.submitItems(items);
                        if (tvMoreLikeTitle != null) tvMoreLikeTitle.setVisibility(View.VISIBLE);
                        rvMoreLikeThis.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(this::hideMoreLike);
            }
        }).start();
    }

    private void hideMoreLike() {
        if (tvMoreLikeTitle != null) tvMoreLikeTitle.setVisibility(View.GONE);
        if (rvMoreLikeThis != null) rvMoreLikeThis.setVisibility(View.GONE);
    }

    private void openRelated(MediaItem item) {
        start(DetalleActivity.this, item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshResumeState();
        requestPlayFocusIfTv();
    }

    private void refreshResumeState() {
        if (mediaItem == null) return;
        resumeItem = ContinueWatchingStore.getForMedia(this, mediaItem.getPostId(),
                mediaItem.getTitulo(), mediaItem.getDetailUrl(), mediaItem.getMediaType());
        boolean isCompleted = ContinueWatchingStore.isCompleted(resumeItem);
        if (resumeItem != null && !isCompleted && resumeItem.getPositionMs() > 0) {
            resumePositionMs = resumeItem.getPositionMs();
            resumeContentId = resumeItem.getContentId();
        } else {
            resumePositionMs = 0;
            resumeContentId = "";
        }
        updatePlayButtonState();
    }

    private AllCalidadScraper.Episode findResumeEpisode() {
        if (resumeItem == null || resumeItem.getEpisodeId() <= 0) {
            return null;
        }
        for (AllCalidadScraper.Season s : seasonItems) {
            if (s != null && s.episodes != null) {
                for (AllCalidadScraper.Episode ep : s.episodes) {
                    if (ep != null && ep.id == resumeItem.getEpisodeId()) {
                        return ep;
                    }
                }
            }
        }
        AllCalidadScraper.Episode fallback = new AllCalidadScraper.Episode();
        fallback.id = resumeItem.getEpisodeId();
        fallback.seasonNumber = resumeItem.getSeasonNumber() > 0 ? resumeItem.getSeasonNumber() : 1;
        fallback.episodeNumber = resumeItem.getEpisodeNumber() > 0 ? resumeItem.getEpisodeNumber() : 1;
        fallback.title = resumeItem.getTitle();
        return fallback;
    }

    private AllCalidadScraper.Episode findEpisodeById(int episodeId) {
        if (episodeId <= 0) return null;
        for (AllCalidadScraper.Season s : seasonItems) {
            if (s == null || s.episodes == null) continue;
            for (AllCalidadScraper.Episode ep : s.episodes) {
                if (ep != null && ep.id == episodeId) return ep;
            }
        }
        return null;
    }

    @Override
    protected void onPause() {
        captureEpisodeNavigationState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        PlaybackCacheHelper.cancelPreload();
        if (userDataListener != null) {
            UserDataManager.getInstance(this).removeDataChangeListener(userDataListener);
        }
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
                RecyclerView.ViewHolder target = rvEpisodes.findViewHolderForAdapterPosition(targetPos);
                if (target != null) {
                    target.itemView.requestFocus();
                }
            });
        });
    }

    private void updateFavoriteButtonState() {
        UserDataManager udm = UserDataManager.getInstance(this);
        isFavorite = udm.isFavorite(mediaItem);
        updateFavoriteUi();
    }

    private void registerUserDataListener() {
        UserDataManager udm = UserDataManager.getInstance(this);
        userDataListener = new UserDataManager.DataChangeListener() {
            @Override
            public void onFavoritesChanged(List<FavoriteItem> favorites) {
                runOnUiThread(DetalleActivity.this::updateFavoriteButtonState);
            }

            @Override
            public void onHistoryChanged(List<WatchedHistoryItem> history) {
            }

            @Override
            public void onSettingsChanged(UserSettings settings) {
            }
        };
        udm.addDataChangeListener(userDataListener);
    }

    private void updateFavoriteUi() {
        if (tvMylistLabel != null) {
            tvMylistLabel.setText(isFavorite ? "En Mi Lista" : "Mi Lista");
            tvMylistLabel.setTextColor(isFavorite
                    ? Color.parseColor("#4ADE80")
                    : getResources().getColor(R.color.player_icon_secondary, getTheme()));
        }
        if (imgMylistIcon != null) {
            imgMylistIcon.setImageResource(isFavorite ? R.drawable.ic_check : R.drawable.ic_add);
            imgMylistIcon.setImageTintList(isFavorite
                    ? android.content.res.ColorStateList.valueOf(Color.parseColor("#4ADE80"))
                    : android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.player_text_soft, getTheme())));
        }
    }

    private String buildFavoriteId() {
        if (mediaItem.getPostId() > 0) {
            return (isSeries ? "tv_" : "post_") + mediaItem.getPostId();
        }
        if (mediaItem.getDetailUrl() != null && !mediaItem.getDetailUrl().isEmpty()) {
            return (isSeries ? "tv_url_" : "movie_url_") + Math.abs(mediaItem.getDetailUrl().hashCode());
        }
        return (isSeries ? "tv_" : "post_") + Math.abs((mediaItem.getTitulo() != null ? mediaItem.getTitulo() : "").hashCode());
    }

    private void toggleMyList() {
        UserDataManager udm = UserDataManager.getInstance(this);
        if (isFavorite) {
            udm.removeFromFavorites(mediaItem);
            isFavorite = false;
            Toast.makeText(this, "Eliminado de Mi Lista", Toast.LENGTH_SHORT).show();
        } else {
            udm.addToFavorites(mediaItem);
            isFavorite = true;
            Toast.makeText(this, "Añadido a Mi Lista", Toast.LENGTH_SHORT).show();
        }
        updateFavoriteUi();
    }

    private boolean isSeriesType(String mediaType) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        return "tvshows".equals(type) || "animes".equals(type) || "series".equals(type) || "tv".equals(type) || "novelas".equals(type);
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

    public static void start(Context context, MediaItem item) {
        Intent intent = new Intent(context, DetalleActivity.class);
        intent.putExtra(EXTRA_MEDIA_ITEM, item);
        context.startActivity(intent);
    }
}
