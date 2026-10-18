package sv.edu.catolica.rex.network.smart;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class FirebaseEndpointResolverTest {

    private FirebaseEndpointResolver resolver;

    @Before
    public void setUp() {
        resolver = new FirebaseEndpointResolver();
    }

    @Test
    public void resolve_invalidTmdbId_returnsNull() {
        assertNull(resolver.resolve(0, "movie", 0, 0));
        assertNull(resolver.resolve(-1, "movie", 0, 0));
    }

    @Test
    public void resolve_nonExistentTmdbId_returnsNull() {
        // ID que no existe en el catálogo
        SmartPlaybackResult result = resolver.resolve(999999999, "movie", 0, 0);
        assertNull(result);
    }

    @Test
    public void resolve_knownDarkKnightTmdb155_returnsPlayableResult() {
        // TMDb 155 = Batman: The Dark Knight, presente en el catálogo de Firebase
        SmartPlaybackResult result = resolver.resolve(155, "movie", 0, 0);
        assertNotNull("Batman TMDb 155 debe existir en Firebase", result);
        assertTrue("Debe contener URLs de reproducción", result.hasPlayableUrls());
        assertEquals("FirebaseCatalog", result.providerName);
        assertFalse("toPlayableUrls no debe estar vacío", result.toPlayableUrls().isEmpty());

        String firstUrl = result.toPlayableUrls().get(0);
        assertTrue("La URL debe ser un embed o servidor válido",
                firstUrl.startsWith("http://") || firstUrl.startsWith("https://"));
    }

    @Test
    public void testIsCurrentOrUpcomingYear() {
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        assertTrue(FirebaseEndpointResolver.isCurrentOrUpcomingYear(String.valueOf(currentYear)));
        assertTrue(FirebaseEndpointResolver.isCurrentOrUpcomingYear(String.valueOf(currentYear + 1)));
        assertFalse(FirebaseEndpointResolver.isCurrentOrUpcomingYear(String.valueOf(currentYear - 2)));
        assertFalse(FirebaseEndpointResolver.isCurrentOrUpcomingYear("2010"));
        assertFalse(FirebaseEndpointResolver.isCurrentOrUpcomingYear(null));
        assertFalse(FirebaseEndpointResolver.isCurrentOrUpcomingYear(""));
    }

    @Test
    public void testGetEndpointScore_currentYear_prioritizesVoesxAndDoodstreamOverAllcalidad() throws Exception {
        org.json.JSONObject voesx = new org.json.JSONObject();
        voesx.put("provider", "cuevana3");
        voesx.put("server", "voesx");
        voesx.put("url", "https://voe.sx/e/test123");

        org.json.JSONObject dood = new org.json.JSONObject();
        dood.put("provider", "cuevana3");
        dood.put("server", "doodstream");
        dood.put("url", "https://dood.to/e/test123");

        org.json.JSONObject allcalidad = new org.json.JSONObject();
        allcalidad.put("provider", "allcalidad");
        allcalidad.put("server", "vimeos");
        allcalidad.put("url", "https://vimeos.net/embed-test.html");

        // En año actual: doodstream (100) > streamwish (90) > voesx (80) > allcalidad (35)
        int doodScoreCurrent = FirebaseEndpointResolver.getEndpointScore(dood, true);
        int voeScoreCurrent = FirebaseEndpointResolver.getEndpointScore(voesx, true);
        int allcalidadScoreCurrent = FirebaseEndpointResolver.getEndpointScore(allcalidad, true);

        assertEquals(100, doodScoreCurrent);
        assertEquals(80, voeScoreCurrent);
        assertTrue(doodScoreCurrent > voeScoreCurrent);
        assertTrue(voeScoreCurrent > allcalidadScoreCurrent);

        // En años anteriores: allcalidad (100) > doodstream (80)
        int allcalidadScoreOld = FirebaseEndpointResolver.getEndpointScore(allcalidad, false);
        int doodScoreOld = FirebaseEndpointResolver.getEndpointScore(dood, false);
        assertEquals(100, allcalidadScoreOld);
        assertTrue(allcalidadScoreOld > doodScoreOld);
    }

    @Test
    public void testIsDirectPlayableEmbed() {
        assertFalse(FirebaseEndpointResolver.isDirectPlayableEmbed(null));
        assertFalse(FirebaseEndpointResolver.isDirectPlayableEmbed(""));
        assertFalse(FirebaseEndpointResolver.isDirectPlayableEmbed("https://wv3.cuevana3.eu/ver-pelicula/el-final-de-la-calle-oak"));
        assertFalse(FirebaseEndpointResolver.isDirectPlayableEmbed("https://wv3.cuevana3.eu/ver-serie/breaking-bad"));
        assertFalse(FirebaseEndpointResolver.isDirectPlayableEmbed("https://wv3.cuevana3.eu/pelicula/titanic"));
        assertFalse(FirebaseEndpointResolver.isDirectPlayableEmbed("https://allcalidad.re/pelicula/avatar-2"));

        assertTrue(FirebaseEndpointResolver.isDirectPlayableEmbed("https://doodstream.com/e/yfs4myeadm9v"));
        assertTrue(FirebaseEndpointResolver.isDirectPlayableEmbed("https://voe.sx/e/test123abc"));
        assertTrue(FirebaseEndpointResolver.isDirectPlayableEmbed("https://vimeos.net/embed-17oc2o65s6o6.html"));
        assertTrue(FirebaseEndpointResolver.isDirectPlayableEmbed("https://player.cuevana3.eu/player.php?h=abcdef"));
    }

    @Test
    public void testGetEndpointScore_rejectsDetailPageAndCuevanaDetail() throws Exception {
        org.json.JSONObject detail = new org.json.JSONObject();
        detail.put("provider", "cuevana3");
        detail.put("server", "cuevana_detail");
        detail.put("type", "detail_page");
        detail.put("url", "https://wv3.cuevana3.eu/ver-pelicula/test-movie");

        assertEquals(0, FirebaseEndpointResolver.getEndpointScore(detail, true));
        assertEquals(0, FirebaseEndpointResolver.getEndpointScore(detail, false));
    }

    @Test
    public void resolve_moana1108427_currentYear_returnsDoodstreamAsFirstServer() {
        SmartPlaybackResult result = resolver.resolve(1108427, "movie", 0, 0, "2026");
        assertNotNull("Moana TMDb 1108427 debe existir en Firebase", result);
        assertTrue(result.hasPlayableUrls());
        String firstUrl = result.toPlayableUrls().get(0);
        assertTrue("El primer servidor para Moana (2026) debe ser doodstream (" + firstUrl + ")",
                firstUrl.contains("dood"));
    }

    @Test
    public void resolve_oak1101383_returnsPlayableEmbedsWithoutCuevanaWebPage() {
        SmartPlaybackResult result = resolver.resolve(1101383, "movie", 0, 0, "2026");
        assertNotNull("Oak Street TMDb 1101383 debe resolver", result);
        assertTrue("Debe tener URLs reproducibles", result.hasPlayableUrls());
        for (String url : result.toPlayableUrls()) {
            assertFalse("Ninguna URL debe ser página web de Cuevana (" + url + ")",
                    url.contains("/ver-pelicula/") || (url.contains("cuevana3.eu") && !url.contains("player.cuevana3.eu")));
        }
        String firstUrl = result.toPlayableUrls().get(0);
        assertTrue("El primer servidor debe ser doodstream", firstUrl.contains("dood"));
    }

    @Test
    public void resolve_odisea1368337_returnsVimeosEmbedWithoutDetailPage() {
        SmartPlaybackResult result = resolver.resolve(1368337, "movie", 0, 0, "2026");
        assertNotNull("La Odisea TMDb 1368337 debe resolver", result);
        assertTrue("Debe tener URLs reproducibles", result.hasPlayableUrls());
        for (String url : result.toPlayableUrls()) {
            assertFalse("Ninguna URL debe ser página web de Cuevana (" + url + ")",
                    url.contains("/ver-pelicula/"));
        }
        String firstUrl = result.toPlayableUrls().get(0);
        assertTrue("El servidor debe ser vimeos (" + firstUrl + ")", firstUrl.contains("vimeos"));
    }
}
