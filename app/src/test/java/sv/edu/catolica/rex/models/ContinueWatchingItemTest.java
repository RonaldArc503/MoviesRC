package sv.edu.catolica.rex.models;

import static org.junit.Assert.*;

import org.junit.Test;

public class ContinueWatchingItemTest {

    @Test
    public void setAndGetContentId() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setContentId("cw_123");
        assertEquals("cw_123", item.getContentId());
    }

    @Test
    public void setAndGetTitle() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setTitle("Test Title");
        assertEquals("Test Title", item.getTitle());
    }

    @Test
    public void setAndGetPositionMs() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setPositionMs(60000L);
        assertEquals(60000L, item.getPositionMs());
    }

    @Test
    public void setAndGetDurationMs() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setDurationMs(3600000L);
        assertEquals(3600000L, item.getDurationMs());
    }

    @Test
    public void setAndGetProgressPercent() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setProgressPercent(45);
        assertEquals(45, item.getProgressPercent());
    }

    @Test
    public void setAndGetCompleted() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        assertFalse(item.isCompleted());
        item.setCompleted(true);
        assertTrue(item.isCompleted());
    }

    @Test
    public void setAndGetLastPlayedAt() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        long now = System.currentTimeMillis();
        item.setLastPlayedAt(now);
        assertEquals(now, item.getLastPlayedAt());
    }

    @Test
    public void setAndGetEpisodeFields() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setEpisodeId(101);
        item.setSeasonNumber(2);
        item.setEpisodeNumber(5);
        assertEquals(101, item.getEpisodeId());
        assertEquals(2, item.getSeasonNumber());
        assertEquals(5, item.getEpisodeNumber());
    }

    @Test
    public void normalizeCompletion_marksCompletedWhenPctIs95OrHigher() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setMediaType("tvshows");
        item.setPositionMs(95000L);
        item.setDurationMs(100000L);
        item.setProgressPercent(95);

        sv.edu.catolica.rex.storage.ContinueWatchingStore.normalizeCompletion(item);

        assertTrue(item.isCompleted());
        assertEquals(100, item.getProgressPercent());
    }

    @Test
    public void normalizeCompletion_keepsUncompletedWhenBelow95() {
        ContinueWatchingItem item = new ContinueWatchingItem();
        item.setMediaType("tvshows");
        item.setPositionMs(94000L);
        item.setDurationMs(100000L);
        item.setProgressPercent(94);

        sv.edu.catolica.rex.storage.ContinueWatchingStore.normalizeCompletion(item);

        assertFalse(item.isCompleted());
        assertEquals(94, item.getProgressPercent());
    }

    @Test
    public void normalizeCompletion_movieRequires99Percent() {
        ContinueWatchingItem movie = new ContinueWatchingItem();
        movie.setMediaType("movies");
        movie.setPositionMs(98000L);
        movie.setDurationMs(100000L);
        movie.setProgressPercent(98);

        sv.edu.catolica.rex.storage.ContinueWatchingStore.normalizeCompletion(movie);

        assertFalse(movie.isCompleted());

        movie.setPositionMs(99000L);
        movie.setProgressPercent(99);
        sv.edu.catolica.rex.storage.ContinueWatchingStore.normalizeCompletion(movie);

        assertTrue(movie.isCompleted());
        assertTrue(movie.isEverCompleted());
    }

    @Test
    public void deduplicateSeries_keepsOnlyLatestEpisodeForSameSeries() {
        java.util.List<ContinueWatchingItem> list = new java.util.ArrayList<>();

        // Episodio 1 de la serie "Fallout", visto primero
        ContinueWatchingItem ep1 = new ContinueWatchingItem();
        ep1.setContentId("series_123_ep1");
        ep1.setPostId(123);
        ep1.setSeriesTitle("Fallout");
        ep1.setSeasonNumber(1);
        ep1.setEpisodeNumber(1);
        ep1.setLastPlayedAt(1000L);
        ep1.setProgressPercent(50);
        list.add(ep1);

        // Episodio 2 de la serie "Fallout", visto después (más reciente)
        ContinueWatchingItem ep2 = new ContinueWatchingItem();
        ep2.setContentId("series_123_ep2");
        ep2.setPostId(123);
        ep2.setSeriesTitle("Fallout");
        ep2.setSeasonNumber(1);
        ep2.setEpisodeNumber(2);
        ep2.setLastPlayedAt(2000L);
        ep2.setProgressPercent(20);
        list.add(ep2);

        // Película "Inception"
        ContinueWatchingItem movie = new ContinueWatchingItem();
        movie.setContentId("movie_999");
        movie.setPostId(999);
        movie.setTitle("Inception");
        movie.setMediaType("movies");
        movie.setLastPlayedAt(1500L);
        movie.setProgressPercent(30);
        list.add(movie);

        sv.edu.catolica.rex.storage.ContinueWatchingStore.deduplicateSeries(list);

        assertEquals(2, list.size());
        // El primer elemento debe ser el ep2 (último jugado a las 2000L)
        assertEquals("series_123_ep2", list.get(0).getContentId());
        assertEquals(2, list.get(0).getEpisodeNumber());
        // El segundo elemento es la película
        assertEquals("movie_999", list.get(1).getContentId());
    }

    @Test
    public void parseSeasonAndEpisodeNumber_fromVariousTitleFormats() {
        ContinueWatchingItem item1 = new ContinueWatchingItem();
        item1.setTitle("King the Land T1 Episodio 1");
        assertEquals(1, sv.edu.catolica.rex.storage.ContinueWatchingStore.parseSeasonNumber(item1));
        assertEquals(1, sv.edu.catolica.rex.storage.ContinueWatchingStore.parseEpisodeNumber(item1));

        ContinueWatchingItem item2 = new ContinueWatchingItem();
        item2.setTitle("Stranger Things 2x04 Episodio 4");
        assertEquals(2, sv.edu.catolica.rex.storage.ContinueWatchingStore.parseSeasonNumber(item2));
        assertEquals(4, sv.edu.catolica.rex.storage.ContinueWatchingStore.parseEpisodeNumber(item2));

        ContinueWatchingItem item3 = new ContinueWatchingItem();
        item3.setTitle("Anime Name Capitulo 12");
        assertEquals(1, sv.edu.catolica.rex.storage.ContinueWatchingStore.parseSeasonNumber(item3));
        assertEquals(12, sv.edu.catolica.rex.storage.ContinueWatchingStore.parseEpisodeNumber(item3));
    }

    @Test
    public void resolveNextEpisodeItem_doesNotInventEpisodeWithoutCatalog() {
        ContinueWatchingItem current = new ContinueWatchingItem();
        current.setContentId("cuevana_series_123");
        current.setSeriesTitle("King the Land");
        current.setTitle("King the Land T1 Episodio 1");
        current.setMediaType("tvshows");
        current.setSeasonNumber(1);
        current.setEpisodeNumber(1);
        current.setPostId(0);
        current.setCompleted(true);
        current.setProgressPercent(100);

        ContinueWatchingItem next = sv.edu.catolica.rex.storage.ContinueWatchingStore.resolveNextEpisodeItem(current);

        assertNull(next);
    }
}
