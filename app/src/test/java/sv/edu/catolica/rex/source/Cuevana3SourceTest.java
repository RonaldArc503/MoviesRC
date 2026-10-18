package sv.edu.catolica.rex.source;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

import sv.edu.catolica.rex.network.smart.Cuevana3Scraper;

public class Cuevana3SourceTest {

    @Test
    public void cuevana3Source_returnsCorrectName() {
        Cuevana3Source source = new Cuevana3Source();
        assertEquals("Cuevana3", source.getName());
    }

    @Test
    public void sourceRegistry_containsCuevana3Source() {
        MultiSourceRepository repo = SourceRegistry.getInstance();
        boolean foundCuevana = false;
        boolean foundAllCalidad = false;

        for (ContentSource source : repo.getSources()) {
            if ("Cuevana3".equalsIgnoreCase(source.getName())) {
                foundCuevana = true;
            }
            if ("AllCalidad".equalsIgnoreCase(source.getName())) {
                foundAllCalidad = true;
            }
        }

        assertTrue("AllCalidad must be registered", foundAllCalidad);
        assertTrue("Cuevana3 must be registered in SourceRegistry", foundCuevana);
    }

    @Test
    public void cuevana3Scraper_extractsEmbedUrlsFromHtml() {
        Cuevana3Scraper scraper = new Cuevana3Scraper();
        String sampleHtml = "<div class=\"TPlayer embed_div\">\n" +
                "  <div class=\"TPlayerTb\" id=\"OptE11\">\n" +
                "    <iframe class=\"no-you\" width=\"560\" height=\"315\" data-src=\"https://doodstream.com/e/c4lr0zhjl17i\" frameborder=\"0\"></iframe>\n" +
                "  </div>\n" +
                "  <div class=\"TPlayerTb\" id=\"OptE12\">\n" +
                "    <iframe class=\"no-you\" width=\"560\" height=\"315\" data-src=\"https://voe.sx/e/lsap1ofca3h5\" frameborder=\"0\"></iframe>\n" +
                "  </div>\n" +
                "  <div class=\"TPlayerTb\" id=\"OptE13\">\n" +
                "    <iframe class=\"no-you\" width=\"560\" height=\"315\" data-src=\"https://streamwish.to/e/yco1a4gez8k9\" frameborder=\"0\"></iframe>\n" +
                "  </div>\n" +
                "</div>";

        List<String> embeds = scraper.extractEmbedUrls(sampleHtml);
        assertEquals(3, embeds.size());
        assertTrue(embeds.contains("https://doodstream.com/e/c4lr0zhjl17i"));
        assertTrue(embeds.contains("https://voe.sx/e/lsap1ofca3h5"));
        assertTrue(embeds.contains("https://streamwish.to/e/yco1a4gez8k9"));
    }

    @Test
    public void cuevana3Scraper_isValidEmbed_validatesKnownHosts() {
        Cuevana3Scraper scraper = new Cuevana3Scraper();

        assertTrue(scraper.isValidEmbed("https://voe.sx/e/lsap1ofca3h5"));
        assertTrue(scraper.isValidEmbed("https://streamwish.to/e/yco1a4gez8k9"));
        assertTrue(scraper.isValidEmbed("https://vidhidepro.com/v/auj5qlx58xzz"));
        assertTrue(scraper.isValidEmbed("https://filemoon.sx/e/kq3m3x83pbmq"));
        assertTrue(scraper.isValidEmbed("https://doodstream.com/e/0x81zookyt1x"));
        assertTrue(scraper.isValidEmbed("https://fastplay.to/embed-b0rp2qa6xv89.html"));
        assertTrue(scraper.isValidEmbed("https://uqload.com/embed-j3ktlyrlc742.html"));

        assertFalse(scraper.isValidEmbed("https://google.com/search"));
        assertFalse(scraper.isValidEmbed("https://facebook.com/tr"));
        assertFalse(scraper.isValidEmbed(""));
        assertFalse(scraper.isValidEmbed(null));
        assertFalse(scraper.isValidEmbed("javascript:void(0)"));
    }

    @Test
    public void cuevana3Scraper_isValidEmbed_validatesAdditionalHosts() {
        Cuevana3Scraper scraper = new Cuevana3Scraper();

        assertTrue(scraper.isValidEmbed("https://streamlare.com/e/kARPonQd1yBlb1aE"));
        assertTrue(scraper.isValidEmbed("https://watchsb.com/e/y2c6j83ta67l"));
        assertTrue(scraper.isValidEmbed("https://v2.zplayer.live/embed/8fwbovz0aodn"));
        assertTrue(scraper.isValidEmbed("https://streamtape.com/e/DlDw14eP74CVkp"));
        assertTrue(scraper.isValidEmbed("https://videoapp.zip/e/movie/77338"));
        assertTrue(scraper.isValidEmbed("https://hlswish.com/e/o2onerpct4ii"));
        assertTrue(scraper.isValidEmbed("https://goodstream.one/embed-xv9nauat6z21.html"));
    }
}
