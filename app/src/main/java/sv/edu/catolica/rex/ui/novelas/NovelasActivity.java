package sv.edu.catolica.rex.ui.novelas;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.network.smart.CanelaScraper;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;
import sv.edu.catolica.rex.repository.UserDataManager;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;
import sv.edu.catolica.rex.ui.detalle.DetalleActivity;
import sv.edu.catolica.rex.ui.home.HeroPagerAdapter;
import sv.edu.catolica.rex.ui.home.HomeActivity;
import sv.edu.catolica.rex.ui.home.HomeAdapter;
import sv.edu.catolica.rex.ui.home.SearchActivity;
import sv.edu.catolica.rex.ui.home.TvChannelsActivity;
import sv.edu.catolica.rex.ui.player.PlayerContenidoActivity;

public class NovelasActivity extends AppCompatActivity {

    private RecyclerView rvSections;
    private HomeAdapter adapter;
    private ProgressBar progressBar;
    private View heroContainer;
    private ViewPager2 heroPager;
    private LinearLayout heroDots;
    private View topHeader;

    private boolean isTv = false;
    private final Handler heroHandler = new Handler(Looper.getMainLooper());
    private Runnable heroAutoScroll;
    private ViewPager2.OnPageChangeCallback heroPageCallback;
    private final List<MediaItem> currentHeroItems = new ArrayList<>();
    private final Map<String, ContinueWatchingItem> continueWatchingByContentId = new HashMap<>();
    private final List<Section> catalogSections = new ArrayList<>();

    private float heroScrollOffset = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = isTelevision();
        enterImmersiveMode();

        setContentView(R.layout.activity_novelas);

        rvSections = findViewById(R.id.rv_novelas_sections);
        progressBar = findViewById(R.id.progressBar);
        heroContainer = findViewById(R.id.hero_container);
        heroPager = findViewById(R.id.vp_hero);
        heroDots = findViewById(R.id.dots_container);
        topHeader = findViewById(R.id.top_header);

        setupHeaderAndNavigation();
        setupRecyclerView();

