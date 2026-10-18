package sv.edu.catolica.rex.ui.doramas;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.ui.auth.AuthManager;
import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.models.Section;
import sv.edu.catolica.rex.repository.UserDataManager;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;
import sv.edu.catolica.rex.ui.detalle.DetalleActivity;
import sv.edu.catolica.rex.ui.home.HeroPagerAdapter;
import sv.edu.catolica.rex.ui.home.HomeActivity;
import sv.edu.catolica.rex.ui.home.HomeAdapter;
import sv.edu.catolica.rex.ui.home.SearchActivity;
import sv.edu.catolica.rex.ui.home.TvChannelsActivity;
import sv.edu.catolica.rex.ui.novelas.NovelasActivity;

public class DoramasActivity extends AppCompatActivity {

    private static final String TAG = "DoramasActivity";

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

    private float heroScrollOffset = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = isTelevision();
        enterImmersiveMode();

        setContentView(R.layout.activity_doramas);

        rvSections = findViewById(R.id.rv_doramas_sections);
        progressBar = findViewById(R.id.progressBar);
        heroContainer = findViewById(R.id.hero_container);
        heroPager = findViewById(R.id.vp_hero);
        heroDots = findViewById(R.id.dots_container);
        topHeader = findViewById(R.id.top_header);

        setupHeaderAndNavigation();
        setupRecyclerView();

        loadDoramasData();
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
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void setupHeaderAndNavigation() {
        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        ImageButton btnSearch = findViewById(R.id.btn_search);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> startActivity(new Intent(DoramasActivity.this, SearchActivity.class)));
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
        View menuDoramas = findViewById(R.id.menu_doramas);
        View menuAjustes = findViewById(R.id.menu_ajustes);

        if (menuHome != null) {
            menuHome.setOnClickListener(v -> {
                Intent intent = new Intent(DoramasActivity.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            });
        }

        if (menuBuscar != null) {
            menuBuscar.setOnClickListener(v -> startActivity(new Intent(DoramasActivity.this, SearchActivity.class)));
        }

        if (menuCanales != null) {
            menuCanales.setOnClickListener(v -> {
                startActivity(new Intent(DoramasActivity.this, TvChannelsActivity.class));
                finish();
            });
        }

        if (menuNovelas != null) {
            menuNovelas.setOnClickListener(v -> {
                startActivity(new Intent(DoramasActivity.this, NovelasActivity.class));
                finish();
            });
        }

        if (menuDoramas != null) {
            menuDoramas.setOnClickListener(v -> {
                if (rvSections != null) {
                    rvSections.smoothScrollToPosition(0);
                }
            });
        }

        if (menuAjustes != null) {
            menuAjustes.setOnClickListener(v -> showSettingsDialog());
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
                Intent intent = new Intent(DoramasActivity.this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            });
        }

        if (menuBuscar != null) {
            menuBuscar.setOnClickListener(v -> startActivity(new Intent(DoramasActivity.this, SearchActivity.class)));
        }

        if (menuCanales != null) {
            menuCanales.setOnClickListener(v -> {
                startActivity(new Intent(DoramasActivity.this, TvChannelsActivity.class));
                finish();
            });
        }

        if (menuNovelas != null) {
            menuNovelas.setOnClickListener(v -> {
                startActivity(new Intent(DoramasActivity.this, NovelasActivity.class));
                finish();
            });
        }

        if (menuDoramas != null) {
            menuDoramas.setOnClickListener(v -> {
                scrollTvToSection(0);
                if (adapter != null && adapter.getItemCount() > 0) {
                    adapter.requestFocusAt(0, 0);
                }
            });
        }

