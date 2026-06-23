package sv.edu.catolica.rex.ui.home;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
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
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;
import sv.edu.catolica.rex.ui.detalle.DetalleActivity;

public class SearchActivity extends AppCompatActivity {

    private RecyclerView rvSections;
    private ProgressBar progressBar;
    private SearchView searchView;
    private HomeAdapter adapter;
    private boolean isTv;

    private boolean suppressQueryListener = false;
    private String lastSearchQuery = "";
    private int activeRequestId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = isTvDevice();

        if (isTv) {
            setContentView(R.layout.activity_tv_search);
        } else {
            setContentView(R.layout.activity_search);
        }

        rvSections = findViewById(R.id.rv_home_sections);
        progressBar = findViewById(R.id.progressBar);
        searchView = findViewById(R.id.search_view);

        rvSections.setLayoutManager(new LinearLayoutManager(this));
        rvSections.setHasFixedSize(true);
        rvSections.setItemAnimator(null);
        adapter = new HomeAdapter(this, new ArrayList<>(),
                item -> DetalleActivity.start(SearchActivity.this, item), isTv);
        rvSections.setAdapter(adapter);

        View backButton = findViewById(R.id.btn_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        setupSearchBehavior();
    }

    private boolean isTvDevice() {
        UiModeManager mgr = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        return mgr != null && mgr.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void setupSearchBehavior() {
        if (searchView == null) {
            return;
        }

        searchView.setIconified(false);
        searchView.requestFocus();
        activateSearchInput();

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) {
                performSearch(query);
                return true;
            }

            @Override public boolean onQueryTextChange(String newText) {
                if (suppressQueryListener) {
                    return false;
                }
                String text = newText == null ? "" : newText.trim();
                if (text.isEmpty()) {
                    clearResults();
                }
                return false;
            }
        });

        searchView.setOnCloseListener(() -> {
            clearResults();
            return false;
        });

        if (isTv) {
            searchView.setOnClickListener(v -> activateSearchInput());
            searchView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    activateSearchInput();
                    return true;
                }
                return false;
            });
        }
    }

    private void activateSearchInput() {
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

    private void performSearch(String query) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isEmpty()) {
            clearResults();
            return;
        }

        lastSearchQuery = safeQuery;
        final int requestId = nextRequestId();

        progressBar.setVisibility(ProgressBar.VISIBLE);
        showSections(new ArrayList<>());
        searchView.clearFocus();

        new Thread(() -> {
            try {
                List<AllCalidadScraper.ContentItem> results = AllCalidadScraper.search(safeQuery, 1);
                List<MediaItem> mapped = mapItems(results, 40);
                List<MediaItem> tmdbMapped = TmdbService.searchMedia(safeQuery, 40);
                List<MediaItem> merged = mergeSearchResults(mapped, tmdbMapped, 60);

                List<MediaItem> movies = new ArrayList<>();
                List<MediaItem> series = new ArrayList<>();
                for (MediaItem item : merged) {
                    String type = item.getMediaType() == null ? ""
                            : item.getMediaType().toLowerCase(Locale.ROOT);
                    if ("tvshows".equals(type) || "animes".equals(type)) {
                        series.add(item);
                    } else {
                        movies.add(item);
                    }
                }

                List<Section> sections = new ArrayList<>();
                if (!movies.isEmpty()) {
                    sections.add(new Section("Peliculas", movies));
                }
                if (!series.isEmpty()) {
                    sections.add(new Section("Series", series));
                }
                if (sections.isEmpty()) {
                    sections.add(new Section("Resultados", merged));
                }

                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !safeQuery.equals(lastSearchQuery)) {
                        return;
                    }
                    progressBar.setVisibility(ProgressBar.GONE);
                    if (merged.isEmpty()) {
                        showSections(new ArrayList<>());
                        Toast.makeText(SearchActivity.this,
                                "Sin resultados para: " + safeQuery, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showSections(sections);
                });

                List<MediaItem> enrichCandidates = new ArrayList<>();
                for (MediaItem item : merged) {
                    if (item == null) {
                        continue;
                    }
                    String source = item.getFuente() == null ? ""
                            : item.getFuente().trim().toLowerCase(Locale.ROOT);
                    if ("tmdb".equals(source) && item.getTmdbId() > 0) {
                        continue;
                    }
                    enrichCandidates.add(item);
                }
                TmdbService.enrichMediaItemsParallel(enrichCandidates);
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !safeQuery.equals(lastSearchQuery)) {
                        return;
                    }
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isRequestActive(requestId) || !safeQuery.equals(lastSearchQuery)) {
                        return;
                    }
                    progressBar.setVisibility(ProgressBar.GONE);
                    Toast.makeText(SearchActivity.this,
                            "Error en busqueda: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void clearResults() {
        progressBar.setVisibility(ProgressBar.GONE);
        lastSearchQuery = "";
        nextRequestId();
        showSections(new ArrayList<>());
    }

    private int nextRequestId() {
        return ++activeRequestId;
    }

    private boolean isRequestActive(int id) {
        return id == activeRequestId;
    }

    private List<MediaItem> mapItems(List<AllCalidadScraper.ContentItem> items, int limit) {
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

    private void showSections(List<Section> sections) {
        if (adapter == null) {
            adapter = new HomeAdapter(this, sections,
                    item -> DetalleActivity.start(SearchActivity.this, item), isTv);
            rvSections.setAdapter(adapter);
            return;
        }
        adapter.setSections(sections);
    }

    @Override
    public void onBackPressed() {
        CharSequence q = searchView != null ? searchView.getQuery() : "";
        if (q != null && q.toString().trim().length() > 0) {
            suppressQueryListener = true;
            searchView.setQuery("", false);
            suppressQueryListener = false;
            clearResults();
            return;
        }
        super.onBackPressed();
    }
}