        loadNovelasData();
    }

    private boolean isTelevision() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        return getPackageManager().hasSystemFeature("android.hardware.type.television")
                || getPackageManager().hasSystemFeature("android.software.leanback");
    }

    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars());
            insetsController.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void setupHeaderAndNavigation() {
        // Botón volver
        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // Botón buscar
        ImageButton btnSearch = findViewById(R.id.btn_search);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> startActivity(new Intent(NovelasActivity.this, SearchActivity.class)));
        }

        if (isTv) {
            setupTvSidebar();
        } else {
            setupMobileBottomNav();
        }
    }

    private void setupMobileBottomNav() {
        View menuHome = findViewById(R.id.menu_home);
        View menuBuscar = findViewById(R.id.menu_buscar);
        View menuCanales = findViewById(R.id.menu_canales);
        View menuNovelas = findViewById(R.id.menu_novelas);

        if (menuHome != null) {
            menuHome.setOnClickListener(v -> {
                Intent intent = new Intent(NovelasActivity.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            });
        }

        if (menuBuscar != null) {
            menuBuscar.setOnClickListener(v -> startActivity(new Intent(NovelasActivity.this, SearchActivity.class)));
        }

        if (menuCanales != null) {
            menuCanales.setOnClickListener(v -> {
                startActivity(new Intent(NovelasActivity.this, TvChannelsActivity.class));
                finish();
            });
        }

        if (menuNovelas != null) {
            menuNovelas.setOnClickListener(v -> {
                if (rvSections != null) {
                    rvSections.smoothScrollToPosition(0);
                }
            });
        }

        View menuDoramas = findViewById(R.id.menu_doramas);
        if (menuDoramas != null) {
            menuDoramas.setOnClickListener(v -> {
                startActivity(new Intent(NovelasActivity.this, sv.edu.catolica.rex.ui.doramas.DoramasActivity.class));
                finish();
            });
        }
    }

    private void setupTvSidebar() {
        View menuHome = findViewById(R.id.menu_home);
        View menuBuscar = findViewById(R.id.menu_buscar);
        View menuCanales = findViewById(R.id.menu_canales);
        View menuNovelas = findViewById(R.id.menu_novelas);
        View menuDoramas = findViewById(R.id.menu_doramas);
        View menuAjustes = findViewById(R.id.menu_ajustes);

        if (menuHome != null) {
            menuHome.setOnClickListener(v -> {
                Intent intent = new Intent(NovelasActivity.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            });
        }

        if (menuBuscar != null) {
            menuBuscar.setOnClickListener(v -> startActivity(new Intent(NovelasActivity.this, SearchActivity.class)));
        }

        if (menuCanales != null) {
            menuCanales.setOnClickListener(v -> {
                startActivity(new Intent(NovelasActivity.this, TvChannelsActivity.class));
                finish();
            });
        }

        if (menuNovelas != null) {
            menuNovelas.setOnClickListener(v -> {
                scrollTvToSection(0);
                if (adapter != null && adapter.getItemCount() > 0) {
                    adapter.requestFocusAt(0, 0);
                }
            });
        }

        if (menuDoramas != null) {
            menuDoramas.setOnClickListener(v -> {
                startActivity(new Intent(NovelasActivity.this, sv.edu.catolica.rex.ui.doramas.DoramasActivity.class));
                finish();
            });
        }

        View.OnKeyListener menuRightListener = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (adapter != null && adapter.getItemCount() > 0) {
                    int sec = adapter.getLastFocusedSection();
                    int it = adapter.getLastFocusedItem();
                    adapter.requestFocusAt(Math.max(0, sec), Math.max(0, it));
                    return true;
                }
            }
            return false;
        };
        if (menuHome != null) menuHome.setOnKeyListener(menuRightListener);
        if (menuBuscar != null) menuBuscar.setOnKeyListener(menuRightListener);
        if (menuCanales != null) menuCanales.setOnKeyListener(menuRightListener);
        if (menuNovelas != null) menuNovelas.setOnKeyListener(menuRightListener);
        if (menuDoramas != null) menuDoramas.setOnKeyListener(menuRightListener);
        if (menuAjustes != null) menuAjustes.setOnKeyListener(menuRightListener);
    }

    private int currentTvScrolledSection = -1;

    public void scrollTvToSection(int sectionPosition) {
        if (rvSections == null) return;
        if (currentTvScrolledSection == sectionPosition) return;
        currentTvScrolledSection = sectionPosition;
        rvSections.post(() -> {
            RecyclerView.LayoutManager lm = rvSections.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(Math.max(0, sectionPosition), 0);
            } else {
                rvSections.scrollToPosition(Math.max(0, sectionPosition));
            }
        });
    }

    private void setupRecyclerView() {
        if (rvSections == null) return;
        rvSections.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        adapter = new HomeAdapter(this, new ArrayList<>(), this::openDetail, isTv);
        rvSections.setAdapter(adapter);

        // Parallax scroll para desplazar el carrusel en móvil
        if (!isTv && heroContainer != null) {
            final float maxOffset = 420f * getResources().getDisplayMetrics().density;
            rvSections.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    heroScrollOffset = Math.max(0f, Math.min(maxOffset, heroScrollOffset + dy));
                    heroContainer.setTranslationY(-heroScrollOffset);
                    if (topHeader != null) {
                        float alpha = Math.min(1f, heroScrollOffset / (maxOffset * 0.5f));
                        topHeader.setAlpha(0.6f + (0.4f * alpha));
                    }
                }
            });
        }
    }

    private void loadNovelasData() {
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }

        new Thread(() -> {
            CanelaScraper scraper = new CanelaScraper();
            CanelaScraper.NovelasCatalog catalog = scraper.fetchNovelasCatalog();

            // Preparar Continuar Viendo de Novelas si existe
            List<Section> finalSections = new ArrayList<>();
            Section continueWatchingSection = buildContinueWatchingNovelasSection();
            if (continueWatchingSection != null && !continueWatchingSection.getItems().isEmpty()) {
                finalSections.add(continueWatchingSection);
            }

            if (catalog != null && catalog.sections != null) {
                synchronized (catalogSections) {
                    catalogSections.clear();
                    catalogSections.addAll(catalog.sections);
                }
                finalSections.addAll(catalog.sections);
            }

            List<MediaItem> heroItems = catalog != null ? catalog.heroItems : new ArrayList<>();

            runOnUiThread(() -> {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }

                if (heroItems != null && !heroItems.isEmpty()) {
                    setupHeroCarousel(heroItems);
                } else if (!finalSections.isEmpty()) {
                    // Fallback para hero carousel: extraer de las primeras secciones
                    List<MediaItem> fallbackHero = new ArrayList<>();
                    for (Section s : finalSections) {
                        if (s.getItems() != null) {
                            for (MediaItem item : s.getItems()) {
                                if (item.getBackdrop() != null && !item.getBackdrop().isEmpty()) {
                                    fallbackHero.add(item);
                                    if (fallbackHero.size() >= 5) break;
                                }
                            }
                        }
                        if (fallbackHero.size() >= 5) break;
                    }
                    if (!fallbackHero.isEmpty()) {
                        setupHeroCarousel(fallbackHero);
                    } else {
                        hideHeroCarousel();
                    }
                } else {
                    hideHeroCarousel();
                }

                if (!finalSections.isEmpty()) {
                    adapter.setSections(finalSections);
                } else {
                    Toast.makeText(NovelasActivity.this, "No se pudieron cargar las novelas", Toast.LENGTH_SHORT).show();
                }
            });
        }, "novelas-catalog-thread").start();
    }

    private Section buildContinueWatchingNovelasSection() {
        List<ContinueWatchingItem> all = ContinueWatchingStore.getAll(this);
        if (all == null || all.isEmpty()) return null;

        List<MediaItem> mediaItems = new ArrayList<>();
        for (ContinueWatchingItem cw : all) {
            if (ContinueWatchingStore.isCompleted(cw)) continue;

            boolean isNovela = (cw.getSource() != null && cw.getSource().equalsIgnoreCase("novelas"))
                    || (cw.getDetailUrl() != null && cw.getDetailUrl().contains("canela.tv"))
                    || (cw.getContentId() != null && cw.getContentId().contains("novelas"));

            if (!isNovela) continue;

            String cleanTitle = cw.getSeriesTitle() != null && !cw.getSeriesTitle().isEmpty()
                    ? cw.getSeriesTitle() : cw.getTitle();
            if (cleanTitle != null) {
                cleanTitle = cleanTitle.replaceAll("(?i)\\s*-\\s*(?:T|S)\\d+.*", "")
                        .replaceAll("(?i)\\s*(?:T|S)\\d+[:E\\s].*", "").trim();
            }

            MediaItem mi = new MediaItem(cleanTitle, "", cw.getImageUrl(), cw.getDetailUrl(), cw.getPostId());
            mi.setMediaType(cw.getMediaType() != null ? cw.getMediaType() : "tvshows");
            mi.setProgress(Math.min(99, cw.getProgressPercent()));
            mi.setPostId(cw.getPostId());
            mi.setFuente("continue_watching:" + cw.getContentId());
            if (mi.getImagen() == null || mi.getImagen().isEmpty()) {
                mi.setImagen(cw.getImageUrl());
            }

            continueWatchingByContentId.put(cw.getContentId(), cw);
            mediaItems.add(mi);
        }

        if (mediaItems.isEmpty()) return null;
        return new Section("Continuar Viendo Novelas", mediaItems);
    }

    private void setupHeroCarousel(List<MediaItem> candidates) {
        if (isTv || heroContainer == null || heroPager == null || candidates == null || candidates.isEmpty()) {
            hideHeroCarousel();
            return;
        }

        currentHeroItems.clear();
        currentHeroItems.addAll(candidates);

        heroContainer.setVisibility(View.VISIBLE);
        heroScrollOffset = 0f;
        heroContainer.setTranslationY(0f);
        updateTopPadding(true);

        heroPager.setAdapter(new HeroPagerAdapter(currentHeroItems, new HeroPagerAdapter.Listener() {
            @Override
            public void onPlay(MediaItem item) {
                openDetail(item);
            }

            @Override
            public void onInfo(MediaItem item) {
                openDetail(item);
            }

            @Override
            public void onToggleMyList(MediaItem item, int position) {
                if (item == null) return;
                UserDataManager udm = UserDataManager.getInstance(NovelasActivity.this);
                boolean wasFav = udm.isFavorite(item);
                if (wasFav) {
                    udm.removeFromFavorites(item);
                    Toast.makeText(NovelasActivity.this, "Eliminado de Mi Lista", Toast.LENGTH_SHORT).show();
                } else {
                    udm.addToFavorites(item);
                    Toast.makeText(NovelasActivity.this, "Añadido a Mi Lista", Toast.LENGTH_SHORT).show();
                }
            }
        }));

        heroPager.setOffscreenPageLimit(1);
        heroPager.setPageTransformer((page, position) ->
                page.setScaleY(1f - Math.min(0.04f, Math.abs(position) * 0.04f)));

        if (heroDots != null) {
            heroDots.removeAllViews();
            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < currentHeroItems.size(); i++) {
                ImageView dot = new ImageView(this);
                dot.setImageResource(i == 0 ? R.drawable.dot_active : R.drawable.dot_inactive);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        (int) (7f * density), (int) (7f * density));
                params.setMargins((int) (3f * density), 0, (int) (3f * density), 0);
                heroDots.addView(dot, params);
            }
        }

        if (heroPageCallback != null) {
            heroPager.unregisterOnPageChangeCallback(heroPageCallback);
        }

        heroPageCallback = new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (heroDots == null) return;
                for (int i = 0; i < heroDots.getChildCount(); i++) {
                    ((ImageView) heroDots.getChildAt(i)).setImageResource(
                            i == position ? R.drawable.dot_active : R.drawable.dot_inactive);
                }
            }
        };
        heroPager.registerOnPageChangeCallback(heroPageCallback);
        startHeroAutoScroll(currentHeroItems.size());
    }

    private void hideHeroCarousel() {
        if (heroContainer != null) {
            heroContainer.setVisibility(View.GONE);
        }
        stopHeroAutoScroll();
        updateTopPadding(false);
    }

    private void updateTopPadding(boolean heroVisible) {
        if (rvSections == null || isTv) return;
        float density = getResources().getDisplayMetrics().density;
        int top = heroVisible ? (int) (420f * density) : (int) (64f * density);
        rvSections.setPadding(0, top, 0, (int) (100f * density));
    }

    private void startHeroAutoScroll(int itemCount) {
        stopHeroAutoScroll();
        if (itemCount <= 1) return;

        heroAutoScroll = new Runnable() {
            @Override
            public void run() {
                if (heroPager == null || heroPager.getAdapter() == null) return;
                int next = (heroPager.getCurrentItem() + 1) % itemCount;
                heroPager.setCurrentItem(next, true);
                heroHandler.postDelayed(this, 5000L);
            }
        };
        heroHandler.postDelayed(heroAutoScroll, 5000L);
    }

    private void stopHeroAutoScroll() {
        if (heroAutoScroll != null) {
            heroHandler.removeCallbacks(heroAutoScroll);
            heroAutoScroll = null;
        }
    }

    private void openDetail(MediaItem item) {
        if (item == null) return;

        // Si es un ítem de continuar viendo, reanudar en player
        if (item.getFuente() != null && item.getFuente().startsWith("continue_watching:")) {
            String contentId = item.getFuente().substring("continue_watching:".length());
            ContinueWatchingItem continueItem = continueWatchingByContentId.get(contentId);
            if (continueItem != null) {
                openContinueWatchingNovela(continueItem, item);
                return;
            }
        }

        DetalleActivity.start(NovelasActivity.this, item);
    }

    private void openContinueWatchingNovela(ContinueWatchingItem continueItem, MediaItem item) {
        String title = continueItem.getTitle() != null && !continueItem.getTitle().isEmpty()
                ? continueItem.getTitle() : item.getTitulo();
        String seriesTitle = continueItem.getSeriesTitle() != null && !continueItem.getSeriesTitle().isEmpty()
                ? continueItem.getSeriesTitle() : item.getTitulo();

        new Thread(() -> {
            List<String> resolvedUrls = new ArrayList<>();
            try {
                CanelaScraper scraper = new CanelaScraper();
                MediaItem playbackItem = new MediaItem(
                        seriesTitle, "", continueItem.getImageUrl() != null ? continueItem.getImageUrl() : "",
                        continueItem.getDetailUrl() != null ? continueItem.getDetailUrl() : "", continueItem.getPostId());
                playbackItem.setMediaType(continueItem.getMediaType() != null ? continueItem.getMediaType() : "tvshows");
                playbackItem.setFuente(continueItem.getSource() != null ? continueItem.getSource() : "novelas");

                boolean isSeries = ContinueWatchingStore.isSeries(continueItem);
                SmartPlaybackResult result = isSeries
                        ? scraper.resolveEpisode(
                                playbackItem,
                                continueItem.getSeasonNumber(),
                                continueItem.getEpisodeNumber(),
                                continueItem.getEpisodeId()
                        )
                        : scraper.resolveMovie(playbackItem);
                if (result != null && result.hasPlayableUrls()) {
                    resolvedUrls = result.toPlayableUrls();
                }
            } catch (Exception ignored) {}

            final ArrayList<String> safeUrls = new ArrayList<>(resolvedUrls);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Intent playerIntent;
                if (ContinueWatchingStore.isSeries(continueItem)) {
                    playerIntent = PlayerContenidoActivity.createEpisodeIntent(
                            NovelasActivity.this,
                            safeUrls,
                            title,
                            seriesTitle,
                            continueItem.getPostId(),
                            continueItem.getMediaType(),
                            continueItem.getEpisodeId(),
                            continueItem.getSeasonNumber(),
                            continueItem.getEpisodeNumber()
                    );
                } else {
                    playerIntent = PlayerContenidoActivity.createIntent(NovelasActivity.this, safeUrls, title);
                }
                playerIntent.putExtra("resume_position_ms", continueItem.getPositionMs());
                playerIntent.putExtra("exo_resume_position_ms", continueItem.getPositionMs());
                playerIntent.putExtra("exo_content_id", continueItem.getContentId());
                playerIntent.putExtra("exo_post_id", continueItem.getPostId());
                playerIntent.putExtra("exo_media_type", continueItem.getMediaType());
                playerIntent.putExtra("exo_series_title", seriesTitle);
                playerIntent.putExtra("series_post_id", continueItem.getPostId());
                playerIntent.putExtra("series_title", seriesTitle);
                playerIntent.putExtra("series_post_type", continueItem.getMediaType());
                if (continueItem.getSource() != null && !continueItem.getSource().isEmpty()) {
                    playerIntent.putExtra("series_source", continueItem.getSource());
                    playerIntent.putExtra("exo_series_source", continueItem.getSource());
                }
                if (continueItem.getDetailUrl() != null && !continueItem.getDetailUrl().isEmpty()) {
                    playerIntent.putExtra("series_detail_url", continueItem.getDetailUrl());
                    playerIntent.putExtra("exo_series_detail_url", continueItem.getDetailUrl());
                }
                if (continueItem.getImageUrl() != null && !continueItem.getImageUrl().isEmpty()) {
                    playerIntent.putExtra("image_url", continueItem.getImageUrl());
                    playerIntent.putExtra("exo_image_url", continueItem.getImageUrl());
                }
                startActivity(playerIntent);
                overridePendingTransition(android.R.anim.fade_in, 0);
            });
        }, "novelas-resume-resolver").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (!isTv && heroPager != null && heroPager.getAdapter() != null) {
            startHeroAutoScroll(heroPager.getAdapter().getItemCount());
        }
        refreshContinueWatchingSection();
    }

    private void refreshContinueWatchingSection() {
        if (adapter == null) return;
        List<Section> finalSections = new ArrayList<>();
        Section continueWatchingSection = buildContinueWatchingNovelasSection();
        if (continueWatchingSection != null && !continueWatchingSection.getItems().isEmpty()) {
            finalSections.add(continueWatchingSection);
        }
        synchronized (catalogSections) {
            finalSections.addAll(catalogSections);
        }
        if (!finalSections.isEmpty()) {
            adapter.setSections(finalSections);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopHeroAutoScroll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHeroAutoScroll();
        if (heroPager != null && heroPageCallback != null) {
            heroPager.unregisterOnPageChangeCallback(heroPageCallback);
        }
    }
}
