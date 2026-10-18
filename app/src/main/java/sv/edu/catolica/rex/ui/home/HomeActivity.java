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
import sv.edu.catolica.rex.source.UnifiedSearchService;
import sv.edu.catolica.rex.ui.auth.AuthManager;
import sv.edu.catolica.rex.ui.auth.LoginActivity;
import sv.edu.catolica.rex.ui.novelas.NovelasActivity;
import sv.edu.catolica.rex.ui.detalle.DetalleActivity;
import sv.edu.catolica.rex.ui.player.PlayerContenidoActivity;

public class HomeActivity extends AppCompatActivity {

    private static final long HOME_CACHE_TTL_MS = 7 * 60 * 1000L;
    private static final String PREF_HOME_CACHE = "home_sections_cache";
    private static final String KEY_SECTIONS_JSON = "cached_sections_json";
    private static final String KEY_CACHE_TIMESTAMP = "cached_sections_time";
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
    private View menuNovelas;
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
    private boolean showingPartialHome = false;
    private androidx.core.widget.NestedScrollView tvNestedScroll;
    private final Map<String, ContinueWatchingItem> continueWatchingByContentId = new LinkedHashMap<>();
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();
    private HomeViewModel viewModel;
    private UserDataManager.DataChangeListener userDataListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        sv.edu.catolica.rex.ui.player.PlaybackCacheHelper.initCacheAsync(this);
        sv.edu.catolica.rex.utils.AppCacheManager.autoPruneOnStartup(this);
        getWindow().getDecorView().postDelayed(this::warmUpWebView, 3000L);

        isTv = isTvDevice();

        if (isTv) {
            tvNestedScroll = findViewById(R.id.tv_nested_scroll);
        }

