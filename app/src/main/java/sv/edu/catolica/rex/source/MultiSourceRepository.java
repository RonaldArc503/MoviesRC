package sv.edu.catolica.rex.source;

import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public class MultiSourceRepository {
    private final List<ContentSource> sources = new ArrayList<>();

    public void registerSource(ContentSource s) {
        if (s == null) return; sources.add(s);
    }

    public List<ContentSource> getSources() {
        return new ArrayList<>(sources);
    }

    public List<MediaItem> searchAll(String q, int limit) {
        List<MediaItem> all = new ArrayList<>();
        for (ContentSource s : sources) {
            try {
                List<MediaItem> r = s.search(q, limit);
                if (r != null && !r.isEmpty()) all.addAll(r);
            } catch (Exception ignored) { }
        }
        return all;
    }

    public List<MediaItem> searchAllOrdered(String q, int limit) {
        List<MediaItem> all = new ArrayList<>();
        for (ContentSource s : sources) {
            try {
                if (s == null) continue;
                List<MediaItem> r = s.search(q, limit);
                if (r != null && !r.isEmpty()) {
                    all.addAll(r);
                }
            } catch (Exception ignored) { }
        }
        return all;
    }

    public SmartPlaybackResult resolveWithFallback(MediaItem item) {
        for (ContentSource s : sources) {
            try {
                SmartPlaybackResult r = s.resolveMovie(item);
                if (r != null && r.hasPlayableUrls()) return r;
            } catch (Exception ignored) { }
        }
        return new SmartPlaybackResult();
    }

    public SmartPlaybackResult resolveEpisodeWithFallback(MediaItem item, int season, int episode) {
        for (ContentSource s : sources) {
            try {
                SmartPlaybackResult r = s.resolveEpisode(item, season, episode);
                if (r != null && r.hasPlayableUrls()) return r;
            } catch (Exception ignored) { }
        }
        return new SmartPlaybackResult();
    }
}
