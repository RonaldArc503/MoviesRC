package sv.edu.catolica.rex.source;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.TmdbService;

/**
 * Servicio unificado de búsqueda inteligente y multi-fuente.
 * Consulta en paralelo AllCalidad, Cuevana3, Pelispedia y TMDb,
 * fusiona y deduplica resultados mediante algoritmos de coincidencia difusa,
 * y garantiza que el contenido se pueda reproducir desde la fuente disponible.
 */
public class UnifiedSearchService {

    private static final String TAG = "UnifiedSearchService";
    private static final int SOURCE_TIMEOUT_MS = 5000;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(5);

    private final MultiSourceRepository repo;

    public static class SearchResult {
        private final List<MediaItem> movies;
        private final List<MediaItem> series;
        private final List<MediaItem> all;

        public SearchResult(List<MediaItem> movies, List<MediaItem> series, List<MediaItem> all) {
            this.movies = movies != null ? movies : new ArrayList<>();
            this.series = series != null ? series : new ArrayList<>();
            this.all = all != null ? all : new ArrayList<>();
        }

        public List<MediaItem> getMovies() { return movies; }
        public List<MediaItem> getSeries() { return series; }
        public List<MediaItem> getAll() { return all; }
        public boolean isEmpty() { return all.isEmpty(); }
    }

    public UnifiedSearchService(MultiSourceRepository repo) {
        this.repo = repo != null ? repo : SourceRegistry.getInstance();
    }

    public static UnifiedSearchService getInstance() {
        return new UnifiedSearchService(SourceRegistry.getInstance());
    }

    /**
     * Búsqueda simple compatible con la interfaz previa.
     */
    public List<MediaItem> search(String q, int limit) {
        SearchResult result = searchUnified(q, limit);
        return result.getAll();
    }

