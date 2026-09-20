package sv.edu.catolica.rex.ui.home;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import sv.edu.catolica.rex.BuildConfig;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;
import sv.edu.catolica.rex.network.smart.PelispediaScraper;
import sv.edu.catolica.rex.network.smart.SmartScraperEngine;
import sv.edu.catolica.rex.models.FavoriteItem;
import sv.edu.catolica.rex.repository.UserDataManager;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;
import sv.edu.catolica.rex.ui.auth.AuthManager;
import sv.edu.catolica.rex.ui.auth.LoginActivity;
import sv.edu.catolica.rex.ui.football.FootballActivity;
import sv.edu.catolica.rex.ui.detalle.DetalleActivity;
import sv.edu.catolica.rex.ui.player.PlayerContenidoActivity;

public class HomeActivity extends AppCompatActivity {

    private static final long HOME_CACHE_TTL_MS = 7 * 60 * 1000L;
    private static List<Section> homeSectionsCache;
    private static long homeCacheSavedAtMs;

    /** Pausa entre peticiones al mismo servidor (ms).
     *  Evita patrones de bot sin sacrificar velocidad real. */
    private static final long REQUEST_STAGGER_MS = 120;

    private RecyclerView rvSections;
    private ProgressBar  progressBar;
    private View heroContainer;
    private ViewPager2 heroPager;
    private android.widget.LinearLayout heroDots;
    private final Handler heroHandler = new Handler(Looper.getMainLooper());
    private Runnable heroAutoScroll;
    private float heroScrollOffset;
    private ViewPager2.OnPageChangeCallback heroPageCallback;
    private final List<MediaItem> currentHeroItems = new ArrayList<>();
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
    private final Map<String, ContinueWatchingItem> continueWatchingByContentId = new LinkedHashMap<>();
    private HomeViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        sv.edu.catolica.rex.ui.player.PlaybackCacheHelper.initCacheAsync(this);
        getWindow().getDecorView().postDelayed(this::warmUpWebView, 3000L);

        isTv = isTvDevice();

