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
}
