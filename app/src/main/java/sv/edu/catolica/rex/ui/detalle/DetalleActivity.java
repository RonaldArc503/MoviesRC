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
    private View btnDetailDownload;
    private View btnMylist;
    private ImageView imgMylistIcon;
    private TextView tvMylistLabel;
    private View btnRate;
    private View btnShare;
    private View btnBack;
    private View seasonSelectorChip;
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

        isSeries = isSeriesType(mediaItem.getMediaType());
        isTV = isTelevision();

        // Auto-resuelve la variante TV (layout-television/) si aplica.
        setContentView(isSeries ? R.layout.activity_series_detail : R.layout.activity_movie_detail);

        bindViews();
        setupRecyclerView();
        setupListeners();

        refreshResumeState();

        displayBasicInfo();
        updateFavoriteButtonState();
        registerUserDataListener();
        if (savedInstanceState != null) {
            selectedEpisodeId = savedInstanceState.getInt("selected_episode_id", -1);
            episodesFirstVisiblePos = savedInstanceState.getInt("episodes_scroll_pos", 0);
            episodesTopOffset = savedInstanceState.getInt("episodes_scroll_offset", 0);
        }
        loadDetail();
    }

    private boolean isTelevision() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        int uiMode = getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
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
        btnDetailDownload = findViewById(R.id.btn_detail_download);
        btnMylist = findViewById(R.id.btn_mylist);
        imgMylistIcon = findViewById(R.id.img_mylist_icon);
        tvMylistLabel = findViewById(R.id.tv_mylist_label);
        btnRate = findViewById(R.id.btn_rate);
        btnShare = findViewById(R.id.btn_share);
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

        String synopsis = mediaItem.getSynopsis();
        tvSynopsis.setText((synopsis != null && !synopsis.isEmpty()) ? synopsis : "Cargando sinopsis...");

        String toLoad = mediaItem.getBackdrop();
        if (toLoad == null || toLoad.trim().isEmpty()) {
            toLoad = mediaItem.getImagen();
        }
        if (toLoad != null && !toLoad.trim().isEmpty()) {
            Glide.with(this)
                    .load(toLoad)
                    .placeholder(R.drawable.placeholder_backdrop)
                    .error(R.drawable.placeholder_backdrop)
                    .centerCrop()
                    .into(ivBackdrop);
        }

        btnDetailPlay.setEnabled(false);
        tvDetailPlayText.setText("Cargando...");
    }

    private void loadDetail() {
        new Thread(() -> {
            try {
                TmdbService.enrichMediaItem(mediaItem);

                List<String> urls = new ArrayList<>();
                List<AllCalidadScraper.Season> seasons = new ArrayList<>();
                int postId = mediaItem.getPostId();
                String mediaType = mediaItem.getMediaType();
                boolean series = isSeriesType(mediaType);
                if (!series) {
                    urls = smartScraperEngine.resolveMovieUrls(DetalleActivity.this, mediaItem);
                } else if (postId <= 0) {
                    urls = smartScraperEngine.resolveEpisodeUrls(DetalleActivity.this, mediaItem, 1, 1);
                    seasons = new ArrayList<>();
                }

                if (postId <= 0 && (urls == null || urls.isEmpty())) {
                    AllCalidadScraper.ContentItem found = AllCalidadScraper.findByTitleAndYear(
                            mediaItem.getTitulo(), mediaItem.getAnio(), mediaType);
                    if (found != null && found.id > 0) {
                        postId = found.id;
                        mediaItem.setPostId(postId);
                    }
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
                final int finalPostId = postId;
                final String desc = (mediaItem.getSynopsis() != null && !mediaItem.getSynopsis().isEmpty())
                        ? mediaItem.getSynopsis()
                        : "Sin sinopsis disponible.";
                final String finalPoster = mediaItem.getImagen();
                final boolean finalIsSeries = series;

                runOnUiThread(() -> {
                    tvSynopsis.setText(desc);
                    episodeAdapter.setSeriesPoster(finalPoster);
                    bindSeasons(finalSeasons, finalIsSeries);

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
                    loadRelated(finalPostId);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvSynopsis.setText("Error al cargar la sinopsis.");
                    if (!isSeries) {
                        btnDetailPlay.setEnabled(false);
                        tvDetailPlayText.setText("No disponible");
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
        if (episode == null || episode.id <= 0) {
            return;
        }

        new Thread(() -> {
            try {
                List<String> urls = new ArrayList<>();

                // 1) Ruta directa y fiable: player del episodio por su id real.
                try {
                    if (mediaItem.getPostId() > 0) {
                        AllCalidadScraper.hit(episode.id, "episodes");
                    }
                    AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(episode.id);
                    urls = AllCalidadScraper.getPlayableUrls(player);
                } catch (Exception e) {
                    // sigue al smart engine
                }

                // 2) Fallback: smart engine (caché / multi-fuente), con try/catch
                //    para que un fallo del enriquecimiento TMDB no rompa el episodio.
                if (urls == null || urls.isEmpty()) {
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
        if (resumeContentId != null && !resumeContentId.isEmpty()) {
            intent.putExtra("exo_content_id", resumeContentId);
            intent.putExtra("exo_post_id", mediaItem.getPostId());
            intent.putExtra("exo_media_type", mediaItem.getMediaType());
            intent.putExtra("exo_series_title", mediaItem.getTitulo());
        }
        intent.putExtra("series_post_id", mediaItem.getPostId());
        intent.putExtra("series_title", mediaItem.getTitulo());
        intent.putExtra("series_post_type", mediaItem.getMediaType());
        String img = mediaItem.getImagen();
        if (img != null && !img.isEmpty()) {
            intent.putExtra("image_url", img);
            intent.putExtra("exo_image_url", img);
        }
    }

    private void updatePlayButtonState() {
        btnDetailPlay.setVisibility(View.VISIBLE);

        if (isSeries) {
            AllCalidadScraper.Episode resumeEp = findResumeEpisode();
            if (resumeEp != null && resumePositionMs > 0 && resumeItem != null) {
                btnDetailPlay.setEnabled(true);
                int pct = resumeItem.getProgressPercent();
                String epLabel = formatEpisodeLabel(resumeEp);
                tvDetailPlayText.setText("Continuar " + epLabel + " (" + pct + "%)");
                if (episodeAdapter != null) {
                    episodeAdapter.setResumeProgress(resumeEp.id, pct);
                }
                return;
            }

            if (firstSeriesEpisode == null) {
                if (playUrls.isEmpty()) {
                    btnDetailPlay.setEnabled(false);
                    tvDetailPlayText.setText("No disponible");
                    return;
                }
                btnDetailPlay.setEnabled(true);
                tvDetailPlayText.setText(resumePositionMs > 0 ? "Continuar T1:E1" : "Reproducir T1:E1");
                return;
            }

            btnDetailPlay.setEnabled(true);
            tvDetailPlayText.setText("Reproducir " + formatEpisodeLabel(firstSeriesEpisode));
            if (episodeAdapter != null) {
                episodeAdapter.setResumeProgress(-1, 0);
            }
            return;
        }

        if (playUrls.isEmpty()) {
            btnDetailPlay.setEnabled(false);
            tvDetailPlayText.setText("No disponible");
            return;
        }

        btnDetailPlay.setEnabled(true);
        if (resumePositionMs > 0 && resumeItem != null) {
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
                if (resumeEp != null && resumePositionMs > 0) {
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
                Toast.makeText(this, "No hay episodios disponibles", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!playUrls.isEmpty()) {
                String playTitle = mediaItem.getTitulo();
                captureEpisodeNavigationState();
                Intent intent = PlayerContenidoActivity.createIntent(this, playUrls, playTitle);
                addResumeExtras(intent);
                launchPlayer(intent);
            } else {
                Toast.makeText(this, "No se pudo obtener la URL de reproducción", Toast.LENGTH_SHORT).show();
            }
        });

        btnMylist.setOnClickListener(v -> toggleMyList());

        btnRate.setOnClickListener(v ->
                Toast.makeText(this, "Calificar — Próximamente", Toast.LENGTH_SHORT).show());

        btnShare.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, mediaItem.getTitulo());
            share.putExtra(Intent.EXTRA_TEXT, mediaItem.getTitulo()
                    + (mediaItem.getAnio() != null ? " (" + mediaItem.getAnio() + ")" : ""));
            startActivity(Intent.createChooser(share, "Compartir"));
        });

        btnDetailDownload.setOnClickListener(v ->
                Toast.makeText(this, "Descargas — Próximamente", Toast.LENGTH_SHORT).show());

        btnBack.setOnClickListener(v -> onBackPressed());

        if (seasonSelectorChip != null) {
            seasonSelectorChip.setOnClickListener(v -> showSeasonPicker());
        }
    }

    private void setupTvFocusStates() {
        applyTvFocus(btnDetailPlay);
        applyTvFocus(btnMylist);
        applyTvFocus(btnBack);
        applyTvFocus(seasonSelectorChip);
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

    private void playFirstSeriesEpisode() {
        if (firstSeriesEpisode == null || firstSeriesEpisode.id <= 0) {
            Toast.makeText(this, "No hay episodios disponibles", Toast.LENGTH_SHORT).show();
            return;
        }
        loadEpisodeServers(firstSeriesEpisode);
    }

    private void requestPlayFocusIfTv() {
        if (!isTV || btnDetailPlay == null || !btnDetailPlay.isShown()) {
            return;
        }
        btnDetailPlay.post(() -> {
            if (btnDetailPlay.isShown() && btnDetailPlay.isEnabled()) {
                btnDetailPlay.requestFocus();
                btnDetailPlay.setSelected(true);
            }
        });
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
    }

    private void refreshResumeState() {
        if (mediaItem == null) return;
        resumeItem = ContinueWatchingStore.getByPostId(this, mediaItem.getPostId());
        if (resumeItem != null && !resumeItem.isCompleted() && resumeItem.getPositionMs() > 0) {
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
        String favId = buildFavoriteId();
        UserDataManager udm = UserDataManager.getInstance(this);
        isFavorite = udm.isFavorite(favId);
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
        }
        if (imgMylistIcon != null) {
            imgMylistIcon.setImageTintList(isFavorite
                    ? android.content.res.ColorStateList.valueOf(Color.parseColor("#4ADE80"))
                    : android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.player_text_soft, getTheme())));
        }
    }

    private String buildFavoriteId() {
        if (isSeries) {
            return "tv_" + mediaItem.getPostId();
        }
        return "post_" + mediaItem.getPostId();
    }

    private void toggleMyList() {
        String favId = buildFavoriteId();
        UserDataManager udm = UserDataManager.getInstance(this);
        if (isFavorite) {
            udm.removeFromFavorites(favId);
            isFavorite = false;
        } else {
            udm.addToFavorites(favId, mediaItem.getTitulo(), mediaItem.getImagen(), mediaItem.getMediaType());
            isFavorite = true;
        }
        updateFavoriteUi();
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

    public static void start(Context context, MediaItem item) {
        Intent intent = new Intent(context, DetalleActivity.class);
        intent.putExtra(EXTRA_MEDIA_ITEM, item);
        context.startActivity(intent);
    }
}
