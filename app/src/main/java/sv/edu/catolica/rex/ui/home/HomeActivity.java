package sv.edu.catolica.rex.ui.home;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import sv.edu.catolica.rex.BuildConfig;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;
import sv.edu.catolica.rex.network.smart.PelispediaScraper;
import sv.edu.catolica.rex.models.FavoriteItem;
import sv.edu.catolica.rex.models.WatchedHistoryItem;
import sv.edu.catolica.rex.repository.UserDataManager;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;
import sv.edu.catolica.rex.ui.auth.AuthManager;
import sv.edu.catolica.rex.ui.auth.LoginActivity;
import sv.edu.catolica.rex.ui.football.FootballActivity;
import sv.edu.catolica.rex.ui.detalle.DetalleActivity;

public class HomeActivity extends AppCompatActivity {

    private static final long HOME_CACHE_TTL_MS = 7 * 60 * 1000L;
    private static List<Section> homeSectionsCache;
    private static long homeCacheSavedAtMs;

    /** Pausa entre peticiones al mismo servidor (ms).
     *  Evita patrones de bot sin sacrificar velocidad real. */
    private static final long REQUEST_STAGGER_MS = 120;

    private RecyclerView rvSections;
    private ProgressBar  progressBar;
    private SearchView   searchView;
    private HomeAdapter  adapter;
    private boolean      isTv;
    private List<Section> homeSectionsSnapshot = new ArrayList<>();

    private View menuHome;
    private View menuBuscar;
    private View menuCanales;
    private View menuFootball;
    private View menuCategorias;
    private View menuSeries;
    private View menuDoramas;
    private View menuAjustes;

    private boolean isSearchMode          = false;
    private boolean suppressQueryListener = false;
    private String  lastSearchQuery       = "";
    private int     activeRequestId       = 0;
    private boolean tmdbKeyWarningShown   = false;
    private int lastFocusedSectionPos = 0;
    private int lastFocusedItemPos = 0;
    private int lastHomeFirstVisiblePos = 0;
    private int lastHomeTopOffset = 0;
    private boolean pendingTvFocusRestore = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        isTv = isTvDevice();

        rvSections  = findViewById(R.id.rv_home_sections);
        progressBar = findViewById(R.id.progressBar);

        rvSections.setLayoutManager(new LinearLayoutManager(this));
        rvSections.setHasFixedSize(true);
        rvSections.setItemAnimator(null);
        rvSections.setItemViewCacheSize(isTv ? 8 : 4);
        adapter = new HomeAdapter(this, new ArrayList<>(),
                this::openDetail, isTv);
        adapter.setOnFocusPositionChangedListener((sectionPosition, itemPosition) -> {
            lastFocusedSectionPos = Math.max(0, sectionPosition);
            lastFocusedItemPos = Math.max(0, itemPosition);
        });
        rvSections.setAdapter(adapter);

        if (savedInstanceState != null) {
            lastFocusedSectionPos = savedInstanceState.getInt("home_focus_section", 0);
            lastFocusedItemPos = savedInstanceState.getInt("home_focus_item", 0);
            lastHomeFirstVisiblePos = savedInstanceState.getInt("home_scroll_pos", 0);
            lastHomeTopOffset = savedInstanceState.getInt("home_scroll_offset", 0);
            pendingTvFocusRestore = isTv;
        }

        if (isTv) {
            searchView = findViewById(R.id.search_view);
            if (searchView != null) {
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override public boolean onQueryTextSubmit(String query) {
                        performSearch(query); return true;
                    }
                    @Override public boolean onQueryTextChange(String newText) {
                        if (suppressQueryListener) return false;
                        String text = newText == null ? "" : newText.trim();
                        if (text.isEmpty() && isSearchMode) { exitSearchModeAndRestoreHome(); return true; }
                        return false;
                    }
                });

