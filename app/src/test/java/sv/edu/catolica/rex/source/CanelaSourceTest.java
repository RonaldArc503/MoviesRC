package sv.edu.catolica.rex.source;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.smart.CanelaScraper;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public class CanelaSourceTest {

    @Test
    public void canelaSource_returnsCorrectName() {
        CanelaSource source = new CanelaSource();
        assertEquals("Novelas", source.getName());
    }

    @Test
    public void sourceRegistry_containsCanelaSource() {
        MultiSourceRepository repo = SourceRegistry.getInstance();
        boolean foundNovelas = false;
        boolean foundAllCalidad = false;
        boolean foundCuevana = false;

        for (ContentSource source : repo.getSources()) {
            if ("Novelas".equalsIgnoreCase(source.getName())) {
                foundNovelas = true;
            }
            if ("AllCalidad".equalsIgnoreCase(source.getName())) {
                foundAllCalidad = true;
            }
            if ("Cuevana3".equalsIgnoreCase(source.getName())) {
                foundCuevana = true;
            }
        }

        assertTrue("AllCalidad must be registered", foundAllCalidad);
        assertTrue("Cuevana3 must be registered", foundCuevana);
        assertTrue("Novelas (CanelaSource) must be registered in SourceRegistry", foundNovelas);
    }

    @Test
    public void canelaScraper_buildsPlayerUrlCorrectly() {
        String slug = "entre-tu-amor-y-mi-amor-lecho-de-muerte-s1-ep1";
        String playerUrl = CanelaScraper.buildPlayerUrl(slug);
        assertEquals("https://canela.tv/player/tvepisode/entre-tu-amor-y-mi-amor-lecho-de-muerte-s1-ep1", playerUrl);

        assertEquals("", CanelaScraper.buildPlayerUrl(""));
        assertEquals("", CanelaScraper.buildPlayerUrl(null));
    }

    @Test
    public void canelaSource_resolveEpisodeDelegatesProperly() throws Exception {
        CanelaSource source = new CanelaSource();
        MediaItem item = new MediaItem("Test Novela", "2024", "", "https://canela.tv/series/test-slug", 0);
        item.setFuente("novelas");

        SmartPlaybackResult res = source.resolveEpisode(item, 1, 1);
        assertNotNull(res);
    }
}
