package sv.edu.catolica.rex.ui.home;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;
import sv.edu.catolica.rex.ui.detalle.DetalleContenidoActivity;

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

    private boolean isSearchMode          = false;
    private boolean suppressQueryListener = false;
    private String  lastSearchQuery       = "";
    private int     activeRequestId       = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        isTv = isTvDevice();

        rvSections  = findViewById(R.id.rv_home_sections);
        progressBar = findViewById(R.id.progressBar);
        searchView  = findViewById(R.id.search_view);

        rvSections.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HomeAdapter(this, new ArrayList<>(),
                item -> DetalleContenidoActivity.start(HomeActivity.this, item), isTv);
        rvSections.setAdapter(adapter);

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

        loadHomeData(false);
    }

    private boolean isTvDevice() {
        UiModeManager mgr = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        return mgr != null && mgr.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void loadHomeData() { loadHomeData(false); }

    private void loadHomeData(boolean forceRefresh) {
        final int requestId = nextRequestId();
        final List<Section> cached = forceRefresh ? null : getCachedHomeSectionsIfFresh();

        if (cached != null && !cached.isEmpty()) {
            progressBar.setVisibility(ProgressBar.GONE);
            showSections(cached);
            return;
        }

        progressBar.setVisibility(ProgressBar.VISIBLE);

        new Thread(() -> {
            try {
                // Lanzar las 5 peticiones EN PARALELO pero escalonadas ~120ms
                // para no parecer un burst de bot al mismo host
                ExecutorService pool = Executors.newFixedThreadPool(5);

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
                pool.shutdown();

                // Recolectar (ya están corriendo en paralelo)
                List<AllCalidadScraper.ContentItem> featured      = safeGet(featuredFuture);
                List<AllCalidadScraper.ContentItem> popular       = safeGet(popularFuture);
                List<AllCalidadScraper.ContentItem> latest        = safeGet(latestFuture);
                List<AllCalidadScraper.ContentItem> series        = safeGet(seriesFuture);
                List<AllCalidadScraper.ContentItem> popularSeries = safeGet(popularSeriesFuture);

                List<MediaItem> featuredItems      = mapItems(featured,      10);
                List<MediaItem> popularItems       = mapItems(popular,       16);
                List<MediaItem> latestItems        = mapItems(latest,        16);
                List<MediaItem> seriesItems        = mapItems(series,        16);
                List<MediaItem> popularSeriesItems = mapItems(popularSeries, 16);

                // Mostrar secciones SIN esperar TMDB
                final List<Section> sections = buildSections(
                        featuredItems, popularItems, latestItems,
                        seriesItems, popularSeriesItems);
                cacheHomeSections(sections);

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || isSearchMode) return;
                    showSections(sections);
                    progressBar.setVisibility(ProgressBar.GONE);
                });

                // TMDB en background (paralelo, no bloquea la UI)
                List<MediaItem> allItems = new ArrayList<>();
                allItems.addAll(featuredItems); allItems.addAll(popularItems);
                allItems.addAll(latestItems);   allItems.addAll(seriesItems);
                allItems.addAll(popularSeriesItems);
                TmdbService.enrichMediaItemsParallel(allItems);

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || isSearchMode) return;
                    if (adapter != null) adapter.notifySectionsChanged();
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

    private List<Section> buildSections(
            List<MediaItem> fi, List<MediaItem> pi,
            List<MediaItem> li, List<MediaItem> si, List<MediaItem> psi) {
        List<Section> s = new ArrayList<>();
        s.add(new Section("🔥 Destacadas",       fi.isEmpty() ? li : fi));
        s.add(new Section("📈 Populares del Mes", pi.isEmpty() ? li : pi));
        s.add(new Section("🎬 Últimas Películas", li));
        if (!si.isEmpty())  s.add(new Section("📺 Series",          si));
        if (!psi.isEmpty()) s.add(new Section("⭐ Series Populares", psi));
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

                List<MediaItem> movies = new ArrayList<>(), series = new ArrayList<>();
                for (MediaItem item : mapped) {
                    String type = item.getMediaType() == null ? "" :
                            item.getMediaType().toLowerCase(Locale.ROOT);
                    if ("tvshows".equals(type) || "animes".equals(type)) series.add(item);
                    else movies.add(item);
                }

                List<Section> sections = new ArrayList<>();
                if (!movies.isEmpty())  sections.add(new Section("🎬 Películas", movies));
                if (!series.isEmpty())  sections.add(new Section("📺 Series",    series));
                if (sections.isEmpty()) sections.add(new Section("🔎 Resultados", mapped));

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode ||
                            !safeQuery.equals(lastSearchQuery)) return;
                    progressBar.setVisibility(ProgressBar.GONE);
                    if (mapped.isEmpty()) {
                        showSections(new ArrayList<>());
                        Toast.makeText(HomeActivity.this,
                                "Sin resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showSections(sections);
                    Toast.makeText(HomeActivity.this,
                            "Resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                });

                TmdbService.enrichMediaItemsParallel(mapped);
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode) return;
                    if (adapter != null) adapter.notifySectionsChanged();
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

    @Override
    public void onBackPressed() {
        CharSequence q = searchView != null ? searchView.getQuery() : "";
        if (isSearchMode || (q != null && q.toString().trim().length() > 0)) {
            exitSearchModeAndRestoreHome(); return;
        }
        super.onBackPressed();
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
            c.setFuente(item.getFuente());
            c.setMediaType(item.getMediaType());
            c.setPostId(item.getPostId());
            c.setSynopsis(item.getSynopsis());
            c.setDublado(item.isDublado());
            copy.add(c);
        }
        return copy;
    }

    private void showSections(List<Section> sections) {
        adapter = new HomeAdapter(this, sections,
                item -> DetalleContenidoActivity.start(HomeActivity.this, item), isTv);
        rvSections.setAdapter(adapter);
    }
}