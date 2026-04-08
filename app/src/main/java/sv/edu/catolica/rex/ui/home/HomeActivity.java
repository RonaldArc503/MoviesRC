package sv.edu.catolica.rex.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private RecyclerView rvSections;
    private ProgressBar progressBar;
    private SearchView searchView;
    private HomeAdapter adapter;

    private boolean isSearchMode = false;
    private boolean suppressQueryListener = false;
    private String lastSearchQuery = "";
    private int activeRequestId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        rvSections = findViewById(R.id.rv_home_sections);
        progressBar = findViewById(R.id.progressBar);
        searchView = findViewById(R.id.search_view);

        rvSections.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HomeAdapter(this, new ArrayList<>(), item -> {
            DetalleContenidoActivity.start(HomeActivity.this, item);
        });
        rvSections.setAdapter(adapter);

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                performSearch(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (suppressQueryListener) {
                    return false;
                }

                String text = newText == null ? "" : newText.trim();
                if (text.isEmpty() && isSearchMode) {
                    exitSearchModeAndRestoreHome();
                    return true;
                }
                return false;
            }
        });

        searchView.setOnCloseListener(() -> {
            if (isSearchMode) {
                exitSearchModeAndRestoreHome();
                return true;
            }
            return false;
        });

        loadHomeData();
    }

    private void loadHomeData() {
        loadHomeData(false);
    }

    private void loadHomeData(boolean forceRefresh) {
        final int requestId = nextRequestId();
        final List<Section> cachedSections = forceRefresh ? null : getCachedHomeSectionsIfFresh();

        if (cachedSections != null && !cachedSections.isEmpty()) {
            progressBar.setVisibility(ProgressBar.GONE);
            showSections(cachedSections);
            return;
        }

        progressBar.setVisibility(ProgressBar.VISIBLE);
        new Thread(() -> {
            try {
                List<AllCalidadScraper.ContentItem> featured = AllCalidadScraper.getFeaturedMovies();
                List<AllCalidadScraper.ContentItem> popular = AllCalidadScraper.getPopularMovies();
                List<AllCalidadScraper.ContentItem> latest = AllCalidadScraper.getMovies(1);
                List<AllCalidadScraper.ContentItem> series = AllCalidadScraper.getTvShows(1);
                List<AllCalidadScraper.ContentItem> popularSeries = AllCalidadScraper.getPopularTvShows();

                List<MediaItem> featuredItems = mapToMediaItems(featured, 10);
                List<MediaItem> popularItems = mapToMediaItems(popular, 16);
                List<MediaItem> latestItems = mapToMediaItems(latest, 16);
                List<MediaItem> seriesItems = mapToMediaItems(series, 16);
                List<MediaItem> popularSeriesItems = mapToMediaItems(popularSeries, 16);

                List<MediaItem> allItems = new ArrayList<>();
                allItems.addAll(featuredItems);
                allItems.addAll(popularItems);
                allItems.addAll(latestItems);
                allItems.addAll(seriesItems);
                allItems.addAll(popularSeriesItems);
                TmdbService.enrichMediaItems(allItems);

                List<Section> sections = new ArrayList<>();
                sections.add(new Section("🔥 Destacadas", featuredItems.isEmpty() ? latestItems : featuredItems));
                sections.add(new Section("📈 Populares del Mes", popularItems.isEmpty() ? latestItems : popularItems));
                sections.add(new Section("🎬 Últimas Películas", latestItems));
                if (!seriesItems.isEmpty()) {
                    sections.add(new Section("📺 Series", seriesItems));
                }
                if (!popularSeriesItems.isEmpty()) {
                    sections.add(new Section("⭐ Series Populares", popularSeriesItems));
                }

                cacheHomeSections(sections);
                
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || isSearchMode) {
                        return;
                    }
                    showSections(sections);
                    progressBar.setVisibility(ProgressBar.GONE);
                });
                
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId)) {
                        return;
                    }
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(HomeActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private List<MediaItem> mapToMediaItems(List<AllCalidadScraper.ContentItem> items, int limit) {
        List<MediaItem> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        int max = Math.min(limit, items.size());
        for (int i = 0; i < max; i++) {
            result.add(AllCalidadScraper.toMediaItem(items.get(i)));
        }
        return result;
    }

    private void performSearch(String query) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isEmpty()) {
            exitSearchModeAndRestoreHome();
            return;
        }

        isSearchMode = true;
        lastSearchQuery = safeQuery;
        final int requestId = nextRequestId();

        progressBar.setVisibility(ProgressBar.VISIBLE);
        searchView.clearFocus();
        showSections(new ArrayList<>());

        new Thread(() -> {
            try {
                List<AllCalidadScraper.ContentItem> results = AllCalidadScraper.search(safeQuery, 1);
                List<MediaItem> mappedResults = mapToMediaItems(results, 40);
                TmdbService.enrichMediaItems(mappedResults);

                List<MediaItem> movieResults = new ArrayList<>();
                List<MediaItem> seriesResults = new ArrayList<>();
                for (MediaItem item : mappedResults) {
                    String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
                    if ("tvshows".equals(type) || "animes".equals(type)) {
                        seriesResults.add(item);
                    } else {
                        movieResults.add(item);
                    }
                }

                List<Section> sections = new ArrayList<>();
                if (!movieResults.isEmpty()) {
                    sections.add(new Section("🎬 Películas", movieResults));
                }
                if (!seriesResults.isEmpty()) {
                    sections.add(new Section("📺 Series", seriesResults));
                }
                if (sections.isEmpty()) {
                    sections.add(new Section("🔎 Resultados", mappedResults));
                }

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode || !safeQuery.equals(lastSearchQuery)) {
                        return;
                    }

                    progressBar.setVisibility(ProgressBar.GONE);
                    if (mappedResults.isEmpty()) {
                        showSections(new ArrayList<>());
                        Toast.makeText(HomeActivity.this, "Sin resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showSections(sections);
                    Toast.makeText(HomeActivity.this, "Resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !isSearchMode || !safeQuery.equals(lastSearchQuery)) {
                        return;
                    }
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(HomeActivity.this, "Error en búsqueda: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void exitSearchModeAndRestoreHome() {
        isSearchMode = false;
        lastSearchQuery = "";

        suppressQueryListener = true;
        searchView.setQuery("", false);
        suppressQueryListener = false;
        searchView.clearFocus();

        loadHomeData(false);
    }

    private int nextRequestId() {
        activeRequestId++;
        return activeRequestId;
    }

    private boolean isRequestActive(int requestId) {
        return requestId == activeRequestId;
    }

    @Override
    public void onBackPressed() {
        CharSequence queryText = searchView != null ? searchView.getQuery() : "";
        boolean hasQuery = queryText != null && queryText.toString().trim().length() > 0;

        if (isSearchMode || hasQuery) {
            exitSearchModeAndRestoreHome();
            return;
        }
        super.onBackPressed();
    }

    private static synchronized List<Section> getCachedHomeSectionsIfFresh() {
        if (homeSectionsCache == null || homeSectionsCache.isEmpty()) {
            return null;
        }

        long ageMs = System.currentTimeMillis() - homeCacheSavedAtMs;
        if (ageMs > HOME_CACHE_TTL_MS) {
            homeSectionsCache = null;
            homeCacheSavedAtMs = 0L;
            return null;
        }

        return copySections(homeSectionsCache);
    }

    private static synchronized void cacheHomeSections(List<Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return;
        }

        homeSectionsCache = copySections(sections);
        homeCacheSavedAtMs = System.currentTimeMillis();
    }

    private static List<Section> copySections(List<Section> source) {
        List<Section> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }

        for (Section section : source) {
            if (section == null) {
                continue;
            }
            String title = section.getTitle() == null ? "" : section.getTitle();
            copy.add(new Section(title, copyMediaItems(section.getItems())));
        }
        return copy;
    }

    private static List<MediaItem> copyMediaItems(List<MediaItem> items) {
        List<MediaItem> copy = new ArrayList<>();
        if (items == null) {
            return copy;
        }

        for (MediaItem item : items) {
            if (item == null) {
                continue;
            }

            MediaItem cloned = new MediaItem(
                    item.getTitulo(),
                    item.getAnio(),
                    item.getImagen(),
                    item.getDetailUrl(),
                    0
            );
            cloned.setFuente(item.getFuente());
            cloned.setMediaType(item.getMediaType());
            cloned.setPostId(item.getPostId());
            cloned.setSynopsis(item.getSynopsis());
            cloned.setDublado(item.isDublado());
            copy.add(cloned);
        }

        return copy;
    }

    private void showSections(List<Section> sections) {
        adapter = new HomeAdapter(this, sections, item -> {
            DetalleContenidoActivity.start(HomeActivity.this, item);
        });
        rvSections.setAdapter(adapter);
    }
}
