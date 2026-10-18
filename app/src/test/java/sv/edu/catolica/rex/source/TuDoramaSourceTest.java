package sv.edu.catolica.rex.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import sv.edu.catolica.rex.network.smart.TuDoramaScraper;

public class TuDoramaSourceTest {

    @Test
    public void source_hasCorrectName() {
        TuDoramaSource source = new TuDoramaSource();
        assertEquals("TuDorama", source.getName());
    }

    @Test
    public void sourceRegistry_registersTuDorama() {
        MultiSourceRepository repo = SourceRegistry.getInstance();
        boolean found = false;
        for (ContentSource cs : repo.getSources()) {
            if ("tudorama".equalsIgnoreCase(cs.getName())) {
                found = true;
                break;
            }
        }
        assertTrue("TuDoramaSource must be registered in SourceRegistry", found);
    }

    @Test
    public void resolveDirectEmbed_mapsKnownServersCorrectly() {
        // StreamHG
        assertEquals("https://hgcloud.to/e/test1234",
                TuDoramaScraper.resolveDirectEmbed("https://cdn.tudorama.com/player/server-1.php?id1=test1234"));

        // Filemoon
        assertEquals("https://bysesukior.com/e/moon5678",
                TuDoramaScraper.resolveDirectEmbed("https://cdn.tudorama.com/stream/server-2.php?id1=moon5678"));

        // Minochinos / EarnVids
        assertEquals("https://minochinos.com/embed/earn9999",
                TuDoramaScraper.resolveDirectEmbed("https://cdn.tudorama.com/player/server-3.php?id1=earn9999"));

        // Abyss Player
        assertEquals("https://abyssplayer.com/abyss777",
                TuDoramaScraper.resolveDirectEmbed("https://cdn.tudorama.com/stream/abyss.php?id1=abyss777"));

        // PixelDrain's file endpoint is a direct media stream for ExoPlayer.
        assertEquals("https://pixeldrain.com/api/file/direct123",
                TuDoramaScraper.resolveDirectEmbed("https://pixeldrain.com/u/direct123"));
    }
}
