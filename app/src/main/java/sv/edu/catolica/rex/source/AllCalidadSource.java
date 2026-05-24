package sv.edu.catolica.rex.source;

import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public class AllCalidadSource implements ContentSource {
    @Override
    public String getName() { return "AllCalidad"; }

    @Override
    public List<MediaItem> search(String query, int limit) throws Exception {
        List<AllCalidadScraper.ContentItem> items = AllCalidadScraper.search(query, 1);
        List<MediaItem> mapped = new java.util.ArrayList<>();
        if (items == null || items.isEmpty()) {
            return mapped;
        }
        int max = Math.min(limit, items.size());
        for (int i = 0; i < max; i++) {
            mapped.add(AllCalidadScraper.toMediaItem(items.get(i)));
        }
        return mapped;
    }

    @Override
    public MediaItem getBySlug(String slug) throws Exception { return null; }

    @Override
    public List<AllCalidadScraper.Season> getSeasons(int postId) throws Exception {
        return AllCalidadScraper.getSeasons(postId);
    }

    @Override
    public SmartPlaybackResult resolveMovie(MediaItem item) throws Exception {
        if (item == null) return new SmartPlaybackResult();
        int postId = Math.max(0, item.getPostId());
        if (postId <= 0) return new SmartPlaybackResult();
        AllCalidadScraper.PlayerData player = AllCalidadScraper.getPlayer(postId);
        SmartPlaybackResult r = new SmartPlaybackResult();
        r.providerName = "AllCalidadApi";
        for (String u : AllCalidadScraper.getPlayableUrls(player)) r.hostUrls.add(u);
        return r;
    }

    @Override
    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode) throws Exception {
        // AllCalidad resolves episodes via episodePostId; not implemented here
        return new SmartPlaybackResult();
    }
}