        rvSections  = findViewById(R.id.rv_home_sections);
        progressBar = findViewById(R.id.progressBar);
        heroContainer = findViewById(R.id.hero_container);
        heroPager = findViewById(R.id.vp_hero);
        heroDots = findViewById(R.id.dots_container);
        rvSections.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // El carrusel se desplaza exactamente con el scroll del listado:
                // forma un solo bloque con el contenido (nada de overlay fijo).
                if (isTv || heroContainer == null) return;
                int offset = recyclerView.computeVerticalScrollOffset();
                heroContainer.setTranslationY(Math.min(0f, -offset));
            }
        });

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

        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);
        setupViewModelObservers();
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

    private android.webkit.WebView webViewWarmup;

    /**
     * Pre-calienta el WebView (carga la librería nativa y el proceso de render)
     * mientras el usuario está en el Home, para que abrir el reproductor no
     * tarde ~900ms en cargar el motor. WebView debe crearse y usarse en el
     * hilo principal de Android.
     */
    private void warmUpWebView() {
        try {
            android.os.Handler mainHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(() -> {
                try {
                    android.webkit.WebView wv = new android.webkit.WebView(this);
                    wv.setVisibility(View.GONE);
                    wv.loadUrl("about:blank");
                    webViewWarmup = wv;
                    mainHandler.postDelayed(() -> {
                        try {
                            wv.destroy();
                        } catch (Exception ignored) {
                        }
                        if (webViewWarmup == wv) webViewWarmup = null;
                    }, 5000L);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
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
        com.google.firebase.auth.FirebaseUser user = AuthManager.getInstance().getCurrentUser();
        String name = user != null ? user.getDisplayName() : null;
        String email = user != null ? user.getEmail() : null;
        String title = user != null && name != null && !name.isEmpty() ? name : "Ajustes";

        List<String> items = new ArrayList<>();
        items.add(user != null && email != null ? email : "No autenticado");
        items.add("Cambiar contraseña");
        items.add("Cerrar sesión");

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which == 1) {
                        if (user != null && email != null) {
                            AuthManager.getInstance().sendPasswordReset(email, new AuthManager.AuthCallback() {
                                @Override
                                public void onSuccess(com.google.firebase.auth.FirebaseUser u) {
                                    Toast.makeText(HomeActivity.this,
                                            "Revisa tu correo para restablecer tu contraseña",
                                            Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(Exception e) {
                                    Toast.makeText(HomeActivity.this,
                                            e != null ? e.getLocalizedMessage() : "No se pudo enviar el correo",
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    } else if (which == 2) {
                        UserDataManager.reset(this);
                        AuthManager.getInstance().logout();
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
        menuAjustes = findViewById(R.id.menu_ajustes);

        if (menuHome == null || menuBuscar == null || menuCanales == null
                || menuFootball == null || menuCategorias == null) {
            return;
        }

        if (menuAjustes != null) {
            menuAjustes.setOnClickListener(v -> showSettingsDialog());
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
        ContinueWatchingItem continueItem = findContinueWatchingItem(item);
        if (continueItem != null) {
            openContinueWatchingPlayer(item, continueItem);
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

    private ContinueWatchingItem findContinueWatchingItem(MediaItem item) {
        if (item == null || item.getFuente() == null) {
            return null;
        }
        String marker = "continue_watching:";
        if (!item.getFuente().startsWith(marker)) {
            return null;
        }
        String contentId = item.getFuente().substring(marker.length());
        ContinueWatchingItem cached = continueWatchingByContentId.get(contentId);
        return cached != null ? cached : ContinueWatchingStore.getByContentId(this, contentId);
    }

    private void openContinueWatchingPlayer(MediaItem item, ContinueWatchingItem continueItem) {
        captureHomeNavigationState();
        pendingTvFocusRestore = isTv;
        progressBar.setVisibility(ProgressBar.VISIBLE);

        new Thread(() -> {
            try {
                MediaItem playbackItem = buildContinuePlaybackItem(item, continueItem);
                SmartScraperEngine resolver = new SmartScraperEngine();
                List<String> urls = isSeriesContinueItem(continueItem)
                        ? resolver.resolveEpisodeUrls(
                                HomeActivity.this,
                                playbackItem,
                                continueItem.getSeasonNumber(),
                                continueItem.getEpisodeNumber(),
                                continueItem.getEpisodeId())
                        : resolver.resolveMovieUrls(HomeActivity.this, playbackItem);

                if ((urls == null || urls.isEmpty()) && continueItem.getEpisodeId() > 0) {
                    AllCalidadScraper.hit(continueItem.getEpisodeId(), "episodes");
                    urls = AllCalidadScraper.getPlayableUrls(
                            AllCalidadScraper.getPlayer(continueItem.getEpisodeId()));
                }
                if ((urls == null || urls.isEmpty()) && continueItem.getPostId() > 0) {
                    AllCalidadScraper.hit(continueItem.getPostId(), continueItem.getMediaType());
                    urls = AllCalidadScraper.getPlayableUrls(
                            AllCalidadScraper.getPlayer(continueItem.getPostId()));
                }

                final List<String> finalUrls = urls;
                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    if (finalUrls == null || finalUrls.isEmpty()) {
                        Toast.makeText(HomeActivity.this,
                                "No hay servidores disponibles para continuar",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startContinueWatchingPlayer(playbackItem, continueItem, new ArrayList<>(finalUrls));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(HomeActivity.this,
                            "Error al continuar reproduccion",
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private MediaItem buildContinuePlaybackItem(MediaItem item, ContinueWatchingItem continueItem) {
        String title = continueItem.getSeriesTitle() != null && !continueItem.getSeriesTitle().isEmpty()
                ? continueItem.getSeriesTitle()
                : item.getTitulo();
        MediaItem playbackItem = new MediaItem(title, item.getAnio(),
                item.getImagen(), item.getDetailUrl(), 0);
        playbackItem.setFuente(item.getFuente());
        playbackItem.setMediaType(continueItem.getMediaType());
        playbackItem.setPostId(continueItem.getPostId());
        playbackItem.setTmdbId(continueItem.getTmdbId());
        playbackItem.setImdbId(item.getImdbId());
        playbackItem.setSynopsis(item.getSynopsis());
        return playbackItem;
    }

    private void startContinueWatchingPlayer(
            MediaItem item,
            ContinueWatchingItem continueItem,
            ArrayList<String> urls
    ) {
        String title = continueItem.getTitle() != null && !continueItem.getTitle().isEmpty()
                ? continueItem.getTitle()
                : item.getTitulo();
        String seriesTitle = continueItem.getSeriesTitle() != null && !continueItem.getSeriesTitle().isEmpty()
                ? continueItem.getSeriesTitle()
                : item.getTitulo();

        Intent intent;
        if (isSeriesContinueItem(continueItem)) {
            intent = PlayerContenidoActivity.createEpisodeIntent(
                    this,
                    urls,
                    title,
                    seriesTitle,
                    continueItem.getPostId(),
                    continueItem.getMediaType(),
                    continueItem.getEpisodeId(),
                    continueItem.getSeasonNumber(),
                    continueItem.getEpisodeNumber()
            );
        } else {
            intent = PlayerContenidoActivity.createIntent(this, urls, title);
        }

        intent.putExtra("resume_position_ms", continueItem.getPositionMs());
        intent.putExtra("exo_resume_position_ms", continueItem.getPositionMs());
        intent.putExtra("exo_content_id", continueItem.getContentId());
        intent.putExtra("exo_post_id", continueItem.getPostId());
        intent.putExtra("exo_media_type", continueItem.getMediaType());
        intent.putExtra("exo_series_title", seriesTitle);
        intent.putExtra("series_post_id", continueItem.getPostId());
        intent.putExtra("series_title", seriesTitle);
        intent.putExtra("series_post_type", continueItem.getMediaType());
        if (continueItem.getImageUrl() != null && !continueItem.getImageUrl().isEmpty()) {
            intent.putExtra("image_url", continueItem.getImageUrl());
            intent.putExtra("exo_image_url", continueItem.getImageUrl());
        }
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, 0);
    }

    private boolean isSeriesContinueItem(ContinueWatchingItem item) {
        if (item == null) {
            return false;
        }
        String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
        return item.getEpisodeId() > 0
                || type.contains("tv")
                || type.contains("series")
                || type.contains("dorama");
    }

    private void loadHomeData() { loadHomeData(false); }

    private void loadHomeData(boolean forceRefresh) {
        final List<Section> cached = forceRefresh ? null : getCachedHomeSectionsIfFresh();

        if (cached != null && !cached.isEmpty()) {
            progressBar.setVisibility(ProgressBar.GONE);
            homeSectionsSnapshot = copySections(cached);
            showSections(cached);
            setupHeroCarousel(extractHeroItems(cached));
            if (hasTmdbHomeSections(cached)) {
                return;
            }
        }

        if (forceRefresh) {
            viewModel.refresh();
        } else {
            viewModel.loadHomeData();
        }
    }

    private void setupViewModelObservers() {
        viewModel.getUiState().observe(this, state -> {
            if (state instanceof HomeUiState.Loading) {
                progressBar.setVisibility(ProgressBar.VISIBLE);
            } else if (state instanceof HomeUiState.Success) {
                if (isSearchMode) return;
                HomeUiState.Success success = (HomeUiState.Success) state;
                List<Section> sections = new ArrayList<>(success.getSections());

                addUserDataSections(sections);
                Section continueSection = buildContinueWatchingSection();
                if (continueSection != null) {
                    sections.add(0, continueSection);
                }
                cacheHomeSections(sections);

                homeSectionsSnapshot = copySections(sections);
                showSections(sections);
                setupHeroCarousel(success.getHeroCandidates());
                progressBar.setVisibility(ProgressBar.GONE);
                maybeWarnMissingTmdbKey();
                restoreTvFocusIfNeeded();
            } else if (state instanceof HomeUiState.Error) {
                progressBar.setVisibility(ProgressBar.GONE);
                Toast.makeText(HomeActivity.this,
                        "Error cargando: " + ((HomeUiState.Error) state).getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });

        viewModel.getEnrichmentComplete().observe(this, complete -> {
            if (Boolean.TRUE.equals(complete) && adapter != null && !isSearchMode) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private Section buildContinueWatchingSection() {
        List<ContinueWatchingItem> items = ContinueWatchingStore.getAll(this);
        continueWatchingByContentId.clear();
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
            if (cw.getContentId() != null && !cw.getContentId().isEmpty()) {
                mi.setFuente("continue_watching:" + cw.getContentId());
                continueWatchingByContentId.put(cw.getContentId(), cw);
            }
            if (mi.getImagen() == null || mi.getImagen().isEmpty()) {
                mi.setImagen(cw.getImageUrl());
            }

            // Retroalimentación visual precisa (Episodio y/o tiempo restante)
            StringBuilder feedback = new StringBuilder();
            if (isSeriesContinueItem(cw) && cw.getEpisodeNumber() > 0) {
                int sNum = cw.getSeasonNumber() > 0 ? cw.getSeasonNumber() : 1;
                feedback.append("T").append(sNum).append(":E").append(cw.getEpisodeNumber());
            }
            if (cw.getDurationMs() > cw.getPositionMs() && cw.getPositionMs() > 0) {
                long remainingMin = (cw.getDurationMs() - cw.getPositionMs()) / 60000L;
                if (remainingMin > 0) {
                    if (feedback.length() > 0) feedback.append(" • ");
                    feedback.append(remainingMin).append(" min rest.");
                } else {
                    if (feedback.length() > 0) feedback.append(" • ");
                    feedback.append(cw.getProgressPercent()).append("%");
                }
            } else if (cw.getPositionMs() > 0) {
                long posMin = cw.getPositionMs() / 60000L;
                if (posMin > 0) {
                    if (feedback.length() > 0) feedback.append(" • ");
                    feedback.append(posMin).append(" min vistos");
                } else if (cw.getProgressPercent() > 0) {
                    if (feedback.length() > 0) feedback.append(" • ");
                    feedback.append(cw.getProgressPercent()).append("%");
                }
            }
            mi.setAnio(feedback.toString());

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
    }

    private void refreshUserDataSections() {
        if (homeSectionsSnapshot.isEmpty()) return;
        List<Section> updated = copySections(homeSectionsSnapshot);
        updated.removeIf(s -> "❤️ Mis Favoritos".equals(s.getTitle())
                || "🕐 Historial".equals(s.getTitle())
                || "Continuar Viendo".equals(s.getTitle()));
        addUserDataSections(updated);
        Section continueSection = buildContinueWatchingSection();
        if (continueSection != null) {
            updated.add(0, continueSection);
        }
        homeSectionsSnapshot = copySections(updated);
        showSections(updated);
    }

    private int parsePostId(String contentId) {
        if (contentId == null) return 0;
        try {
            String num = contentId.replaceAll("[^0-9]", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (Exception ignored) {}
        return 0;
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
        } else if (sv.edu.catolica.rex.network.TmdbService.isTmdbKeyInvalid()) {
            tmdbKeyWarningShown = true;
            Toast.makeText(this,
                    "TMDB API Key inválida (HTTP 401): revisa tmdb.api.key en local.properties",
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
        stopHeroAutoScroll();
        if (isTv) {
            captureHomeNavigationState();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isSearchMode) {
            refreshUserDataSections();
        }
        if (!isTv && heroPager != null && heroPager.getAdapter() != null) {
            startHeroAutoScroll(heroPager.getAdapter().getItemCount());
        }
        restoreTvFocusIfNeeded();
        if (AuthManager.getInstance().isSignedIn()) {
            UserDataManager.getInstance(this).processPendingOnReconnect();
        }
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

    private List<MediaItem> extractHeroItems(List<Section> sections) {
        List<MediaItem> heroItems = new ArrayList<>();
        if (sections == null) return heroItems;
        for (Section section : sections) {
            if (section == null || section.getItems() == null) continue;
            for (MediaItem item : section.getItems()) {
                if (item != null && item.getTmdbId() > 0
                        && item.getBackdrop() != null && !item.getBackdrop().isEmpty()) {
                    heroItems.add(item);
                    if (heroItems.size() == 6) return heroItems;
                }
            }
        }
        return heroItems;
    }

    /**
     * Configura el carrusel combinando contenido de AllCalidad (postId, ya
     * reproducibles) y de TMDB (trending/popular), como se mostraba antes.
     * Los items TMDB se enriquecen en segundo plano con su postId de AllCalidad
     * para que al tocar "Reproducir" funcionen; no se quitan del carrusel.
     */
    private void setupHeroCarousel(List<MediaItem> candidates) {
        if (isTv || heroContainer == null || heroPager == null) return;
        List<MediaItem> combined = new ArrayList<>();
        if (candidates != null) {
            for (MediaItem c : candidates) {
                if (c == null) continue;
                boolean dup = false;
                for (MediaItem existing : combined) {
                    if (sameHeroItem(existing, c)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    combined.add(c);
                    if (combined.size() >= 6) break;
                }
            }
        }
        currentHeroItems.clear();
        currentHeroItems.addAll(combined);
        applyHeroCarousel();
        verifyHeroAsync(currentHeroItems);
    }

    private boolean sameHeroItem(MediaItem a, MediaItem b) {
        if (a == null || b == null) return false;
        if (a.getTmdbId() > 0 && b.getTmdbId() > 0) return a.getTmdbId() == b.getTmdbId();
        String ta = a.getTitulo() == null ? "" : a.getTitulo().trim().toLowerCase(Locale.ROOT);
        String tb = b.getTitulo() == null ? "" : b.getTitulo().trim().toLowerCase(Locale.ROOT);
        String ya = a.getAnio() == null ? "" : a.getAnio().trim();
        String yb = b.getAnio() == null ? "" : b.getAnio().trim();
        return !ta.isEmpty() && ta.equals(tb) && ya.equals(yb);
    }

    private void applyHeroCarousel() {
        if (isTv || heroContainer == null || heroPager == null) return;
        List<MediaItem> heroItems = new ArrayList<>(currentHeroItems);
        if (heroItems.isEmpty()) {
            heroContainer.setVisibility(View.GONE);
            heroScrollOffset = 0f;
            heroContainer.setTranslationY(0f);
            stopHeroAutoScroll();
            updateHomeTopPadding(false);
            return;
        }

        heroContainer.setVisibility(View.VISIBLE);
        heroScrollOffset = 0f;
        heroContainer.setTranslationY(0f);
        updateHomeTopPadding(true);
        heroPager.setAdapter(new HeroPagerAdapter(heroItems, new HeroPagerAdapter.Listener() {
            @Override public void onPlay(MediaItem item) { openDetail(item); }
            @Override public void onInfo(MediaItem item) { openDetail(item); }
        }));
        heroPager.setOffscreenPageLimit(1);
        heroPager.setPageTransformer((page, position) ->
                page.setScaleY(1f - Math.min(0.04f, Math.abs(position) * 0.04f)));

        heroDots.removeAllViews();
        for (int i = 0; i < heroItems.size(); i++) {
            ImageView dot = new ImageView(this);
            dot.setImageResource(i == 0 ? R.drawable.dot_active : R.drawable.dot_inactive);
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(7, 7);
            params.setMargins(4, 0, 4, 0);
            heroDots.addView(dot, params);
        }
        if (heroPageCallback != null) {
            heroPager.unregisterOnPageChangeCallback(heroPageCallback);
        }
        heroPageCallback = new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                for (int i = 0; i < heroDots.getChildCount(); i++) {
                    ((ImageView) heroDots.getChildAt(i)).setImageResource(
                            i == position ? R.drawable.dot_active : R.drawable.dot_inactive);
                }
            }
        };
        heroPager.registerOnPageChangeCallback(heroPageCallback);
        startHeroAutoScroll(heroItems.size());
    }

    /** Enriquece en segundo plano los items TMDB del carrusel con su postId de
     *  AllCalidad (para que "Reproducir" funcione) sin quitar nada del carrusel. */
    private void verifyHeroAsync(List<MediaItem> items) {
        if (items == null || items.isEmpty()) return;
        List<MediaItem> toVerify = new ArrayList<>();
        for (MediaItem c : items) {
            if (c == null) continue;
            boolean playable = c.getPostId() > 0
                    || (c.getDetailUrl() != null && !c.getDetailUrl().isEmpty());
            if (!playable) toVerify.add(c);
        }
        if (toVerify.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, toVerify.size()));
        for (MediaItem item : toVerify) {
            pool.submit(() -> {
                try {
                    AllCalidadScraper.ContentItem found = AllCalidadScraper.findByTitleAndYear(
                            item.getTitulo(), item.getAnio(), item.getMediaType());
                    if (found != null && found.id > 0) {
                        item.setPostId(found.id);
                    }
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        Thread waiter = new Thread(() -> {
            try {
                pool.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                pool.shutdownNow();
            }
        });
        waiter.start();
    }

    /** Ajusta el padding superior del listado: con carrusel visible el contenido
     *  empieza debajo de él (y lo rellena al desplazarse); sin carrusel arranca arriba. */
    private void updateHomeTopPadding(boolean heroVisible) {
        if (rvSections == null) return;
        float density = getResources().getDisplayMetrics().density;
        int top = heroVisible ? (int) (420f * density) : (int) (24f * density);
        rvSections.setPadding(0, top, 0, (int) (100f * density));
    }

    private void startHeroAutoScroll(int itemCount) {
        stopHeroAutoScroll();
        if (itemCount < 2) return;
        heroAutoScroll = new Runnable() {
            @Override public void run() {
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
