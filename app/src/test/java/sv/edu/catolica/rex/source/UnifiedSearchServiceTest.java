package sv.edu.catolica.rex.source;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;

public class UnifiedSearchServiceTest {

    @Test
    public void multiSourceRepository_findByName_findsSourcesIgnoringCase() {
        MultiSourceRepository repo = SourceRegistry.getInstance();
        assertNotNull(repo.findByName("allcalidad"));
        assertNotNull(repo.findByName("AllCalidad"));
        assertNotNull(repo.findByName("cuevana3"));
        assertNotNull(repo.findByName("Cuevana3"));
        assertNotNull(repo.findByName("pelispedia"));
        assertNull(repo.findByName("nonexistent"));
        assertNull(repo.findByName(null));
    }

    @Test
    public void unifiedSearchService_isSeriesType_identifiesSeriesCorrectly() {
        assertTrue(UnifiedSearchService.isSeriesType("tvshows"));
        assertTrue(UnifiedSearchService.isSeriesType("animes"));
        assertTrue(UnifiedSearchService.isSeriesType("series"));
        assertTrue(UnifiedSearchService.isSeriesType("tv"));
        assertFalse(UnifiedSearchService.isSeriesType("movies"));
        assertFalse(UnifiedSearchService.isSeriesType("movie"));
        assertFalse(UnifiedSearchService.isSeriesType(null));
    }

    @Test
    public void unifiedSearchService_isMatch_matchesIdenticalOrFuzzyTitles() {
        MediaItem item1 = new MediaItem("The Boys", "2019", "http://img.jpg", "https://wv3.cuevana3.eu/serie/the-boys", 0);
        item1.setMediaType("tvshows");

        MediaItem item2 = new MediaItem("The Boys", "2019", "http://tmdb.jpg", "", 0);
        item2.setMediaType("tvshows");
        item2.setTmdbId(76479);

        assertTrue("Identical titles with same year and type must match",
                UnifiedSearchService.isMatch(item1, item2));

        MediaItem movie1 = new MediaItem("Spider-Man: No Way Home", "2021", "http://img1.jpg", "", 1234);
        movie1.setMediaType("movies");

        MediaItem movie2 = new MediaItem("Spider-Man No Way Home", "2021", "http://img2.jpg", "", 0);
        movie2.setMediaType("movies");

        assertTrue("Fuzzy/normalized titles must match",
                UnifiedSearchService.isMatch(movie1, movie2));

        MediaItem diff = new MediaItem("Avengers Endgame", "2019", "http://img.jpg", "", 0);
        diff.setMediaType("movies");
        assertFalse("Different titles must not match",
                UnifiedSearchService.isMatch(movie1, diff));
    }

