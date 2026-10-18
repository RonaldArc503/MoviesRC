package sv.edu.catolica.rex.network;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.List;

public class AllCalidadScraperTest {

    @Test
    public void getSignificantWords_sortsByLengthDescendingAndIgnoresStopwords() {
        List<String> words = AllCalidadScraper.getSignificantWords("Amigos intocables");
        assertNotNull(words);
        assertTrue(words.size() >= 2);
        assertEquals("intocables", words.get(0)); // longest and most specific first
        assertEquals("amigos", words.get(1));
    }

    @Test
    public void getSignificantWords_filtersStopWords() {
        List<String> words = AllCalidadScraper.getSignificantWords("El señor de los anillos: Las dos torres");
        assertNotNull(words);
        assertFalse(words.contains("el"));
        assertFalse(words.contains("de"));
        assertFalse(words.contains("los"));
        assertFalse(words.contains("las"));
        assertFalse(words.contains("dos")); // less than 4 or stopword
        assertTrue(words.contains("anillos"));
        assertTrue(words.contains("torres"));
        assertTrue(words.contains("senor") || words.contains("señor"));
    }

    @Test
    public void getPlayableUrls_preservesAlternativeServers() {
        AllCalidadScraper.PlayerData pd = new AllCalidadScraper.PlayerData();
        pd.embedUrl = "https://vimeos.net/embed-123.html";

        AllCalidadScraper.Server s1 = new AllCalidadScraper.Server();
        s1.name = "Vimeos";
        s1.url = "https://vimeos.net/embed-123.html";

        AllCalidadScraper.Server s2 = new AllCalidadScraper.Server();
        s2.name = "Voe";
        s2.url = "https://voe.sx/e/abc";

        AllCalidadScraper.Server s3 = new AllCalidadScraper.Server();
        s3.name = "Filemoon";
        s3.url = "https://filemoon.sx/e/xyz";

        pd.servers.add(s1);
        pd.servers.add(s2);
        pd.servers.add(s3);

        List<String> playable = AllCalidadScraper.getPlayableUrls(pd);
        assertEquals(3, playable.size());
        assertEquals("https://vimeos.net/embed-123.html", playable.get(0));
        assertEquals("https://voe.sx/e/abc", playable.get(1));
        assertEquals("https://filemoon.sx/e/xyz", playable.get(2));
    }
}