                searchView.setOnCloseListener(() -> {
                    if (isSearchMode) { exitSearchModeAndRestoreHome(); return true; }
                    return false;
                });
            }
            setupTvSearchBehavior();
            setupTvSideMenu();
        } else {
            setupPhoneBottomMenu();
        }

        loadHomeData(false);

        if (AuthManager.getInstance().isSignedIn()) {
            UserDataManager.getInstance(this).restoreFromFirestore(new UserDataManager.RestoreCallback() {
                @Override
                public void onRestoreComplete(boolean fromFirestore) {
                    runOnUiThread(() -> refreshUserDataSections());
                }

                @Override
                public void onError(Exception e) {
                    Log.w("HomeActivity", "restoreFromFirestore failed", e);
                }
            });
        }
    }

    private boolean isTvDevice() {
        UiModeManager mgr = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        return mgr != null && mgr.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void setupTvSearchBehavior() {
        if (searchView == null) {
            return;
        }

        searchView.setFocusable(true);
        searchView.setFocusableInTouchMode(true);
        searchView.setOnClickListener(v -> activateTvSearchInput());
        searchView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                activateTvSearchInput();
                return true;
            }
            return false;
        });
        searchView.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate()
                    .scaleX(hasFocus ? 1.04f : 1.0f)
                    .scaleY(hasFocus ? 1.04f : 1.0f)
                    .alpha(hasFocus ? 1.0f : 0.92f)
                    .setDuration(160L)
                    .start();
            v.setSelected(hasFocus);
        });
    }

    private void setupTvSideMenu() {
        menuHome = findViewById(R.id.menu_home);
        menuBuscar = findViewById(R.id.menu_buscar);
        menuCanales = findViewById(R.id.menu_canales);
        menuFootball = findViewById(R.id.menu_football);
        menuCategorias = findViewById(R.id.menu_categorias);
        menuSeries = findViewById(R.id.menu_series);
        menuDoramas = findViewById(R.id.menu_doramas);
        menuAjustes = findViewById(R.id.menu_ajustes);

        if (menuHome == null || menuBuscar == null || menuCanales == null
                || menuFootball == null || menuCategorias == null) {
            return;
        }

        if (menuSeries != null) menuSeries.setVisibility(View.VISIBLE);
        if (menuDoramas != null) menuDoramas.setVisibility(View.VISIBLE);
        if (menuAjustes != null) menuAjustes.setVisibility(View.VISIBLE);

        menuHome.setOnClickListener(v -> {
            setTvMenuSelected(menuHome);
            if (isSearchMode) {
                exitSearchModeAndRestoreHome();
                return;
            }
            if (!homeSectionsSnapshot.isEmpty()) {
                showSections(copySections(homeSectionsSnapshot));
                restoreTvFocusIfNeeded();
            } else {
                loadHomeData(false);
            }
        });

        menuBuscar.setOnClickListener(v -> {
            startActivity(new Intent(HomeActivity.this, SearchActivity.class));
        });

        menuCanales.setOnClickListener(v -> {
            setTvMenuSelected(menuCanales);
            startActivity(new Intent(HomeActivity.this, TvChannelsActivity.class));
        });

        menuFootball.setOnClickListener(v -> {
            setTvMenuSelected(menuFootball);
            startActivity(new Intent(HomeActivity.this, FootballActivity.class));
        });

        menuCategorias.setOnClickListener(v -> {
            handleTvMenuPlaceholder(menuCategorias);
        });

        menuSeries.setOnClickListener(v -> {
            handleTvMenuPlaceholder(menuSeries);
        });

        menuDoramas.setOnClickListener(v -> {
            handleTvMenuPlaceholder(menuDoramas);
        });

        if (menuAjustes != null) {
            menuAjustes.setOnClickListener(v -> showSettingsDialog());
        }

        setTvMenuSelected(menuHome);
    }

    private void setTvMenuSelected(View selectedItem) {
        View[] items = new View[]{menuHome, menuBuscar, menuCanales, menuFootball, menuCategorias};
        for (View item : items) {
            if (item == null) {
                continue;
            }
            boolean isSelected = item == selectedItem;
            item.setSelected(isSelected);
            item.setActivated(isSelected);
        }
    }

    private void activateTvSearchInput() {
        if (searchView == null) {
            return;
        }

        searchView.setIconified(false);
        searchView.requestFocus();

        EditText searchText = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
        if (searchText != null) {
            searchText.setFocusable(true);
            searchText.setFocusableInTouchMode(true);
            searchText.requestFocus();
            searchText.setSelection(searchText.getText() != null ? searchText.getText().length() : 0);

            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(searchText, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private void handleTvMenuPlaceholder(View menuItem) {
        setTvMenuSelected(menuItem);
        Toast.makeText(this, "Proximamente", Toast.LENGTH_SHORT).show();
    }

    private void showSettingsDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setItems(new String[]{"Usuario: " + (AuthManager.getInstance().getCurrentUser() != null
                        ? AuthManager.getInstance().getCurrentUser().getEmail() : "No autenticado"),
                        "Cerrar sesión"}, (dialog, which) -> {
                    if (which == 1) {
                        AuthManager.getInstance().logout();
                        UserDataManager.getInstance(this).cleanup();
                        Intent intent = new Intent(this, LoginActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void setupPhoneBottomMenu() {
        menuHome = findViewById(R.id.menu_home);
        menuBuscar = findViewById(R.id.menu_buscar);
        menuCanales = findViewById(R.id.menu_canales);
        menuFootball = findViewById(R.id.menu_football);
        menuCategorias = findViewById(R.id.menu_categorias);

        if (menuHome == null || menuBuscar == null || menuCanales == null
                || menuFootball == null || menuCategorias == null) {
            return;
        }

        menuHome.setOnClickListener(v -> {
            setTvMenuSelected(menuHome);
            if (!homeSectionsSnapshot.isEmpty()) {
                showSections(copySections(homeSectionsSnapshot));
            } else {
                loadHomeData(false);
            }
        });

        menuBuscar.setOnClickListener(v -> {
            startActivity(new Intent(HomeActivity.this, SearchActivity.class));
        });

        menuCanales.setOnClickListener(v -> {
            setTvMenuSelected(menuCanales);
            startActivity(new Intent(HomeActivity.this, TvChannelsActivity.class));
        });

        menuFootball.setOnClickListener(v -> {
            setTvMenuSelected(menuFootball);
            startActivity(new Intent(HomeActivity.this, FootballActivity.class));
        });

        menuCategorias.setOnClickListener(v -> {
            setTvMenuSelected(menuCategorias);
            List<Section> source = homeSectionsSnapshot.isEmpty() ? getCachedHomeSectionsIfFresh() : homeSectionsSnapshot;
            showSections(buildCategorySections(copySections(source)));
        });

        setTvMenuSelected(menuHome);
    }

    private void openDetail(MediaItem item) {
        if (item == null) {
            return;
        }
        captureHomeNavigationState();
        pendingTvFocusRestore = isTv;

        UserDataManager udm = UserDataManager.getInstance(this);
        if (udm.isRestoreComplete()) {
            String postId = String.valueOf(item.getPostId());
            udm.addToHistory(postId, item.getTitulo(), item.getImagen(), item.getMediaType());
        }

        DetalleActivity.start(HomeActivity.this, item);
    }

    private void loadHomeData() { loadHomeData(false); }

    private void loadHomeData(boolean forceRefresh) {
        final int requestId = nextRequestId();
        final List<Section> cached = forceRefresh ? null : getCachedHomeSectionsIfFresh();

        if (cached != null && !cached.isEmpty()) {
            progressBar.setVisibility(ProgressBar.GONE);
            homeSectionsSnapshot = copySections(cached);
            showSections(cached);
            if (hasTmdbHomeSections(cached)) {
                return;
            }
        }

        progressBar.setVisibility(ProgressBar.VISIBLE);

        new Thread(() -> {
            try {
                // Lanzar peticiones EN PARALELO pero escalonadas ~120ms
                // para no parecer un burst de bot al mismo host
                ExecutorService pool = Executors.newFixedThreadPool(16);

                // AllCalidad futures
                Future<List<AllCalidadScraper.ContentItem>> featuredFuture =
                        pool.submit((Callable<List<AllCalidadScraper.ContentItem>>)
                                AllCalidadScraper::getFeaturedMovies);
                stagger();
                Future<List<AllCalidadScraper.ContentItem>> popularFuture =
                        pool.submit((Callable<List<AllCalidadScraper.ContentItem>>)
                                AllCalidadScraper::getPopularMovies);
                stagger();
                Future<List<AllCalidadScraper.ContentItem>> latestFuture =
                        pool.submit(() -> AllCalidadScraper.getMovies(1));
                stagger();
                Future<List<AllCalidadScraper.ContentItem>> seriesFuture =
                        pool.submit(() -> AllCalidadScraper.getTvShows(1));
                stagger();
                Future<List<AllCalidadScraper.ContentItem>> popularSeriesFuture =
                        pool.submit((Callable<List<AllCalidadScraper.ContentItem>>)
                                AllCalidadScraper::getPopularTvShows);
                stagger();

                // TMDB futures (nuevos módulos estilo Netflix)
                Future<List<MediaItem>> tmdbPopularFuture =
                        pool.submit(() -> TmdbService.getPopularHomeItems(20));
                stagger();
                Future<List<MediaItem>> tmdbTrendingFuture =
                        pool.submit(() -> TmdbService.getTrendingHomeItems(20));
                stagger();
                Future<List<MediaItem>> tmdbNowPlayingFuture =
                        pool.submit(() -> TmdbService.getNowPlayingMovies(16));
                stagger();
                Future<List<MediaItem>> tmdbUpcomingFuture =
                        pool.submit(() -> TmdbService.getUpcomingMovies(16));
                stagger();
                Future<List<MediaItem>> tmdbTopRatedMoviesFuture =
                        pool.submit(() -> TmdbService.getTopRatedMovies(16));
                stagger();
                Future<List<MediaItem>> tmdbTopRatedTvFuture =
                        pool.submit(() -> TmdbService.getTopRatedTvShows(16));
                stagger();
                Future<List<MediaItem>> tmdbOnTheAirFuture =
                        pool.submit(() -> TmdbService.getOnTheAirTvShows(16));
                stagger();
                Future<List<MediaItem>> tmdbDiscoverFuture =
                        pool.submit(() -> TmdbService.getDiscoverItems(20));
                stagger();

                // Otros scrapers
                Future<List<MediaItem>> pelispediaFuture =
                    pool.submit(() -> new PelispediaScraper().getHomeItems(12));
                pool.shutdown();

                // Recolectar (ya están corriendo en paralelo)
                List<AllCalidadScraper.ContentItem> featured      = safeGet(featuredFuture);
                List<AllCalidadScraper.ContentItem> popular       = safeGet(popularFuture);
                List<AllCalidadScraper.ContentItem> latest        = safeGet(latestFuture);
                List<AllCalidadScraper.ContentItem> series        = safeGet(seriesFuture);
                List<AllCalidadScraper.ContentItem> popularSeries = safeGet(popularSeriesFuture);
                List<MediaItem> tmdbPopular = safeGet(tmdbPopularFuture);
                List<MediaItem> tmdbTrending = safeGet(tmdbTrendingFuture);
                List<MediaItem> tmdbNowPlaying = safeGet(tmdbNowPlayingFuture);
                List<MediaItem> tmdbUpcoming = safeGet(tmdbUpcomingFuture);
                List<MediaItem> tmdbTopRatedMovies = safeGet(tmdbTopRatedMoviesFuture);
                List<MediaItem> tmdbTopRatedTv = safeGet(tmdbTopRatedTvFuture);
                List<MediaItem> tmdbOnTheAir = safeGet(tmdbOnTheAirFuture);
                List<MediaItem> tmdbDiscover = safeGet(tmdbDiscoverFuture);
                List<MediaItem> pelispediaItems = safeGet(pelispediaFuture);

                List<MediaItem> featuredItems      = mapItems(featured,      10);
                List<MediaItem> popularItems       = mapItems(popular,       16);
                List<MediaItem> latestItems        = mapItems(latest,        16);
                List<MediaItem> seriesItems        = mapItems(series,        16);
                List<MediaItem> popularSeriesItems = mapItems(popularSeries, 16);

                // Mostrar secciones SIN esperar TMDB enrichment
                final List<Section> sections = buildSections(
                        featuredItems, popularItems, latestItems,
                        seriesItems, popularSeriesItems,
                        tmdbPopular, tmdbTrending,
                        tmdbNowPlaying, tmdbUpcoming,
                        tmdbTopRatedMovies, tmdbTopRatedTv,
                        tmdbOnTheAir, tmdbDiscover);
                // add Pelispedia sections if available
                if (pelispediaItems != null && !pelispediaItems.isEmpty()) {
                    sections.add(new Section("Pelispedia - Destacadas", pelispediaItems));
                }
                Section continueSection = buildContinueWatchingSection();
                if (continueSection != null) {
                    sections.add(0, continueSection);
                }
                addUserDataSections(sections);
                cacheHomeSections(sections);

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || isSearchMode) return;
                    homeSectionsSnapshot = copySections(sections);
                    showSections(sections);
                    progressBar.setVisibility(ProgressBar.GONE);
                    maybeWarnMissingTmdbKey();
                    restoreTvFocusIfNeeded();
                });

                // TMDB en background (paralelo, no bloquea la UI)
                List<MediaItem> allItems = new ArrayList<>();
                allItems.addAll(featuredItems); allItems.addAll(popularItems);
                allItems.addAll(latestItems);   allItems.addAll(seriesItems);
                allItems.addAll(popularSeriesItems);
                allItems.addAll(tmdbPopular); allItems.addAll(tmdbTrending);
                allItems.addAll(tmdbNowPlaying); allItems.addAll(tmdbUpcoming);
                allItems.addAll(tmdbTopRatedMovies); allItems.addAll(tmdbTopRatedTv);
                allItems.addAll(tmdbOnTheAir); allItems.addAll(tmdbDiscover);
                TmdbService.enrichMediaItemsParallel(allItems);

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || isSearchMode) return;
                    if (adapter != null) adapter.notifyDataSetChanged();
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId)) return;
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(HomeActivity.this,
                            "Error cargando: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private Section buildContinueWatchingSection() {
        List<ContinueWatchingItem> items = ContinueWatchingStore.getAll(this);
        if (items == null || items.isEmpty()) return null;

        List<ContinueWatchingItem> active = new ArrayList<>();
        for (ContinueWatchingItem item : items) {
            if (!item.isCompleted() && item.getPositionMs() > 0) {
                active.add(item);
            }
        }
        if (active.isEmpty()) return null;

        List<MediaItem> mediaItems = new ArrayList<>();
        for (ContinueWatchingItem cw : active) {
            String cleanTitle = cw.getSeriesTitle() != null && !cw.getSeriesTitle().isEmpty()
                    ? cw.getSeriesTitle() : cw.getTitle();
            MediaItem mi = new MediaItem(cleanTitle, "", cw.getImageUrl(), cw.getDetailUrl(), cw.getPostId());
            mi.setMediaType(cw.getMediaType());
            mi.setProgress(Math.min(99, cw.getProgressPercent()));
            mi.setPostId(cw.getPostId());
            mi.setTmdbId(cw.getTmdbId());
            if (mi.getImagen() == null || mi.getImagen().isEmpty()) {
                mi.setImagen(cw.getImageUrl());
            }
            mediaItems.add(mi);
        }

        return mediaItems.isEmpty() ? null : new Section("Continuar Viendo", mediaItems);
    }

    private void addUserDataSections(List<Section> sections) {
        UserDataManager udm = UserDataManager.getInstance(this);
        if (!udm.isRestoreComplete()) return;

        List<FavoriteItem> favorites = udm.getFavorites();
        if (favorites != null && !favorites.isEmpty()) {
            List<MediaItem> favItems = new ArrayList<>();
            for (FavoriteItem f : favorites) {
                MediaItem mi = new MediaItem(f.getTitle(), "", f.getImageUrl(), "", 0);
                mi.setMediaType(f.getMediaType());
                mi.setPostId(parsePostId(f.getContentId()));
                favItems.add(mi);
            }
            sections.add(0, new Section("❤️ Mis Favoritos", favItems));
        }

        List<WatchedHistoryItem> history = udm.getHistory();
        if (history != null && !history.isEmpty()) {
            List<MediaItem> histItems = new ArrayList<>();
            int maxHistory = Math.min(history.size(), 20);
            for (int i = 0; i < maxHistory; i++) {
                WatchedHistoryItem h = history.get(i);
                MediaItem mi = new MediaItem(h.getTitle(), "", h.getImageUrl(), "", 0);
                mi.setMediaType(h.getMediaType());
                mi.setPostId(parsePostId(h.getContentId()));
                histItems.add(mi);
            }
            sections.add(1, new Section("🕐 Historial", histItems));
        }
    }

    private void refreshUserDataSections() {
        if (homeSectionsSnapshot.isEmpty()) return;
        List<Section> updated = copySections(homeSectionsSnapshot);
        updated.removeIf(s -> "❤️ Mis Favoritos".equals(s.getTitle()) || "🕐 Historial".equals(s.getTitle()));
        addUserDataSections(updated);
        homeSectionsSnapshot = copySections(updated);
        showSections(updated);
    }

    private int parsePostId(String contentId) {
        if (contentId == null) return 0;
        try {
            String[] parts = contentId.split("_");
            if (parts.length > 0) {
                String num = parts[0].replaceAll("[^0-9]", "");
                return num.isEmpty() ? 0 : Integer.parseInt(num);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private List<Section> buildSections(
            List<MediaItem> fi, List<MediaItem> pi,
            List<MediaItem> li, List<MediaItem> si, List<MediaItem> psi,
            List<MediaItem> tmdbPopular, List<MediaItem> tmdbTrending,
            List<MediaItem> tmdbNowPlaying, List<MediaItem> tmdbUpcoming,
            List<MediaItem> tmdbTopRatedMovies, List<MediaItem> tmdbTopRatedTv,
            List<MediaItem> tmdbOnTheAir, List<MediaItem> tmdbDiscover) {
        List<Section> s = new ArrayList<>();
        // 1. Destacadas (AllCalidad)
        s.add(new Section("🔥 Destacadas", fi.isEmpty() ? li : fi));
        // 2. Ahora en Cines (TMDB)
        if (tmdbNowPlaying != null && !tmdbNowPlaying.isEmpty()) {
            s.add(new Section("🎥 Ahora en Cines", tmdbNowPlaying));
        }
        // 3. Populares del Mes (AllCalidad)
        s.add(new Section("📈 Populares del Mes", pi.isEmpty() ? li : pi));
        // 4. Series en Emisión (TMDB)
        if (tmdbOnTheAir != null && !tmdbOnTheAir.isEmpty()) {
            s.add(new Section("📡 Series en Emisión", tmdbOnTheAir));
        }
        // 5. Últimas Películas (AllCalidad)
        s.add(new Section("🎬 Últimas Películas", li));
        // 6. Mejor Valoradas - Películas (TMDB)
        if (tmdbTopRatedMovies != null && !tmdbTopRatedMovies.isEmpty()) {
            s.add(new Section("🏆 Mejor Valoradas Películas", tmdbTopRatedMovies));
        }
        // 7. Series (AllCalidad)
        if (!si.isEmpty()) s.add(new Section("📺 Series", si));
        // 8. Mejor Valoradas - Series (TMDB)
        if (tmdbTopRatedTv != null && !tmdbTopRatedTv.isEmpty()) {
            s.add(new Section("🌟 Mejor Valoradas Series", tmdbTopRatedTv));
        }
        // 9. Series Populares (AllCalidad)
        if (!psi.isEmpty()) s.add(new Section("⭐ Series Populares", psi));
        // 10. Próximos Estrenos (TMDB)
        if (tmdbUpcoming != null && !tmdbUpcoming.isEmpty()) {
            s.add(new Section("📅 Próximos Estrenos", tmdbUpcoming));
        }
        // 11. Populares TMDB
        if (tmdbPopular != null && !tmdbPopular.isEmpty()) {
            s.add(new Section("🔥 Populares TMDB", tmdbPopular));
        }
        // 12. Tendencias (TMDB)
        if (tmdbTrending != null && !tmdbTrending.isEmpty()) {
            s.add(new Section("⚡ Tendencias", tmdbTrending));
        }
        // 13. Descubrimiento (TMDB)
        if (tmdbDiscover != null && !tmdbDiscover.isEmpty()) {
            s.add(new Section("✨ Descubrimiento", tmdbDiscover));
        }
        return s;
    }

    private static void stagger() {
        try { Thread.sleep(REQUEST_STAGGER_MS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T> List<T> safeGet(Future<List<T>> future) {
        try { return future.get(); }
        catch (Exception e) { e.printStackTrace(); return new ArrayList<>(); }
    }

    private List<MediaItem> mapItems(List<AllCalidadScraper.ContentItem> items, int limit) {
        List<MediaItem> result = new ArrayList<>();
        if (items == null) return result;
        int max = Math.min(limit, items.size());
        for (int i = 0; i < max; i++) result.add(AllCalidadScraper.toMediaItem(items.get(i)));
        return result;
    }

    private List<MediaItem> mergeSearchResults(
            List<MediaItem> primary, List<MediaItem> secondary, int limit) {
        LinkedHashMap<String, MediaItem> unique = new LinkedHashMap<>();
        appendUniqueSearchItems(unique, primary, limit);
        appendUniqueSearchItems(unique, secondary, limit);
        return new ArrayList<>(unique.values());
    }

    private void appendUniqueSearchItems(
            Map<String, MediaItem> target, List<MediaItem> source, int limit) {
        if (source == null || source.isEmpty() || target.size() >= limit) {
            return;
        }
        for (MediaItem item : source) {
            if (item == null || target.size() >= limit) {
                break;
            }
            String key = buildSearchKey(item);
            if (!target.containsKey(key)) {
                target.put(key, item);
            }
        }
    }

    private String buildSearchKey(MediaItem item) {
        if (item.getTmdbId() > 0) {
            return (item.getMediaType() == null ? "" : item.getMediaType())
                    + "|" + item.getTmdbId();
        }
        String title = item.getTitulo() == null ? "" : item.getTitulo().trim().toLowerCase(Locale.ROOT);
        String year = item.getAnio() == null ? "" : item.getAnio().trim();
        String type = item.getMediaType() == null ? "" : item.getMediaType().trim().toLowerCase(Locale.ROOT);
        return title + "|" + year + "|" + type;
    }

    private void performSearch(String query) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isEmpty()) { exitSearchModeAndRestoreHome(); return; }

        isSearchMode    = true;
        lastSearchQuery = safeQuery;
        final int requestId = nextRequestId();

        progressBar.setVisibility(ProgressBar.VISIBLE);
        searchView.clearFocus();
        showSections(new ArrayList<>());

        new Thread(() -> {
            try {
                List<AllCalidadScraper.ContentItem> results = AllCalidadScraper.search(safeQuery, 1);
                List<MediaItem> mapped = mapItems(results, 40);
                List<MediaItem> tmdbMapped = TmdbService.searchMedia(safeQuery, 40);
                List<MediaItem> merged = mergeSearchResults(mapped, tmdbMapped, 60);

                List<MediaItem> movies = new ArrayList<>(), series = new ArrayList<>();
                for (MediaItem item : merged) {
                    String type = item.getMediaType() == null ? "" :
                            item.getMediaType().toLowerCase(Locale.ROOT);
                    if ("tvshows".equals(type) || "animes".equals(type)) series.add(item);
                    else movies.add(item);
                }

                List<Section> sections = new ArrayList<>();
                if (!movies.isEmpty())  sections.add(new Section("🎬 Películas", movies));
                if (!series.isEmpty())  sections.add(new Section("📺 Series",    series));
                if (sections.isEmpty()) sections.add(new Section("🔎 Resultados", merged));

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode ||
                            !safeQuery.equals(lastSearchQuery)) return;
                    progressBar.setVisibility(ProgressBar.GONE);
                    if (merged.isEmpty()) {
                        showSections(new ArrayList<>());
                        Toast.makeText(HomeActivity.this,
                                "Sin resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showSections(sections);
                    Toast.makeText(HomeActivity.this,
                            "Resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                });

                List<MediaItem> enrichCandidates = new ArrayList<>();
                for (MediaItem item : merged) {
                    if (item == null) {
                        continue;
                    }
                    String source = item.getFuente() == null ? "" : item.getFuente().trim().toLowerCase(Locale.ROOT);
                    if ("tmdb".equals(source) && item.getTmdbId() > 0) {
                        continue;
                    }
                    enrichCandidates.add(item);
                }
                TmdbService.enrichMediaItemsParallel(enrichCandidates);
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode) return;
                    if (adapter != null) adapter.notifyDataSetChanged();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode ||
                            !safeQuery.equals(lastSearchQuery)) return;
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(HomeActivity.this,
                            "Error en búsqueda: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void exitSearchModeAndRestoreHome() {
        isSearchMode = false; lastSearchQuery = "";
        suppressQueryListener = true;
        searchView.setQuery("", false);
        suppressQueryListener = false;
        searchView.clearFocus();
        loadHomeData(false);
    }

    private int nextRequestId()             { return ++activeRequestId; }
    private boolean isRequestActive(int id) { return id == activeRequestId; }

    private void maybeWarnMissingTmdbKey() {
        if (tmdbKeyWarningShown) {
            return;
        }
        if (BuildConfig.TMDB_API_KEY == null || BuildConfig.TMDB_API_KEY.trim().isEmpty()) {
            tmdbKeyWarningShown = true;
            Toast.makeText(this,
                    "TMDB API Key no configurada: agrega tmdb.api.key en local.properties",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (isTv && searchView != null) {
            CharSequence q = searchView.getQuery();
            if (isSearchMode || (q != null && q.toString().trim().length() > 0)) {
                exitSearchModeAndRestoreHome(); return;
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (isTv) {
            captureHomeNavigationState();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreTvFocusIfNeeded();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        captureHomeNavigationState();
        outState.putInt("home_focus_section", lastFocusedSectionPos);
        outState.putInt("home_focus_item", lastFocusedItemPos);
        outState.putInt("home_scroll_pos", lastHomeFirstVisiblePos);
        outState.putInt("home_scroll_offset", lastHomeTopOffset);
    }

    private static synchronized List<Section> getCachedHomeSectionsIfFresh() {
        if (homeSectionsCache == null || homeSectionsCache.isEmpty()) return null;
        if (System.currentTimeMillis() - homeCacheSavedAtMs > HOME_CACHE_TTL_MS) {
            homeSectionsCache = null; homeCacheSavedAtMs = 0; return null;
        }
        return copySections(homeSectionsCache);
    }

    private static synchronized void cacheHomeSections(List<Section> sections) {
        if (sections == null || sections.isEmpty()) return;
        homeSectionsCache  = copySections(sections);
        homeCacheSavedAtMs = System.currentTimeMillis();
    }

    private boolean hasTmdbHomeSections(List<Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return false;
        }
        boolean hasPopularTmdb = false;
        boolean hasTrending = false;
        for (Section section : sections) {
            if (section == null || section.getTitle() == null) {
                continue;
            }
            String title = section.getTitle().trim().toLowerCase(Locale.ROOT);
            if ("populares tmdb".equals(title)) {
                hasPopularTmdb = true;
            } else if ("tendencias".equals(title)) {
                hasTrending = true;
            }
            if (hasPopularTmdb && hasTrending) {
                return true;
            }
        }
        return false;
    }

    private static List<Section> copySections(List<Section> source) {
        List<Section> copy = new ArrayList<>();
        if (source == null) return copy;
        for (Section s : source) {
            if (s == null) continue;
            copy.add(new Section(s.getTitle() == null ? "" : s.getTitle(),
                    copyMediaItems(s.getItems())));
        }
        return copy;
    }

    private static List<MediaItem> copyMediaItems(List<MediaItem> items) {
        List<MediaItem> copy = new ArrayList<>();
        if (items == null) return copy;
        for (MediaItem item : items) {
            if (item == null) continue;
            MediaItem c = new MediaItem(item.getTitulo(), item.getAnio(),
                    item.getImagen(), item.getDetailUrl(), 0);
            c.setBackdrop(item.getBackdrop());
            c.setFuente(item.getFuente());
            c.setMediaType(item.getMediaType());
            c.setPostId(item.getPostId());
            c.setSynopsis(item.getSynopsis());
            c.setDublado(item.isDublado());
            c.setRating(item.getRating());
            c.setProgress(item.getProgress());
            c.setTmdbId(item.getTmdbId());
            c.setImdbId(item.getImdbId());
            copy.add(c);
        }
        return copy;
    }

    private void showSections(List<Section> sections) {
        if (adapter == null) {
            adapter = new HomeAdapter(this, sections, this::openDetail, isTv);
            adapter.setOnFocusPositionChangedListener((sectionPosition, itemPosition) -> {
                lastFocusedSectionPos = Math.max(0, sectionPosition);
                lastFocusedItemPos = Math.max(0, itemPosition);
            });
            rvSections.setAdapter(adapter);
            return;
        }
        adapter.setSections(sections);
    }

    private void captureHomeNavigationState() {
        if (rvSections == null) {
            return;
        }

        RecyclerView.LayoutManager manager = rvSections.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            return;
        }
        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int firstVisible = linear.findFirstVisibleItemPosition();
        if (firstVisible >= 0) {
            lastHomeFirstVisiblePos = firstVisible;
        }

        View topChild = rvSections.getChildAt(0);
        if (topChild != null) {
            lastHomeTopOffset = topChild.getTop() - rvSections.getPaddingTop();
        }

        if (adapter != null) {
            int focusedSection = adapter.getLastFocusedSection();
            int focusedItem = adapter.getLastFocusedItem();
            if (focusedSection >= 0) {
                lastFocusedSectionPos = focusedSection;
            }
            if (focusedItem >= 0) {
                lastFocusedItemPos = focusedItem;
            }
        }
    }

    private void restoreTvFocusIfNeeded() {
        if (!isTv || !pendingTvFocusRestore || rvSections == null || adapter == null) {
            return;
        }

        pendingTvFocusRestore = false;
        rvSections.post(() -> {
            RecyclerView.LayoutManager manager = rvSections.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager)
                        .scrollToPositionWithOffset(Math.max(0, lastHomeFirstVisiblePos), lastHomeTopOffset);
            }
            rvSections.post(() -> adapter.requestFocusAt(lastFocusedSectionPos, lastFocusedItemPos));
        });
    }

    private List<Section> buildCategorySections(List<Section> sourceSections) {
        List<MediaItem> movies = new ArrayList<>();
        List<MediaItem> series = new ArrayList<>();
        List<MediaItem> doramas = new ArrayList<>();

        if (sourceSections != null) {
            for (Section section : sourceSections) {
                if (section == null || section.getItems() == null) {
                    continue;
                }
                for (MediaItem item : section.getItems()) {
                    if (item == null) {
                        continue;
                    }
                    String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
                    String title = item.getTitulo() == null ? "" : item.getTitulo().toLowerCase(Locale.ROOT);
                    if (isDoramasType(type, title)) {
                        doramas.add(item);
                    } else if (isSeriesType(type)) {
                        series.add(item);
                    } else {
                        movies.add(item);
                    }
                }
            }
        }

        List<Section> result = new ArrayList<>();
        if (!movies.isEmpty()) {
            result.add(new Section("Peliculas", movies));
        }
        if (!series.isEmpty()) {
            result.add(new Section("Series", series));
        }
        if (!doramas.isEmpty()) {
            result.add(new Section("Doramas", doramas));
        }
        if (result.isEmpty()) {
            result.add(new Section("Categorias", new ArrayList<>()));
        }
        return result;
    }

    private List<Section> filterSectionsByType(List<Section> sourceSections, boolean seriesOnly) {
        List<Section> result = new ArrayList<>();
        if (sourceSections == null) {
            return result;
        }

        for (Section section : sourceSections) {
            if (section == null || section.getItems() == null) {
                continue;
            }
            List<MediaItem> filtered = new ArrayList<>();
            for (MediaItem item : section.getItems()) {
                if (item == null) {
                    continue;
                }
                String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
                if (seriesOnly && isSeriesType(type)) {
                    filtered.add(item);
                }
            }
            if (!filtered.isEmpty()) {
                result.add(new Section(section.getTitle(), filtered));
            }
        }

        if (result.isEmpty()) {
            result.add(new Section("Series", new ArrayList<>()));
        }
        return result;
    }

    private List<Section> filterSectionsDoramas(List<Section> sourceSections) {
        List<Section> result = new ArrayList<>();
        if (sourceSections == null) {
            return result;
        }

        for (Section section : sourceSections) {
            if (section == null || section.getItems() == null) {
                continue;
            }
            List<MediaItem> filtered = new ArrayList<>();
            for (MediaItem item : section.getItems()) {
                if (item == null) {
                    continue;
                }
                String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
                String title = item.getTitulo() == null ? "" : item.getTitulo().toLowerCase(Locale.ROOT);
                if (isDoramasType(type, title)) {
                    filtered.add(item);
                }
            }
            if (!filtered.isEmpty()) {
                result.add(new Section(section.getTitle(), filtered));
            }
        }

        if (result.isEmpty()) {
            result.add(new Section("Doramas", new ArrayList<>()));
        }
        return result;
    }

    private boolean isSeriesType(String type) {
        return "tvshows".equals(type) || "animes".equals(type)
                || "series".equals(type) || "tv".equals(type);
    }

    private boolean isDoramasType(String type, String title) {
        return type.contains("dorama")
                || title.contains("dorama")
                || title.contains("k-drama")
                || title.contains("kdrama")
                || title.contains("drama coreano");
    }
}
