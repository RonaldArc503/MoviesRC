package sv.edu.catolica.rex.source;

import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;
import sv.edu.catolica.rex.network.smart.PelispediaScraper;

public class PelispediaSource implements ContentSource {
    private final PelispediaScraper scraper = new PelispediaScraper();

    @Override
    public String getName() { return "Pelispedia"; }

    @Override
    public List<MediaItem> search(String query, int limit) throws Exception {
        return scraper.search(query, limit);
    }

    @Override
    public MediaItem getBySlug(String slug) throws Exception {
        return scraper.fetchBySlug(slug);
    }

    @Override
    public List<AllCalidadScraper.Season> getSeasons(int postId) throws Exception {
        // Pelispedia does not use AllCalidad seasons; return empty
        return new java.util.ArrayList<>();
    }

    @Override
    public SmartPlaybackResult resolveMovie(MediaItem item) throws Exception {
        if (item == null) return new SmartPlaybackResult();
        if (item.getDetailUrl() == null || item.getDetailUrl().trim().isEmpty()) {
            String q = item.getTitulo();
            if (q == null || q.trim().isEmpty()) return new SmartPlaybackResult();
            List<MediaItem> results = scraper.search(q, 5);
            if (results != null && !results.isEmpty()) {
                MediaItem first = results.get(0);
                return scraper.resolveMovie(first);
            }
            return new SmartPlaybackResult();
        }
        return scraper.resolveMovie(item);
    }

    @Override
    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode) throws Exception {
        if (item == null) return new SmartPlaybackResult();
        if (item.getDetailUrl() == null || item.getDetailUrl().trim().isEmpty()) {
            String q = item.getTitulo();
            if (q == null || q.trim().isEmpty()) return new SmartPlaybackResult();
            List<MediaItem> results = scraper.search(q, 5);
            if (results != null && !results.isEmpty()) {
                MediaItem first = results.get(0);
                return scraper.resolveEpisode(first, season, episode);
            }
            return new SmartPlaybackResult();
        }
        return scraper.resolveEpisode(item, season, episode);
    }
}
