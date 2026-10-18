package sv.edu.catolica.rex;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.smart.Cuevana3Scraper;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public class CuevanaLiveTest {

    @Test
    public void testAllCalidadItemsParsing() throws Exception {
        List<AllCalidadScraper.ContentItem> items = AllCalidadScraper.search("batman", 1);
        Assert.assertNotNull(items);
        System.out.println("AllCalidad search results count: " + items.size());
        Assert.assertFalse("AllCalidad search should parse items array correctly", items.isEmpty());
        AllCalidadScraper.ContentItem first = items.get(0);
        System.out.println("First AllCalidad item: " + first.title + " (id=" + first.id + ", code=" + first.code + ")");
        Assert.assertTrue(first.id > 0);
    }

    @Test
    public void testFallbackMovieWithoutDetailUrl() throws Exception {
        Cuevana3Scraper scraper = new Cuevana3Scraper();

        // Simulate an item coming from TMDB or Home with NO detailUrl
        MediaItem tmdbMovie = new MediaItem("The Batman", "2022", "", "", 0);
        tmdbMovie.setMediaType("movies");
        tmdbMovie.setTmdbId(414906);

        SmartPlaybackResult result = scraper.resolveMovie(tmdbMovie);
        Assert.assertNotNull(result);
        System.out.println("Movie fallback hosts: " + result.hostUrls);
        System.out.println("Movie fallback streams: " + result.streamUrls);
        Assert.assertTrue("Should find playback URLs for movie fallback", result.hasPlayableUrls());
    }

    @Test
    public void testFallbackSeriesWithoutDetailUrl() throws Exception {
        Cuevana3Scraper scraper = new Cuevana3Scraper();

        // Simulate a series coming from TMDB with NO detailUrl
        MediaItem tmdbSeries = new MediaItem("Prison Break", "2005", "", "", 0);
        tmdbSeries.setMediaType("tvshows");
        tmdbSeries.setTmdbId(2288);

        SmartPlaybackResult result = scraper.resolveEpisode(tmdbSeries, 1, 1);
        Assert.assertNotNull(result);
        System.out.println("Series fallback hosts: " + result.hostUrls);
        System.out.println("Series fallback streams: " + result.streamUrls);
        Assert.assertTrue("Should find playback URLs for series fallback", result.hasPlayableUrls());
    }
}
