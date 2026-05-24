package sv.edu.catolica.rex.source;

import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;

public class UnifiedSearchService {
    private final MultiSourceRepository repo;

    public UnifiedSearchService(MultiSourceRepository repo) { this.repo = repo; }

    public List<MediaItem> search(String q, int limit) {
        return repo.searchAll(q, limit);
    }
}
