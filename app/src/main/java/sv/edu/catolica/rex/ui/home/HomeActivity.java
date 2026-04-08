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

    private RecyclerView rvSections;
    private ProgressBar progressBar;
    private SearchView searchView;
    private HomeAdapter adapter;

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
            public boolean onQueryTextChange(String newText) { return false; }
        });

        loadHomeData();
    }

    private void loadHomeData() {
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
                
                runOnUiThread(() -> {
                    showSections(sections);
                    progressBar.setVisibility(ProgressBar.GONE);
                });
                
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
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
            loadHomeData();
            return;
        }

        progressBar.setVisibility(ProgressBar.VISIBLE);
        searchView.clearFocus();

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
                    progressBar.setVisibility(ProgressBar.GONE);
                    if (mappedResults.isEmpty()) {
                        Toast.makeText(HomeActivity.this, "Sin resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showSections(sections);
                    Toast.makeText(HomeActivity.this, "Resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(HomeActivity.this, "Error en búsqueda: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showSections(List<Section> sections) {
        adapter = new HomeAdapter(this, sections, item -> {
            DetalleContenidoActivity.start(HomeActivity.this, item);
        });
        rvSections.setAdapter(adapter);
    }
}