        if (menuAjustes != null) {
            menuAjustes.setOnClickListener(v -> showSettingsDialog());
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

    private void scrollTvToSection(int sectionPos) {
        if (rvSections == null || rvSections.getLayoutManager() == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvSections.getLayoutManager();
        lm.scrollToPositionWithOffset(Math.max(0, sectionPos), 20);
    }

    private void setupRecyclerView() {
        LinearLayoutManager lm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        rvSections.setLayoutManager(lm);

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

    private void loadDoramasData() {
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }

        new Thread(() -> {
            List<MediaItem> allDoramas = loadDoramasFromAssets();

            // Si por alguna razón el archivo local falla, fallback a Firebase RTDB
            if (allDoramas.isEmpty()) {
                allDoramas = loadDoramasFromFirebase();
            }

            final List<MediaItem> items = allDoramas;
            final List<Section> sections = buildSections(items);
            final List<MediaItem> heroItems = selectHeroItems(items);

            runOnUiThread(() -> {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }

                if (!heroItems.isEmpty() && !isTv) {
                    setupHeroCarousel(heroItems);
                } else {
                    hideHeroCarousel();
                }

                if (!sections.isEmpty()) {
                    adapter.setSections(sections);
                } else {
                    Toast.makeText(DoramasActivity.this, "No se encontraron doramas disponibles", Toast.LENGTH_SHORT).show();
                }
            });
        }, "doramas-loader-thread").start();
    }

    private List<MediaItem> loadDoramasFromAssets() {
        List<MediaItem> list = new ArrayList<>();
        try {
            InputStream is = getAssets().open("doramas_latino.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            is.close();

            JSONArray array = new JSONArray(sb.toString().trim());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                MediaItem item = parseDoramaJson(obj);
                if (item != null) {
                    list.add(item);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error leyendo doramas_latino.json de assets: " + e.getMessage());
        }
        return list;
    }

    private List<MediaItem> loadDoramasFromFirebase() {
        List<MediaItem> list = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://anime-rc-default-rtdb.firebaseio.com/catalog/series.json");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "RexMovies-Android/1.0");

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString().trim());
                java.util.Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject obj = root.optJSONObject(k);
                    if (obj == null) continue;
                    String audio = obj.optString("audio", "");
                    JSONObject sources = obj.optJSONObject("sources");
                    boolean isTuDorama = (sources != null && sources.has("tudorama")) || "Latino".equalsIgnoreCase(audio);
                    if (isTuDorama) {
                        MediaItem item = parseDoramaJson(obj);
                        if (item != null) list.add(item);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error cargando doramas desde Firebase: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return list;
    }

    private MediaItem parseDoramaJson(JSONObject obj) {
        if (obj == null) return null;
        String title = obj.optString("title", "");
        if (title.isEmpty()) return null;

        String year = obj.optString("year", "");
        String poster = obj.optString("posterUrl", "");
        if (poster.isEmpty()) poster = obj.optString("poster", "");
        String backdrop = obj.optString("backdropUrl", "");
        if (backdrop.isEmpty()) backdrop = obj.optString("backdrop", "");
        String detailUrl = obj.optString("detailUrl", "");
        if (detailUrl.isEmpty()) {
            JSONObject sources = obj.optJSONObject("sources");
            if (sources != null && sources.has("tudorama")) {
                detailUrl = sources.optJSONObject("tudorama").optString("detail_url", "");
            }
        }

        int tmdbId = obj.optInt("tmdbId", 0);
        if (tmdbId <= 0) tmdbId = obj.optInt("tmdb_id", 0);
        double rating = obj.optDouble("rating", 0.0);
        String overview = obj.optString("overview", "");

        MediaItem item = new MediaItem(title, year, poster, detailUrl, 0);
        item.setBackdrop(backdrop);
        item.setTmdbId(tmdbId);
        item.setRating(rating);
        item.setSynopsis(overview);
        item.setMediaType("tvshows");
        item.setFuente("tudorama");

        return item;
    }

    private List<Section> buildSections(List<MediaItem> all) {
        List<Section> sections = new ArrayList<>();

        // 1. Continuar Viendo Doramas
        Section cwSection = buildContinueWatchingSection();
        if (cwSection != null && !cwSection.getItems().isEmpty()) {
            sections.add(cwSection);
        }

        // 2. 🔥 Doramas Más Populares & Aclamados (rating >= 8.5)
        List<MediaItem> popular = new ArrayList<>(all);
        Collections.sort(popular, (a, b) -> Double.compare(b.getRating(), a.getRating()));
        List<MediaItem> topPopular = popular.subList(0, Math.min(20, popular.size()));
        sections.add(new Section("🔥 Doramas Más Populares & Aclamados", topPopular));

        // 3. 💖 Romance & Comedia Romántica
        List<MediaItem> romance = new ArrayList<>();
        for (MediaItem m : all) {
            String title = m.getTitulo().toLowerCase(Locale.ROOT);
            if (title.contains("amor") || title.contains("beso") || title.contains("novio")
                    || title.contains("esposo") || title.contains("realeza") || title.contains("reina")
                    || title.contains("corazón") || title.contains("propuesta") || title.contains("belleza")) {
                romance.add(m);
            }
        }
        if (romance.isEmpty()) romance = all.subList(0, Math.min(20, all.size()));
        sections.add(new Section("💖 Romance & Comedia Romántica", romance));

        // 4. ⚡ Fantasía, Misterio & Magia
        List<MediaItem> fantasy = new ArrayList<>();
        for (MediaItem m : all) {
            String title = m.getTitulo().toLowerCase(Locale.ROOT);
            if (title.contains("goblin") || title.contains("alquimia") || title.contains("monarca")
                    || title.contains("demon") || title.contains("genio") || title.contains("vapor")
                    || title.contains("sirena") || title.contains("ratonera") || title.contains("dos mundos")) {
                fantasy.add(m);
            }
        }
        if (!fantasy.isEmpty()) {
            sections.add(new Section("⚡ Fantasía, Misterio & Magia", fantasy));
        }

        // 5. 🕵️ Acción, Crimen & Suspenso
        List<MediaItem> action = new ArrayList<>();
        for (MediaItem m : all) {
            String title = m.getTitulo().toLowerCase(Locale.ROOT);
            if (title.contains("vincenzo") || title.contains("sabuesos") || title.contains("agente")
                    || title.contains("samurái") || title.contains("confesión") || title.contains("letal")
                    || title.contains("buen chico") || title.contains("cashero") || title.contains("korea")) {
                action.add(m);
            }
        }
        if (!action.isEmpty()) {
            sections.add(new Section("🕵️ Acción, Crimen & Suspenso", action));
        }

        // 6. ✨ Catálogo Completo A-Z (Todos los Doramas en Español Latino)
        List<MediaItem> sortedAll = new ArrayList<>(all);
        Collections.sort(sortedAll, (a, b) -> a.getTitulo().compareToIgnoreCase(b.getTitulo()));
        sections.add(new Section("✨ Todos los Doramas en Español Latino (A-Z)", sortedAll));

        return sections;
    }

    private Section buildContinueWatchingSection() {
        List<ContinueWatchingItem> all = ContinueWatchingStore.getAll(this);
        if (all == null || all.isEmpty()) return null;

        List<MediaItem> mediaItems = new ArrayList<>();
        for (ContinueWatchingItem cw : all) {
            if (ContinueWatchingStore.isCompleted(cw)) continue;

            boolean isDorama = (cw.getSource() != null && cw.getSource().toLowerCase(Locale.ROOT).contains("tudorama"))
                    || (cw.getDetailUrl() != null && cw.getDetailUrl().contains("tudorama.com"));

            if (!isDorama) continue;

            String cleanTitle = cw.getSeriesTitle() != null && !cw.getSeriesTitle().isEmpty()
                    ? cw.getSeriesTitle() : cw.getTitle();
            if (cleanTitle != null) {
                cleanTitle = cleanTitle.replaceAll("(?i)\\s*-\\s*(?:T|S)\\d+.*", "")
                        .replaceAll("(?i)\\s*(?:T|S)\\d+[:E\\s].*", "").trim();
            }

            MediaItem mi = new MediaItem(cleanTitle, "", cw.getImageUrl(), cw.getDetailUrl(), cw.getPostId());
            mi.setMediaType("tvshows");
            mi.setProgress(Math.min(99, cw.getProgressPercent()));
            mi.setFuente("tudorama");
            if (mi.getImagen() == null || mi.getImagen().isEmpty()) {
                mi.setImagen(cw.getImageUrl());
            }

            continueWatchingByContentId.put(cw.getContentId(), cw);
            mediaItems.add(mi);
        }

        if (mediaItems.isEmpty()) return null;
        return new Section("Continuar Viendo Doramas", mediaItems);
    }

    private List<MediaItem> selectHeroItems(List<MediaItem> all) {
        List<MediaItem> heroes = new ArrayList<>();
        // Priorizar títulos de renombre con backdrops espectaculares
        String[] featured = new String[]{
                "Goblin", "Vincenzo", "Alquimia de Almas", "Cásate con mi esposo",
                "La Reina de las Lágrimas", "W Dos Mundos", "Belleza verdadera",
                "King the Land", "Aterrizaje de emergencia", "Lovely Runner"
        };

        for (String feat : featured) {
            for (MediaItem item : all) {
                if (item.getTitulo() != null && item.getTitulo().toLowerCase(Locale.ROOT).contains(feat.toLowerCase(Locale.ROOT))) {
                    if (!heroes.contains(item) && item.getBackdrop() != null && !item.getBackdrop().isEmpty()) {
                        heroes.add(item);
                        break;
                    }
                }
            }
            if (heroes.size() >= 5) break;
        }

        // Si no se llenaron 5, agregar los mejor valorados
        if (heroes.size() < 5) {
            for (MediaItem item : all) {
                if (!heroes.contains(item) && item.getBackdrop() != null && !item.getBackdrop().isEmpty()) {
                    heroes.add(item);
                    if (heroes.size() >= 5) break;
                }
            }
        }

        return heroes;
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
                UserDataManager udm = UserDataManager.getInstance(DoramasActivity.this);
                boolean wasFav = udm.isFavorite(item);
                if (wasFav) {
                    udm.removeFromFavorites(item);
                    Toast.makeText(DoramasActivity.this, "Eliminado de Mi Lista", Toast.LENGTH_SHORT).show();
                } else {
                    udm.addToFavorites(item);
                    Toast.makeText(DoramasActivity.this, "Añadido a Mi Lista", Toast.LENGTH_SHORT).show();
                }
            }
        }));