        rvSections  = findViewById(R.id.rv_home_sections);
        progressBar = findViewById(R.id.progressBar);
        heroContainer = findViewById(R.id.hero_container);
        heroPager = findViewById(R.id.vp_hero);
        heroDots = findViewById(R.id.dots_container);
        rvSections.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // En móvil el carrusel se desplaza con translationY para integrarse.
                // En TV, el desplazamiento continuo lo maneja tvNestedScroll directamente.
                if (!isTv && heroContainer != null) {
                    int offset = recyclerView.computeVerticalScrollOffset();
                    heroContainer.setTranslationY(Math.min(0f, -offset));
                }
            }
        });

        rvSections.setLayoutManager(new LinearLayoutManager(this));
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

        userDataListener = new UserDataManager.DataChangeListener() {
            @Override
            public void onFavoritesChanged(List<FavoriteItem> favorites) {
                runOnUiThread(() -> {
                    refreshUserDataSections();
                    if (heroPager != null && heroPager.getAdapter() != null) {
                        heroPager.getAdapter().notifyItemChanged(heroPager.getCurrentItem());
                    }
                });
            }

            @Override
            public void onHistoryChanged(List<sv.edu.catolica.rex.models.WatchedHistoryItem> history) {
            }

            @Override
            public void onSettingsChanged(sv.edu.catolica.rex.models.UserSettings settings) {
            }
        };
        UserDataManager.getInstance(this).addDataChangeListener(userDataListener);

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
        return sv.edu.catolica.rex.utils.TvUtils.isTv(this);
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
        menuNovelas = findViewById(R.id.menu_novelas);
        menuSeries = findViewById(R.id.menu_series);
        menuDoramas = findViewById(R.id.menu_doramas);
        menuAjustes = findViewById(R.id.menu_ajustes);

        if (menuHome == null || menuBuscar == null || menuCanales == null
                || menuNovelas == null) {
            return;
        }

        if (menuSeries != null) menuSeries.setVisibility(View.VISIBLE);
        if (menuDoramas != null) menuDoramas.setVisibility(View.VISIBLE);
        if (menuAjustes != null) menuAjustes.setVisibility(View.VISIBLE);

        View.OnKeyListener menuRightListener = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (heroContainer != null && heroContainer.getVisibility() == View.VISIBLE && lastFocusedSectionPos == -1) {
                    return focusHeroPlayButton();
                } else if (adapter != null && adapter.getItemCount() > 0) {
                    int sec = Math.max(0, adapter.getLastFocusedSection());
                    int it = Math.max(0, adapter.getLastFocusedItem());
                    scrollTvToSection(sec);
                    adapter.requestFocusAt(sec, it);
                    return true;
                } else if (heroContainer != null && heroContainer.getVisibility() == View.VISIBLE) {
                    return focusHeroPlayButton();
                }
            }
            return false;
        };

        menuHome.setOnKeyListener(menuRightListener);
        menuBuscar.setOnKeyListener(menuRightListener);
        menuCanales.setOnKeyListener(menuRightListener);
        menuNovelas.setOnKeyListener(menuRightListener);
        if (menuSeries != null) menuSeries.setOnKeyListener(menuRightListener);
        if (menuDoramas != null) menuDoramas.setOnKeyListener(menuRightListener);
        if (menuAjustes != null) menuAjustes.setOnKeyListener(menuRightListener);

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

        menuNovelas.setOnClickListener(v -> {
            setTvMenuSelected(menuNovelas);
            startActivity(new Intent(HomeActivity.this, NovelasActivity.class));
        });

        menuSeries.setOnClickListener(v -> {
            handleTvMenuPlaceholder(menuSeries);
        });

        menuDoramas.setOnClickListener(v -> {
            setTvMenuSelected(menuDoramas);
            startActivity(new Intent(HomeActivity.this, sv.edu.catolica.rex.ui.doramas.DoramasActivity.class));
        });

        if (menuAjustes != null) {
            menuAjustes.setOnClickListener(v -> showSettingsDialog());
        }

        setTvMenuSelected(menuHome);
    }

    private void setTvMenuSelected(View selectedItem) {
        View[] items = new View[]{menuHome, menuBuscar, menuCanales, menuNovelas, menuSeries, menuDoramas};
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

        long cacheBytes = sv.edu.catolica.rex.utils.AppCacheManager.getCacheSizeBytes(this);
        String cacheStr = sv.edu.catolica.rex.utils.AppCacheManager.formatSize(cacheBytes);

        List<String> items = new ArrayList<>();
        items.add(user != null && email != null ? email : "No autenticado");
        items.add("Cambiar contraseña");
        items.add("Limpiar caché (" + cacheStr + ")");
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
                        Toast.makeText(this, "Limpiando caché...", Toast.LENGTH_SHORT).show();
                        sv.edu.catolica.rex.utils.AppCacheManager.cleanCache(this, (bytesFreed, formatted) -> {
                            Toast.makeText(HomeActivity.this, "Caché liberada: " + formatted, Toast.LENGTH_SHORT).show();
                        });
                    } else if (which == 3) {
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
        menuNovelas = findViewById(R.id.menu_novelas);
        menuDoramas = findViewById(R.id.menu_doramas);
        menuAjustes = findViewById(R.id.menu_ajustes);

        if (menuHome == null || menuBuscar == null || menuCanales == null
                || menuNovelas == null) {
            return;
        }

        if (menuDoramas != null) {
            menuDoramas.setVisibility(View.VISIBLE);
            menuDoramas.setOnClickListener(v -> {
                setTvMenuSelected(menuDoramas);
                startActivity(new Intent(HomeActivity.this, sv.edu.catolica.rex.ui.doramas.DoramasActivity.class));
            });
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

        menuNovelas.setOnClickListener(v -> {
            setTvMenuSelected(menuNovelas);
            startActivity(new Intent(HomeActivity.this, NovelasActivity.class));
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

        MediaItem playbackItem = buildContinuePlaybackItem(item, continueItem);
        boolean isNovela = (continueItem.getSource() != null && continueItem.getSource().equalsIgnoreCase("novelas"))
                || (continueItem.getDetailUrl() != null && continueItem.getDetailUrl().contains("canela.tv"))
                || (item != null && item.getFuente() != null && item.getFuente().contains("novelas"));

        if (isNovela) {
            new Thread(() -> {
                List<String> resolved = new ArrayList<>();
                try {
                    sv.edu.catolica.rex.network.smart.CanelaScraper scraper =
                            new sv.edu.catolica.rex.network.smart.CanelaScraper();
                    boolean isSeries = ContinueWatchingStore.isSeries(continueItem);
                    sv.edu.catolica.rex.network.smart.SmartPlaybackResult res = isSeries
                            ? scraper.resolveEpisode(
                                    playbackItem,
                                    continueItem.getSeasonNumber(),
                                    continueItem.getEpisodeNumber(),
                                    continueItem.getEpisodeId()
                            )
                            : scraper.resolveMovie(playbackItem);
                    if (res != null && res.hasPlayableUrls()) {
                        resolved = res.toPlayableUrls();
                    }
                } catch (Exception ignored) {}
                final ArrayList<String> urlsToPass = new ArrayList<>(resolved);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    startContinueWatchingPlayer(playbackItem, continueItem, urlsToPass);
                });
            }).start();
            return;
        }

        SmartScraperEngine resolver = new SmartScraperEngine();
        List<String> cachedUrls = resolver.getCachedUrls(
                this,
                playbackItem,
                continueItem.getSeasonNumber(),
                continueItem.getEpisodeNumber()
        );

        ArrayList<String> urlsToPass = (cachedUrls != null && !cachedUrls.isEmpty())
                ? new ArrayList<>(cachedUrls) : new ArrayList<>();

        startContinueWatchingPlayer(playbackItem, continueItem, urlsToPass);
    }

    private MediaItem buildContinuePlaybackItem(MediaItem item, ContinueWatchingItem continueItem) {
        String title = continueItem.getSeriesTitle() != null && !continueItem.getSeriesTitle().isEmpty()
                ? continueItem.getSeriesTitle()
                : item.getTitulo();
        String detailUrl = continueItem.getDetailUrl() != null && !continueItem.getDetailUrl().isEmpty()
                ? continueItem.getDetailUrl()
                : item.getDetailUrl();
        MediaItem playbackItem = new MediaItem(title, item.getAnio(),
                item.getImagen(), detailUrl, 0);
        String source = continueItem.getSource() != null && !continueItem.getSource().isEmpty()
                ? continueItem.getSource()
                : item.getFuente();
        playbackItem.setFuente(source);
        playbackItem.setMediaType(continueItem.getMediaType());
        playbackItem.setPostId(continueItem.getPostId());
        playbackItem.setTmdbId(continueItem.getTmdbId());
        playbackItem.setImdbId(item.getImdbId());
        playbackItem.setSynopsis(item.getSynopsis());
        return playbackItem;
    }

    private MediaItem buildContinuePlaybackItem(ContinueWatchingItem continueItem) {
        if (continueItem == null) return null;
        String title = continueItem.getSeriesTitle() != null && !continueItem.getSeriesTitle().isEmpty()
                ? continueItem.getSeriesTitle()
                : (continueItem.getTitle() != null ? continueItem.getTitle() : "");
        String detailUrl = continueItem.getDetailUrl() != null ? continueItem.getDetailUrl() : "";
        MediaItem playbackItem = new MediaItem(title, "", continueItem.getImageUrl(), detailUrl, continueItem.getPostId());
        playbackItem.setFuente(continueItem.getSource());
        playbackItem.setMediaType(continueItem.getMediaType());
        playbackItem.setPostId(continueItem.getPostId());
        playbackItem.setTmdbId(continueItem.getTmdbId());
        return playbackItem;
    }

    private void prefetchContinueWatching(List<ContinueWatchingItem> items) {
        if (items == null || items.isEmpty()) return;
        final List<ContinueWatchingItem> snapshot = new ArrayList<>(items);
        prefetchExecutor.execute(() -> {
            for (int i = 0; i < Math.min(3, snapshot.size()); i++) {
                ContinueWatchingItem cw = snapshot.get(i);
                if (cw == null) continue;
                try {
                    MediaItem mi = buildContinuePlaybackItem(cw);
                    if (mi == null) continue;
                    SmartScraperEngine resolver = new SmartScraperEngine();
                    if (resolver.getCachedUrls(HomeActivity.this, mi, cw.getSeasonNumber(), cw.getEpisodeNumber()) == null) {
                        if (isSeriesContinueItem(cw)) {
                            resolver.resolveEpisodeUrls(HomeActivity.this, mi, cw.getSeasonNumber(), cw.getEpisodeNumber(), cw.getEpisodeId());
                        } else {
                            resolver.resolveMovieUrls(HomeActivity.this, mi);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
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

        ArrayList<String> safeUrls = urls != null ? urls : new ArrayList<>();
        Intent intent;
        if (isSeriesContinueItem(continueItem)) {
            intent = PlayerContenidoActivity.createEpisodeIntent(
                    this,
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
            intent = PlayerContenidoActivity.createIntent(this, safeUrls, title);
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
        if (continueItem.getSource() != null && !continueItem.getSource().isEmpty()) {
            intent.putExtra("series_source", continueItem.getSource());
            intent.putExtra("exo_series_source", continueItem.getSource());
        }
        if (continueItem.getDetailUrl() != null && !continueItem.getDetailUrl().isEmpty()) {
            intent.putExtra("series_detail_url", continueItem.getDetailUrl());
            intent.putExtra("exo_series_detail_url", continueItem.getDetailUrl());
        }
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
        if (item.getEpisodeId() > 0 || item.getSeasonNumber() > 0 || item.getEpisodeNumber() > 0) {
            return true;
        }
        String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
        return type.contains("tv")
                || type.contains("series")
                || type.contains("dorama")
                || type.contains("anime");
    }

    private boolean isSameSeriesContinueItem(ContinueWatchingItem a, ContinueWatchingItem b) {
        if (a == null || b == null) return false;
        if (a.getPostId() > 0 && b.getPostId() > 0 && a.getPostId() == b.getPostId()) {
            return true;
        }
        String titleA = a.getSeriesTitle() != null && !a.getSeriesTitle().trim().isEmpty()
                ? a.getSeriesTitle().trim().toLowerCase(Locale.ROOT)
                : (a.getTitle() != null ? a.getTitle().replaceAll("(?i)\\s*-\\s*(?:T|S)\\d+.*", "").trim().toLowerCase(Locale.ROOT) : "");
        String titleB = b.getSeriesTitle() != null && !b.getSeriesTitle().trim().isEmpty()
                ? b.getSeriesTitle().trim().toLowerCase(Locale.ROOT)
                : (b.getTitle() != null ? b.getTitle().replaceAll("(?i)\\s*-\\s*(?:T|S)\\d+.*", "").trim().toLowerCase(Locale.ROOT) : "");
        return !titleA.isEmpty() && !titleB.isEmpty() && titleA.equalsIgnoreCase(titleB);
    }

    private void loadHomeData() { loadHomeData(false); }

    private void loadHomeData(boolean forceRefresh) {
        final List<Section> cached = forceRefresh ? null : getCachedHomeSectionsIfFresh();

        if (cached != null && !cached.isEmpty()) {
            progressBar.setVisibility(ProgressBar.GONE);
            cached.removeIf(s -> "Continuar Viendo".equals(s.getTitle())
                    || "❤️ Mis Favoritos".equals(s.getTitle())
                    || "🕐 Historial".equals(s.getTitle()));
            addUserDataSections(cached);
            Section freshCw = buildContinueWatchingSection();
            if (freshCw != null) {
                cached.add(0, freshCw);
            }
            homeSectionsSnapshot = copySections(cached);
            showSections(cached);
            setupHeroCarousel(extractHeroItems(cached));
            if (hasTmdbHomeSections(cached)) {
                return;
            }
        } else {
            // Mostrar inmediatamente los datos locales sin esperar respuesta de red
            Section initialCw = buildContinueWatchingSection();
            if (initialCw != null) {
                List<Section> initialList = new ArrayList<>();
                initialList.add(initialCw);
                homeSectionsSnapshot = copySections(initialList);
                showSections(initialList);
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
                if (homeSectionsSnapshot.isEmpty()) {
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                }
            } else if (state instanceof HomeUiState.Partial) {
                if (isSearchMode || homeSectionsSnapshot.size() > 1) return;
                List<Section> sections = new ArrayList<>(((HomeUiState.Partial) state).getSections());
                addUserDataSections(sections);
                Section continueSection = buildContinueWatchingSection();
                if (continueSection != null) sections.add(0, continueSection);
                homeSectionsSnapshot = copySections(sections);
                showSections(sections);
                showingPartialHome = true;
                progressBar.setVisibility(ProgressBar.GONE);
            } else if (state instanceof HomeUiState.Success) {
                if (isSearchMode) return;
                if (showingPartialHome && isTv && rvSections.hasFocus()) {
                    pendingTvFocusRestore = true;
                }
                showingPartialHome = false;
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
            if (item == null) continue;
            boolean isCompleted = ContinueWatchingStore.isCompleted(item);
            if (isCompleted) {
                continue;
            }
            if (!isSeriesContinueItem(item) && item.getPositionMs() <= 0 && item.getProgressPercent() <= 0) {
                continue;
            }

            // Si es una serie, solo permitimos un único seguimiento (el último)
            if (isSeriesContinueItem(item)) {
                boolean duplicate = false;
                for (ContinueWatchingItem kept : active) {
                    if (isSameSeriesContinueItem(kept, item)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    continue;
                }
            }

            active.add(item);
        }
        if (active.isEmpty()) return null;

        List<MediaItem> mediaItems = new ArrayList<>();
        for (ContinueWatchingItem cw : active) {
            String cleanTitle = cw.getSeriesTitle() != null && !cw.getSeriesTitle().isEmpty()
                    ? cw.getSeriesTitle() : cw.getTitle();
            if (cleanTitle != null) {
                cleanTitle = cleanTitle.replaceAll("(?i)\\s*-\\s*(?:T|S)\\d+.*", "")
                        .replaceAll("(?i)\\s*(?:T|S)\\d+[:E\\s].*", "").trim();
            }
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

            // Retroalimentación visual precisa estilo Netflix (T1 Episodio 1 • tiempo restante)
            StringBuilder feedback = new StringBuilder();
            if (isSeriesContinueItem(cw) && cw.getEpisodeNumber() > 0) {
                int sNum = cw.getSeasonNumber() > 0 ? cw.getSeasonNumber() : 1;
                feedback.append("T").append(sNum).append(" Episodio ").append(cw.getEpisodeNumber());
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

        prefetchContinueWatching(active);
        return mediaItems.isEmpty() ? null : new Section("Continuar Viendo", mediaItems);
    }

    private void addUserDataSections(List<Section> sections) {
        UserDataManager udm = UserDataManager.getInstance(this);


        List<FavoriteItem> favorites = udm.getFavorites();
        if (favorites != null && !favorites.isEmpty()) {
            List<MediaItem> favItems = new ArrayList<>();
            for (FavoriteItem f : favorites) {
                MediaItem mi = new MediaItem(
                        f.getTitle(),
                        f.getAnio() != null ? f.getAnio() : "",
                        f.getImageUrl(),
                        f.getDetailUrl() != null ? f.getDetailUrl() : "",
                        f.getPostId() > 0 ? f.getPostId() : parsePostId(f.getContentId()),
                        true
                );
                mi.setMediaType(f.getMediaType());
                mi.setBackdrop(f.getBackdrop() != null ? f.getBackdrop() : f.getImageUrl());
                mi.setFuente(f.getFuente());
                mi.setRating(f.getRating());
                mi.setTmdbId(f.getTmdbId());
                mi.setSynopsis(f.getSynopsis());
                favItems.add(mi);
            }
            sections.add(0, new Section("❤️ Mis Favoritos", favItems));
        }
    }

    public void refreshUserDataSections() {
        if (homeSectionsSnapshot.isEmpty()) {
            List<Section> initial = new ArrayList<>();
            addUserDataSections(initial);
            Section continueSection = buildContinueWatchingSection();
            if (continueSection != null) {
                initial.add(0, continueSection);
            }
            if (!initial.isEmpty()) {
                homeSectionsSnapshot = copySections(initial);
                showSections(initial);
            }
            return;
        }
        List<Section> updated = copySections(homeSectionsSnapshot);
        updated.removeIf(s -> "❤️ Mis Favoritos".equals(s.getTitle())
                || "❤️ Mi Lista".equals(s.getTitle())
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
                UnifiedSearchService searchService = UnifiedSearchService.getInstance();
                UnifiedSearchService.SearchResult searchResult = searchService.searchUnified(safeQuery, 30);

                List<Section> sections = new ArrayList<>();
                int topMovieScore = !searchResult.getMovies().isEmpty()
                        ? UnifiedSearchService.computeQueryRelevance(safeQuery, searchResult.getMovies().get(0)) : 0;
                int topSeriesScore = !searchResult.getSeries().isEmpty()
                        ? UnifiedSearchService.computeQueryRelevance(safeQuery, searchResult.getSeries().get(0)) : 0;

                if (topSeriesScore > topMovieScore) {
                    if (!searchResult.getSeries().isEmpty()) {
                        sections.add(new Section("📺 Series", searchResult.getSeries()));
                    }
                    if (!searchResult.getMovies().isEmpty()) {
                        sections.add(new Section("🎬 Películas", searchResult.getMovies()));
                    }
                } else {
                    if (!searchResult.getMovies().isEmpty()) {
                        sections.add(new Section("🎬 Películas", searchResult.getMovies()));
                    }
                    if (!searchResult.getSeries().isEmpty()) {
                        sections.add(new Section("📺 Series", searchResult.getSeries()));
                    }
                }
                if (sections.isEmpty() && !searchResult.getAll().isEmpty()) {
                    sections.add(new Section("🔎 Resultados", searchResult.getAll()));
                }

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode ||
                            !safeQuery.equals(lastSearchQuery)) return;
                    progressBar.setVisibility(ProgressBar.GONE);
                    if (searchResult.isEmpty()) {
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
                for (MediaItem item : searchResult.getAll()) {
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
        if (adapter != null && adapter.dismissOpenOverlays()) {
            return;
        }
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
        if (heroPager != null && heroPager.getAdapter() != null) {
            heroPager.getAdapter().notifyItemChanged(heroPager.getCurrentItem());
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (userDataListener != null) {
            UserDataManager.getInstance(this).removeDataChangeListener(userDataListener);
        }
        try {
            prefetchExecutor.shutdown();
        } catch (Throwable ignored) {}
    }

    private synchronized List<Section> getCachedHomeSectionsIfFresh() {
        if (homeSectionsCache != null && !homeSectionsCache.isEmpty()) {
            if (System.currentTimeMillis() - homeCacheSavedAtMs <= HOME_CACHE_TTL_MS) {
                return copySections(homeSectionsCache);
            }
            homeSectionsCache = null;
            homeCacheSavedAtMs = 0;
        }

        try {
            android.content.SharedPreferences prefs =
                    getApplicationContext().getSharedPreferences(PREF_HOME_CACHE, Context.MODE_PRIVATE);
            long savedAt = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L);
            if (savedAt > 0 && (System.currentTimeMillis() - savedAt <= HOME_CACHE_TTL_MS)) {
                String json = prefs.getString(KEY_SECTIONS_JSON, null);
                if (json != null && !json.isEmpty()) {
                    java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<Section>>() {}.getType();
                    List<Section> diskSections = new com.google.gson.Gson().fromJson(json, type);
                    if (diskSections != null && !diskSections.isEmpty()) {
                        homeSectionsCache = copySections(diskSections);
                        homeCacheSavedAtMs = savedAt;
                        return copySections(diskSections);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private synchronized void cacheHomeSections(List<Section> sections) {
        if (sections == null || sections.isEmpty()) return;
        homeSectionsCache  = copySections(sections);
        homeCacheSavedAtMs = System.currentTimeMillis();

        List<Section> toPersist = copySections(sections);
        toPersist.removeIf(s -> "Continuar Viendo".equals(s.getTitle())
                || "❤️ Mis Favoritos".equals(s.getTitle())
                || "🕐 Historial".equals(s.getTitle()));
        if (!toPersist.isEmpty()) {
            final long now = homeCacheSavedAtMs;
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    String json = new com.google.gson.Gson().toJson(toPersist);
                    getApplicationContext().getSharedPreferences(PREF_HOME_CACHE, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_SECTIONS_JSON, json)
                            .putLong(KEY_CACHE_TIMESTAMP, now)
                            .apply();
                } catch (Exception ignored) {
                }
            });
        }
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
            if (title.contains("populares tmdb")) {
                hasPopularTmdb = true;
            } else if (title.contains("tendencias")) {
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
        if (heroContainer == null || heroPager == null) return;
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
        if (heroContainer == null || heroPager == null) return;
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
        if (isTv) {
            if (tvNestedScroll != null) {
                tvNestedScroll.scrollTo(0, 0);
            }
            heroContainer.post(() -> {
                if (!pendingTvFocusRestore && lastFocusedSectionPos <= 0) {
                    focusHeroPlayButton();
                }
            });
        }
        heroPager.setAdapter(new HeroPagerAdapter(heroItems, new HeroPagerAdapter.Listener() {
            @Override public void onPlay(MediaItem item) { openDetail(item); }
            @Override public void onInfo(MediaItem item) { openDetail(item); }
            @Override
            public void onToggleMyList(MediaItem item, int position) {
                if (item == null) return;
                UserDataManager udm = UserDataManager.getInstance(HomeActivity.this);
                boolean wasFav = udm.isFavorite(item);
                if (wasFav) {
                    udm.removeFromFavorites(item);
                    Toast.makeText(HomeActivity.this, "Eliminado de Mi Lista", Toast.LENGTH_SHORT).show();
                } else {
                    udm.addToFavorites(item);
                    Toast.makeText(HomeActivity.this, "Añadido a Mi Lista", Toast.LENGTH_SHORT).show();
                }
            }
        }, isTv));
        heroPager.setOffscreenPageLimit(1);
        heroPager.setPageTransformer((page, position) ->
                page.setScaleY(1f - Math.min(0.04f, Math.abs(position) * 0.04f)));

        heroDots.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < heroItems.size(); i++) {
            ImageView dot = new ImageView(this);
            if (isTv) {
                dot.setImageResource(i == 0 ? R.drawable.bg_hero_dot_active_hbomax : R.drawable.bg_hero_dot_inactive_hbomax);
                android.widget.LinearLayout.LayoutParams params =
                        new android.widget.LinearLayout.LayoutParams(
                                i == 0 ? (int)(22 * density) : (int)(6 * density),
                                (int)(6 * density));
                params.setMargins((int)(4 * density), 0, (int)(4 * density), 0);
                heroDots.addView(dot, params);
            } else {
                dot.setImageResource(i == 0 ? R.drawable.dot_active : R.drawable.dot_inactive);
                android.widget.LinearLayout.LayoutParams params =
                        new android.widget.LinearLayout.LayoutParams(7, 7);
                params.setMargins(4, 0, 4, 0);
                heroDots.addView(dot, params);
            }
        }
        if (heroPageCallback != null) {
            heroPager.unregisterOnPageChangeCallback(heroPageCallback);
        }
        heroPageCallback = new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                for (int i = 0; i < heroDots.getChildCount(); i++) {
                    ImageView dotView = (ImageView) heroDots.getChildAt(i);
                    if (isTv) {
                        dotView.setImageResource(i == position ? R.drawable.bg_hero_dot_active_hbomax : R.drawable.bg_hero_dot_inactive_hbomax);
                        android.view.ViewGroup.LayoutParams lp = dotView.getLayoutParams();
                        lp.width = (int) ((i == position ? 22 : 6) * density);
                        dotView.setLayoutParams(lp);
                    } else {
                        dotView.setImageResource(
                                i == position ? R.drawable.dot_active : R.drawable.dot_inactive);
                    }
                }

                if (isTv && heroContainer != null && heroContainer.getVisibility() == View.VISIBLE) {
                    if (heroContainer.hasFocus()) {
                        focusHeroPlayButton();
                        heroContainer.post(() -> focusHeroPlayButton());
                        heroContainer.postDelayed(() -> focusHeroPlayButton(), 120L);
                    }
                }
            }

            @Override public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_IDLE && isTv && heroContainer != null && heroContainer.getVisibility() == View.VISIBLE) {
                    if (heroContainer.hasFocus()) {
                        View play = getCurrentHeroPlayButton();
                        if (play != null && !play.hasFocus()) {
                            play.requestFocus();
                        }
                    }
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
            boolean playable = c.getPostId() > 0 || c.getTmdbId() > 0
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
                        if (item.getTmdbId() <= 0) item.setTmdbId(found.id);
                        if (found.detailUrl != null && !found.detailUrl.isEmpty()) item.setDetailUrl(found.detailUrl);
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
        if (isTv) {
            rvSections.setPadding(0, 0, 0, (int) (140f * density));
            return;
        }
        int heroHeightDp = 420;
        int top = heroVisible ? (int) (heroHeightDp * density) : (int) (24f * density);
        int bottom = (int) (100f * density);
        rvSections.setPadding(0, top, 0, bottom);
    }

    private int currentTvScrolledSection = -1;

    public void onHeroFocused() {
        lastFocusedSectionPos = -1;
        lastFocusedItemPos = 0;
        currentTvScrolledSection = -1;
    }

    public void scrollTvToSection(int sectionPosition) {
        if (!isTv || tvNestedScroll == null || rvSections == null) return;
        if (currentTvScrolledSection == sectionPosition) return;
        currentTvScrolledSection = sectionPosition;
        rvSections.post(() -> {
            RecyclerView.ViewHolder holder = rvSections.findViewHolderForAdapterPosition(sectionPosition);
            float density = getResources().getDisplayMetrics().density;
            if (holder != null && holder.itemView != null) {
                int targetY = rvSections.getTop() + holder.itemView.getTop();
                int scrollY = Math.max(0, targetY - (int) (70 * density));
                tvNestedScroll.smoothScrollTo(0, scrollY);
            } else {
                int heroH = (heroContainer != null && heroContainer.getVisibility() == View.VISIBLE)
                        ? heroContainer.getHeight() : 0;
                if (heroH <= 0 && heroContainer != null && heroContainer.getVisibility() == View.VISIBLE) {
                    heroH = (int) (460 * density);
                }
                int estimatedRowH = (int) (280 * density);
                int targetY = heroH + (sectionPosition * estimatedRowH);
                int scrollY = Math.max(0, targetY - (int) (70 * density));
                tvNestedScroll.smoothScrollTo(0, scrollY);
                rvSections.scrollToPosition(sectionPosition);
            }
        });
    }

    public View getCurrentHeroPlayButton() {
        if (heroPager == null) return null;
        int current = heroPager.getCurrentItem();
        View internalRv = heroPager.getChildAt(0);
        if (internalRv instanceof RecyclerView) {
            RecyclerView.ViewHolder vh = ((RecyclerView) internalRv).findViewHolderForAdapterPosition(current);
            if (vh != null && vh.itemView != null) {
                View play = vh.itemView.findViewById(R.id.btn_hero_play);
                if (play != null) return play;
            }
        }
        return heroPager.findViewById(R.id.btn_hero_play);
    }

    public boolean focusHeroPlayButton() {
        if (!isTv || heroContainer == null || heroContainer.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (tvNestedScroll != null) {
            tvNestedScroll.scrollTo(0, 0);
        }
        onHeroFocused();
        View play = getCurrentHeroPlayButton();
        if (play != null) {
            play.setFocusable(true);
            play.setFocusableInTouchMode(true);
            play.requestFocus();
            return true;
        }
        heroContainer.post(() -> {
            View p = getCurrentHeroPlayButton();
            if (p != null) {
                p.requestFocus();
            }
        });
        return true;
    }

    public boolean focusFirstSectionFromHero() {
        if (!isTv || rvSections == null || adapter == null || adapter.getItemCount() == 0) {
            return false;
        }
        scrollTvToSection(0);
        adapter.requestFocusAt(0, 0);
        return true;
    }

    public boolean focusSidebarFromHero() {
        if (!isTv) return false;
        lastFocusedSectionPos = 0;
        if (menuHome != null && menuHome.isShown()) {
            menuHome.requestFocus();
            return true;
        }
        return false;
    }

    private void startHeroAutoScroll(int itemCount) {
        stopHeroAutoScroll();
        if (itemCount < 2) return;
        heroAutoScroll = new Runnable() {
            @Override public void run() {
                if (heroPager == null || heroPager.getAdapter() == null) return;
                boolean heroActive = isTv && (heroContainer != null && heroContainer.hasFocus());
                int next = (heroPager.getCurrentItem() + 1) % itemCount;
                heroPager.setCurrentItem(next, true);
                if (heroActive && heroContainer != null) {
                    heroContainer.postDelayed(() -> focusHeroPlayButton(), 120L);
                }
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
        if (lastFocusedSectionPos == -1) {
            focusHeroPlayButton();
            return;
        }
        scrollTvToSection(lastFocusedSectionPos);
        rvSections.post(() -> adapter.requestFocusAt(lastFocusedSectionPos, lastFocusedItemPos));
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
                if ("Continuar Viendo".equals(section.getTitle())
                        || "❤️ Mis Favoritos".equals(section.getTitle())
                        || "🕐 Historial".equals(section.getTitle())) {
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
            if ("Continuar Viendo".equals(section.getTitle())
                    || "❤️ Mis Favoritos".equals(section.getTitle())
                    || "🕐 Historial".equals(section.getTitle())) {
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
            if ("Continuar Viendo".equals(section.getTitle())
                    || "❤️ Mis Favoritos".equals(section.getTitle())
                    || "🕐 Historial".equals(section.getTitle())) {
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