    /**
     * Búsqueda multi-fuente inteligente y paralela.
     */
    public SearchResult searchUnified(String query, int limitPerSource) {
        if (query == null || query.trim().isEmpty()) {
            return new SearchResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        final String safeQuery = query.trim();
        final int limit = Math.max(10, limitPerSource);
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SOURCE_TIMEOUT_MS);

        // 1. Tareas concurrentes por fuente
        Future<List<MediaItem>> futureAllCalidad = EXECUTOR.submit(() -> {
            try {
                List<AllCalidadScraper.ContentItem> raw = AllCalidadScraper.search(safeQuery, 1);
                List<MediaItem> list = new ArrayList<>();
                if (raw != null) {
                    int max = Math.min(limit, raw.size());
                    for (int i = 0; i < max; i++) {
                        MediaItem mi = AllCalidadScraper.toMediaItem(raw.get(i));
                        if (mi != null) {
                            mi.setFuente("allcalidad");
                            list.add(mi);
                        }
                    }
                }
                return list;
            } catch (Exception e) {
                Log.w(TAG, "AllCalidad search failed: " + e.getMessage());
                return new ArrayList<>();
            }
        });

        Future<List<MediaItem>> futureCuevana = EXECUTOR.submit(() -> {
            try {
                ContentSource c3 = repo.findByName("Cuevana3");
                if (c3 != null) {
                    List<MediaItem> res = c3.search(safeQuery, limit);
                    if (res != null) {
                        for (MediaItem item : res) {
                            if (item != null && (item.getFuente() == null || item.getFuente().isEmpty())) {
                                item.setFuente("cuevana3");
                            }
                        }
                        return res;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Cuevana3 search failed: " + e.getMessage());
            }
            return new ArrayList<>();
        });

        Future<List<MediaItem>> futurePelispedia = EXECUTOR.submit(() -> {
            try {
                ContentSource peli = repo.findByName("Pelispedia");
                if (peli != null) {
                    List<MediaItem> res = peli.search(safeQuery, limit);
                    if (res != null) {
                        for (MediaItem item : res) {
                            if (item != null && (item.getFuente() == null || item.getFuente().isEmpty())) {
                                item.setFuente("pelispedia");
                            }
                        }
                        return res;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Pelispedia search failed: " + e.getMessage());
            }
            return new ArrayList<>();
        });

        Future<List<MediaItem>> futureNovelas = EXECUTOR.submit(() -> {
            try {
                ContentSource novelas = repo.findByName("Novelas");
                if (novelas != null) {
                    List<MediaItem> res = novelas.search(safeQuery, limit);
                    if (res != null) {
                        for (MediaItem item : res) {
                            if (item != null && (item.getFuente() == null || item.getFuente().isEmpty())) {
                                item.setFuente("novelas");
                            }
                        }
                        return res;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Novelas search failed: " + e.getMessage());
            }
            return new ArrayList<>();
        });

        Future<List<MediaItem>> futureTmdb = EXECUTOR.submit(() -> {
            try {
                List<MediaItem> res = TmdbService.searchMedia(safeQuery, limit);
                if (res != null) {
                    for (MediaItem item : res) {
                        if (item != null && (item.getFuente() == null || item.getFuente().isEmpty())) {
                            item.setFuente("tmdb");
                        }
                    }
                    return res;
                }
            } catch (Exception e) {
                Log.w(TAG, "TMDb search failed: " + e.getMessage());
            }
            return new ArrayList<>();
        });

        List<MediaItem> allCalidadResults = getFutureResult(futureAllCalidad, deadlineNanos);
        List<MediaItem> cuevanaResults = getFutureResult(futureCuevana, deadlineNanos);
        List<MediaItem> pelispediaResults = getFutureResult(futurePelispedia, deadlineNanos);
        List<MediaItem> novelasResults = getFutureResult(futureNovelas, deadlineNanos);
        List<MediaItem> tmdbResults = getFutureResult(futureTmdb, deadlineNanos);

        // 2. Fusión inteligente y deduplicación con relevancia de búsqueda
        List<MediaItem> unifiedList = mergeAndDeduplicate(
                allCalidadResults, cuevanaResults, pelispediaResults, novelasResults, tmdbResults, limit * 3, safeQuery);

        // 3. Separación en Películas y Series
        List<MediaItem> movies = new ArrayList<>();
        List<MediaItem> series = new ArrayList<>();
        for (MediaItem item : unifiedList) {
            if (isSeriesType(item.getMediaType())) {
                series.add(item);
            } else {
                movies.add(item);
            }
        }

        return new SearchResult(movies, series, unifiedList);
    }

    private List<MediaItem> getFutureResult(Future<List<MediaItem>> future, long deadlineNanos) {
        try {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ArrayList<>();
        } catch (Exception e) {
            future.cancel(true);
            return new ArrayList<>();
        }
    }

    /**
     * Fusiona los resultados de scrapers con los metadatos de TMDb de forma inteligente.
     */
    public static List<MediaItem> mergeAndDeduplicate(
            List<MediaItem> allCalidad,
            List<MediaItem> cuevana,
            List<MediaItem> pelispedia,
            List<MediaItem> tmdb,
            int maxTotal) {
        return mergeAndDeduplicate(allCalidad, cuevana, pelispedia, null, tmdb, maxTotal, null);
    }

    public static List<MediaItem> mergeAndDeduplicate(
            List<MediaItem> allCalidad,
            List<MediaItem> cuevana,
            List<MediaItem> pelispedia,
            List<MediaItem> tmdb,
            int maxTotal,
            String query) {
        return mergeAndDeduplicate(allCalidad, cuevana, pelispedia, null, tmdb, maxTotal, query);
    }

    public static List<MediaItem> mergeAndDeduplicate(
            List<MediaItem> allCalidad,
            List<MediaItem> cuevana,
            List<MediaItem> pelispedia,
            List<MediaItem> novelas,
            List<MediaItem> tmdb,
            int maxTotal,
            String query) {

        // 1. Agrupar scrapers aplicando filtrado de relevancia si hay consulta
        List<MediaItem> combinedScrapers = new ArrayList<>();
        appendScraperItems(combinedScrapers, allCalidad, query);
        appendScraperItems(combinedScrapers, cuevana, query);
        appendScraperItems(combinedScrapers, pelispedia, query);
        if (novelas != null) {
            appendScraperItems(combinedScrapers, novelas, query);
        }

        // 2. Unificar scrapers con TMDb
        List<MediaItem> merged = new ArrayList<>();
        boolean[] tmdbUsed = new boolean[tmdb != null ? tmdb.size() : 0];

        for (MediaItem scraperItem : combinedScrapers) {
            if (scraperItem == null || merged.size() >= maxTotal) break;

            MediaItem matchedTmdb = null;
            if (tmdb != null) {
                for (int i = 0; i < tmdb.size(); i++) {
                    if (tmdbUsed[i]) continue;
                    MediaItem tmdbItem = tmdb.get(i);
                    if (isMatch(scraperItem, tmdbItem)) {
                        matchedTmdb = tmdbItem;
                        tmdbUsed[i] = true;
                        break;
                    }
                }
            }

            if (matchedTmdb != null) {
                // Fusión: Metadatos visuales de TMDb + datos de streaming del scraper
                MediaItem fused = fuseMediaItems(scraperItem, matchedTmdb);
                merged.add(fused);
            } else {
                merged.add(scraperItem);
            }
        }

        // 3. Añadir elementos de TMDb que no estaban en los scrapers directos
        if (tmdb != null) {
            for (int i = 0; i < tmdb.size(); i++) {
                if (!tmdbUsed[i] && merged.size() < maxTotal) {
                    MediaItem tmdbItem = tmdb.get(i);
                    if (tmdbItem != null) {
                        if (query != null && !query.trim().isEmpty()) {
                            int rel = computeQueryRelevance(query, tmdbItem);
                            if (rel < 40) continue;
                        }
                        merged.add(tmdbItem);
                    }
                }
            }
        }

        // 4. Ordenar por relevancia respecto a la búsqueda del usuario (coincidencias exactas primero)
        if (query != null && !query.trim().isEmpty()) {
            final String qFinal = query;
            Collections.sort(merged, (m1, m2) -> {
                int r1 = computeQueryRelevance(qFinal, m1);
                int r2 = computeQueryRelevance(qFinal, m2);
                if (r1 != r2) {
                    return Integer.compare(r2, r1);
                }
                boolean m1Direct = m1.getPostId() > 0 || (m1.getDetailUrl() != null && !m1.getDetailUrl().isEmpty());
                boolean m2Direct = m2.getPostId() > 0 || (m2.getDetailUrl() != null && !m2.getDetailUrl().isEmpty());
                if (m1Direct != m2Direct) {
                    return m1Direct ? -1 : 1;
                }
                return 0;
            });
        }

        return merged;
    }

    private static void appendScraperItems(List<MediaItem> target, List<MediaItem> source, String query) {
        if (source == null) return;
        for (MediaItem item : source) {
            if (item == null) continue;
            if (query != null && !query.trim().isEmpty()) {
                int rel = computeQueryRelevance(query, item);
                if (rel < 40) {
                    continue;
                }
            }
            boolean duplicate = false;
            for (MediaItem existing : target) {
                if (isMatch(item, existing)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                target.add(item);
            }
        }
    }

    public static int computeQueryRelevance(String query, MediaItem item) {
        if (item == null) return 0;
        return computeTitleRelevance(query, item.getTitulo());
    }

    public static int computeTitleRelevance(String query, String title) {
        if (query == null || query.trim().isEmpty()) return 100;
        if (title == null || title.trim().isEmpty()) return 0;

        String q = SourceMatcher.normalize(query);
        String t = SourceMatcher.normalize(title);
        if (q.isEmpty() || t.isEmpty()) return 0;
        if (q.equals(t)) return 100;

        if (t.startsWith(q + " ")) {
            return 92;
        }
        if (q.startsWith(t + " ")) {
            return 88;
        }

        if (t.contains(q)) {
            int score = (q.length() * 90) / t.length();
            return Math.max(75, score);
        }
        if (q.contains(t)) {
            return (t.length() * 75) / q.length();
        }

        String[] queryWords = q.split("\\s+");
        int totalMeaningful = 0;
        int matchedMeaningful = 0;
        for (String w : queryWords) {
            if (w.length() < 3 || isStopWord(w)) continue;
            totalMeaningful++;
            if (t.contains(w)) {
                matchedMeaningful++;
            }
        }

        if (totalMeaningful == 0) {
            return SourceMatcher.fuzzyScore(q, t);
        }

        if (totalMeaningful >= 2 && matchedMeaningful < totalMeaningful) {
            return (matchedMeaningful * 100 / totalMeaningful) / 2;
        }

        int wordScore = matchedMeaningful * 100 / totalMeaningful;
        int fuzzy = SourceMatcher.fuzzyScore(q, t);
        return Math.max(wordScore, fuzzy);
    }

    private static boolean isStopWord(String word) {
        if (word == null) return false;
        String w = word.toLowerCase(Locale.ROOT);
        return w.equals("the") || w.equals("de") || w.equals("la") || w.equals("el")
                || w.equals("en") || w.equals("y") || w.equals("and") || w.equals("of")
                || w.equals("un") || w.equals("una") || w.equals("los") || w.equals("las")
                || w.equals("del") || w.equals("por") || w.equals("para") || w.equals("a");
    }

    public static boolean isMatch(MediaItem a, MediaItem b) {
        if (a == null || b == null) return false;

        // Si ambos tienen tmdbId válido, comparar directamente
        if (a.getTmdbId() > 0 && b.getTmdbId() > 0) {
            return a.getTmdbId() == b.getTmdbId();
        }

        // Si tienen imdbId válido, comparar directamente
        if (a.getImdbId() != null && !a.getImdbId().isEmpty()
                && b.getImdbId() != null && !b.getImdbId().isEmpty()) {
            if (a.getImdbId().equalsIgnoreCase(b.getImdbId())) {
                return true;
            }
        }

        // Validar tipo si ambos lo especifican
        boolean aIsSeries = isSeriesType(a.getMediaType());
        boolean bIsSeries = isSeriesType(b.getMediaType());
        if (a.getMediaType() != null && b.getMediaType() != null && (aIsSeries != bIsSeries)) {
            return false;
        }

        String normA = SourceMatcher.normalize(a.getTitulo());
        String normB = SourceMatcher.normalize(b.getTitulo());
        if (normA.isEmpty() || normB.isEmpty()) return false;

        // Título normalizado idéntico
        if (normA.equals(normB)) {
            return isYearCompatible(a.getAnio(), b.getAnio());
        }

        // Fuzzy score alto
        int score = SourceMatcher.fuzzyScore(normA, normB);
        if (score >= 88) {
            return isYearCompatible(a.getAnio(), b.getAnio());
        }

        return false;
    }

    private static boolean isYearCompatible(String yearA, String yearB) {
        if (yearA == null || yearB == null) return true;
        String yA = yearA.trim();
        String yB = yearB.trim();
        if (yA.isEmpty() || yB.isEmpty()) return true;
        try {
            int intA = Integer.parseInt(yA.replaceAll("[^0-9]", ""));
            int intB = Integer.parseInt(yB.replaceAll("[^0-9]", ""));
            return Math.abs(intA - intB) <= 1;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static MediaItem fuseMediaItems(MediaItem scraperItem, MediaItem tmdbItem) {
        String title = tmdbItem.getTitulo() != null && !tmdbItem.getTitulo().isEmpty()
                ? tmdbItem.getTitulo() : scraperItem.getTitulo();
        String year = tmdbItem.getAnio() != null && !tmdbItem.getAnio().isEmpty()
                ? tmdbItem.getAnio() : scraperItem.getAnio();
        String poster = tmdbItem.getImagen() != null && !tmdbItem.getImagen().isEmpty()
                ? tmdbItem.getImagen() : scraperItem.getImagen();
        String detailUrl = scraperItem.getDetailUrl();
        int postId = scraperItem.getPostId();

        MediaItem fused = new MediaItem(title, year, poster, detailUrl, postId);
        fused.setMediaType(scraperItem.getMediaType() != null ? scraperItem.getMediaType() : tmdbItem.getMediaType());
        fused.setTmdbId(tmdbItem.getTmdbId());
        fused.setFuente(scraperItem.getFuente());

        if (tmdbItem.getBackdrop() != null && !tmdbItem.getBackdrop().isEmpty()) {
            fused.setBackdrop(tmdbItem.getBackdrop());
        } else {
            fused.setBackdrop(scraperItem.getBackdrop());
        }

        if (tmdbItem.getSynopsis() != null && !tmdbItem.getSynopsis().isEmpty()) {
            fused.setSynopsis(tmdbItem.getSynopsis());
        } else {
            fused.setSynopsis(scraperItem.getSynopsis());
        }

        double rating = tmdbItem.getRating() > 0 ? tmdbItem.getRating() : scraperItem.getRating();
        fused.setRating(rating);

        if (tmdbItem.getImdbId() != null && !tmdbItem.getImdbId().isEmpty()) {
            fused.setImdbId(tmdbItem.getImdbId());
        } else if (scraperItem.getImdbId() != null && !scraperItem.getImdbId().isEmpty()) {
            fused.setImdbId(scraperItem.getImdbId());
        }

        return fused;
    }

    public static boolean isSeriesType(String mediaType) {
        if (mediaType == null) return false;
        String t = mediaType.trim().toLowerCase(Locale.ROOT);
        return "tvshows".equals(t) || "animes".equals(t) || "series".equals(t) || "tv".equals(t);
    }
}