        setupHeroDots(currentHeroItems.size());

        if (heroPageCallback != null) {
            heroPager.unregisterOnPageChangeCallback(heroPageCallback);
        }
        heroPageCallback = new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateHeroDots(position);
            }
        };
        heroPager.registerOnPageChangeCallback(heroPageCallback);

        startHeroAutoScroll();
    }

    private void hideHeroCarousel() {
        if (heroContainer != null) {
            heroContainer.setVisibility(View.GONE);
        }
        updateTopPadding(false);
        stopHeroAutoScroll();
    }

    private void updateTopPadding(boolean hasHero) {
        if (rvSections == null) return;
        int topPadding = hasHero && !isTv ? (int) (420 * getResources().getDisplayMetrics().density) : 0;
        int bottomPadding = (int) (100 * getResources().getDisplayMetrics().density);
        rvSections.setPadding(0, topPadding, 0, bottomPadding);
    }

    private void setupHeroDots(int count) {
        if (heroDots == null) return;
        heroDots.removeAllViews();
        if (count <= 1) return;

        int size = (int) (6 * getResources().getDisplayMetrics().density);
        int margin = (int) (3 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < count; i++) {
            ImageView dot = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setImageResource(R.drawable.dot_inactive);
            heroDots.addView(dot);
        }
        updateHeroDots(0);
    }

    private void updateHeroDots(int activePosition) {
        if (heroDots == null) return;
        int count = heroDots.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = heroDots.getChildAt(i);
            if (child instanceof ImageView) {
                ((ImageView) child).setImageResource(i == activePosition ? R.drawable.dot_active : R.drawable.dot_inactive);
            }
        }
    }

    private void startHeroAutoScroll() {
        stopHeroAutoScroll();
        heroAutoScroll = new Runnable() {
            @Override
            public void run() {
                if (heroPager != null && currentHeroItems.size() > 1) {
                    int next = (heroPager.getCurrentItem() + 1) % currentHeroItems.size();
                    heroPager.setCurrentItem(next, true);
                    heroHandler.postDelayed(this, 5500);
                }
            }
        };
        heroHandler.postDelayed(heroAutoScroll, 5500);
    }

    private void stopHeroAutoScroll() {
        if (heroAutoScroll != null) {
            heroHandler.removeCallbacks(heroAutoScroll);
            heroAutoScroll = null;
        }
    }

    private void openDetail(MediaItem item) {
        if (item == null) return;
        DetalleActivity.start(this, item);
    }

    private void showSettingsDialog() {
        com.google.firebase.auth.FirebaseUser user = AuthManager.getInstance().getCurrentUser();
        String name = user != null ? user.getDisplayName() : null;
        String email = user != null ? user.getEmail() : null;
        String title = user != null && name != null && !name.isEmpty() ? name : "Ajustes";

        long cacheBytes = sv.edu.catolica.rex.utils.AppCacheManager.getCacheSizeBytes(this);
        String cacheStr = sv.edu.catolica.rex.utils.AppCacheManager.formatSize(cacheBytes);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage((email != null ? email + "\n\n" : "") + "Caché de la app: " + cacheStr)
                .setPositiveButton("Limpiar Caché", (dialog, which) -> {
                    sv.edu.catolica.rex.utils.AppCacheManager.cleanCache(this, (bytesFreed, formatted) -> {
                        Toast.makeText(DoramasActivity.this, "Caché liberada: " + formatted, Toast.LENGTH_SHORT).show();
                    });
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentHeroItems.size() > 1) {
            startHeroAutoScroll();
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