    @Test
    public void unifiedSearchService_mergeAndDeduplicate_fusesMetadataAndStreams() {
        List<MediaItem> allCalidad = new ArrayList<>();
        List<MediaItem> cuevana = new ArrayList<>();
        List<MediaItem> pelispedia = new ArrayList<>();
        List<MediaItem> tmdb = new ArrayList<>();

        // Cuevana3 tiene "The Boys"
        MediaItem c3TheBoys = new MediaItem("The Boys", "2019", "http://c3/poster.jpg", "https://wv3.cuevana3.eu/serie/the-boys", 0);
        c3TheBoys.setMediaType("tvshows");
        c3TheBoys.setFuente("cuevana3");
        cuevana.add(c3TheBoys);

        // TMDb tiene "The Boys" con metadatos ricos
        MediaItem tmdbTheBoys = new MediaItem("The Boys", "2019", "http://tmdb/hq_poster.jpg", "", 0);
        tmdbTheBoys.setMediaType("tvshows");
        tmdbTheBoys.setTmdbId(76479);
        tmdbTheBoys.setRating(8.5);
        tmdbTheBoys.setSynopsis("A fun and irreverent take on what happens when superheroes abuse their superpowers.");
        tmdbTheBoys.setBackdrop("http://tmdb/backdrop.jpg");
        tmdb.add(tmdbTheBoys);

        // TMDb tiene además una película que ningún scraper indexó inicialmente
        MediaItem tmdbExclusive = new MediaItem("Película Exclusiva", "2024", "http://tmdb/excl.jpg", "", 0);
        tmdbExclusive.setMediaType("movies");
        tmdbExclusive.setTmdbId(99999);
        tmdb.add(tmdbExclusive);

        List<MediaItem> merged = UnifiedSearchService.mergeAndDeduplicate(allCalidad, cuevana, pelispedia, tmdb, 50);

        assertEquals("Should have exactly 2 deduplicated items", 2, merged.size());

        MediaItem fused = merged.get(0);
        assertEquals("The Boys", fused.getTitulo());
        assertEquals("cuevana3", fused.getFuente());
        assertEquals("https://wv3.cuevana3.eu/serie/the-boys", fused.getDetailUrl());
        assertEquals(76479, fused.getTmdbId());
        assertEquals("http://tmdb/hq_poster.jpg", fused.getImagen());
        assertEquals("http://tmdb/backdrop.jpg", fused.getBackdrop());
        assertEquals(8.5, fused.getRating(), 0.01);
        assertEquals("A fun and irreverent take on what happens when superheroes abuse their superpowers.", fused.getSynopsis());

        MediaItem second = merged.get(1);
        assertEquals("Película Exclusiva", second.getTitulo());
        assertEquals(99999, second.getTmdbId());
    }

    @Test
    public void unifiedSearchService_relevance_filtersUnrelatedAndPrioritizesExact() {
        String query = "King the Land";

        int exactScore = UnifiedSearchService.computeTitleRelevance(query, "King the Land");
        assertEquals("Exact title match must score 100", 100, exactScore);

        int unrelatedDocScore = UnifiedSearchService.computeTitleRelevance(query, "King: Una historia de Martin Luther King");
        assertTrue("Partial single-word match on a multi-word query must score < 40", unrelatedDocScore < 40);

        List<MediaItem> allCalidad = new ArrayList<>();
        // AllCalidad solo tiene el documental irrelevante
        MediaItem mlkDoc = new MediaItem("King: Una historia de Martin Luther King", "1970", "http://allcal/king.jpg", "https://allcalidad.re/peliculas/king-1970", 25178);
        mlkDoc.setMediaType("movies");
        mlkDoc.setFuente("allcalidad");
        allCalidad.add(mlkDoc);

        List<MediaItem> cuevana = new ArrayList<>();
        // Cuevana3 tiene la serie real
        MediaItem c3Series = new MediaItem("King the Land", "2023", "http://c3/ktl.jpg", "https://wv3.cuevana3.eu/serie/king-the-land", 0);
        c3Series.setMediaType("tvshows");
        c3Series.setFuente("cuevana3");
        cuevana.add(c3Series);

        List<MediaItem> merged = UnifiedSearchService.mergeAndDeduplicate(
                allCalidad, cuevana, new ArrayList<>(), new ArrayList<>(), 50, query);

        // El documental irrelevante debe ser filtrado por tener relevancia < 40
        assertEquals("Only relevant results should remain", 1, merged.size());
        assertEquals("King the Land", merged.get(0).getTitulo());
        assertEquals("cuevana3", merged.get(0).getFuente());
    }

    @Test
    public void unifiedSearchService_isMatch_rejectsDisparateTypesAndYears() {
        MediaItem series = new MediaItem("King the Land", "2023", "http://c3/ktl.jpg", "https://wv3.cuevana3.eu/serie/king-the-land", 0);
        series.setMediaType("tvshows");

        MediaItem movie = new MediaItem("King: Una historia de Martin Luther King", "1970", "http://allcal/king.jpg", "https://allcalidad.re/peliculas/king-1970", 25178);
        movie.setMediaType("movies");

        assertFalse("Must not match a series with an unrelated 1970 movie",
                UnifiedSearchService.isMatch(series, movie));
    }
}
