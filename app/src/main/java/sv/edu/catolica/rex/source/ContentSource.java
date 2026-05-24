package sv.edu.catolica.rex.source;

import java.util.List;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;
import sv.edu.catolica.rex.models.MediaItem;

public interface ContentSource {
    String getName();
    List<MediaItem> search(String query, int limit) throws Exception;
    MediaItem getBySlug(String slug) throws Exception;
    List<sv.edu.catolica.rex.network.AllCalidadScraper.Season> getSeasons(int postId) throws Exception;
    SmartPlaybackResult resolveMovie(MediaItem item) throws Exception;
    SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode) throws Exception;
}
