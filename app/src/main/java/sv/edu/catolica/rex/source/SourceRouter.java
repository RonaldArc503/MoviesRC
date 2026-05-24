package sv.edu.catolica.rex.source;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;

public class SourceRouter {
    private final MultiSourceRepository repository;
    private final SourcePriorityManager priorityManager;

    public SourceRouter(Context context, MultiSourceRepository repository) {
        this.repository = repository;
        this.priorityManager = new SourcePriorityManager(context);
    }

    public SmartPlaybackResult resolveMovie(Context context, MediaItem item) {
        return resolve(context, item, false, 0, 0);
    }

    public SmartPlaybackResult resolveEpisode(Context context, MediaItem item, int season, int episode) {
        return resolve(context, item, true, season, episode);
    }

    private SmartPlaybackResult resolve(Context context, MediaItem item, boolean episode, int season, int episodeNumber) {
        if (item == null) return new SmartPlaybackResult();

        String cacheKey = SourceAwareCache.key(
                item.getFuente(), item.getImdbId(), item.getTmdbId(), item.getTitulo(), item.getAnio(), season, episodeNumber);
        SmartPlaybackResult cached = SourceAwareCache.get(context, cacheKey);
        if (cached != null && cached.hasPlayableUrls()) {
            return cached;
        }

        List<ContentSource> ordered = new ArrayList<>();
        for (String name : priorityManager.getPriorityList()) {
            ContentSource source = findByName(name);
            if (source != null) ordered.add(source);
        }

        for (ContentSource source : ordered) {
            try {
                SmartPlaybackResult result = episode
                        ? source.resolveEpisode(item, season, episodeNumber)
                        : source.resolveMovie(item);
                if (result != null && result.hasPlayableUrls()) {
                    SourceAwareCache.put(context, cacheKey, result);
                    return result;
                }
            } catch (Exception ignored) {
            }
        }

        return new SmartPlaybackResult();
    }

    private ContentSource findByName(String name) {
        if (name == null) return null;
        String normalized = name.trim().toLowerCase();
        if (normalized.isEmpty()) return null;

        for (ContentSource source : repository.getSources()) {
            if (source != null && source.getName() != null && source.getName().trim().toLowerCase().equals(normalized)) {
                return source;
            }
        }
        return null;
    }
}
