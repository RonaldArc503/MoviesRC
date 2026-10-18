package sv.edu.catolica.rex.source;

import java.util.ArrayList;
import java.util.List;

import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.smart.CanelaScraper;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public class CanelaSource implements ContentSource {

    private final CanelaScraper scraper;

    public CanelaSource() {
        this.scraper = new CanelaScraper();
    }

    public CanelaSource(CanelaScraper scraper) {
        this.scraper = scraper != null ? scraper : new CanelaScraper();
    }

    public CanelaScraper getScraper() {
        return scraper;
    }

    @Override
    public String getName() {
        return "Novelas";
    }

    @Override
    public List<MediaItem> search(String query, int limit) throws Exception {
        return scraper.search(query, limit);
    }

    @Override
    public MediaItem getBySlug(String slug) throws Exception {
        return null;
    }

    @Override
    public List<AllCalidadScraper.Season> getSeasons(int postId) throws Exception {
        return new ArrayList<>();
    }

    public List<AllCalidadScraper.Season> getSeasons(String seriesUrlOrSlug) {
        return scraper.getSeasons(seriesUrlOrSlug);
    }

    @Override
    public SmartPlaybackResult resolveMovie(MediaItem item) throws Exception {
        return scraper.resolveMovie(item);
    }

    @Override
    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode) throws Exception {
        return scraper.resolveEpisode(item, season, episode, 0);
    }

    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode, int episodeId) {
        return scraper.resolveEpisode(item, season, episode, episodeId);
    }
}
