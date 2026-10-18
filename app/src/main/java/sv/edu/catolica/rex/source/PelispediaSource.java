package sv.edu.catolica.rex.source;

import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.smart.SmartPlaybackResult;
import sv.edu.catolica.rex.network.smart.PelispediaScraper;
import sv.edu.catolica.rex.source.SourceMatcher;
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
        String detailUrl = item.getDetailUrl();
        if (detailUrl == null || !detailUrl.toLowerCase().contains("pelispedia")) {
            String q = item.getTitulo();
            if (q == null || q.trim().isEmpty()) return new SmartPlaybackResult();
            List<MediaItem> results = scraper.search(q, 5);
            MediaItem best = pickBestMatch(results, q);
            if (best != null) return scraper.resolveMovie(best);
            return new SmartPlaybackResult();
        }
        return scraper.resolveMovie(item);
    }

    @Override
    public SmartPlaybackResult resolveEpisode(MediaItem item, int season, int episode) throws Exception {
        if (item == null) return new SmartPlaybackResult();
        String detailUrl = item.getDetailUrl();
        if (detailUrl == null || !detailUrl.toLowerCase().contains("pelispedia")) {
            String q = item.getTitulo();
            if (q == null || q.trim().isEmpty()) return new SmartPlaybackResult();
            List<MediaItem> results = scraper.search(q, 5);
            MediaItem best = pickBestMatch(results, q);
            if (best != null) return scraper.resolveEpisode(best, season, episode);
            return new SmartPlaybackResult();
        }
        return scraper.resolveEpisode(item, season, episode);
    }

    /**
     * Elige el mejor resultado de búsqueda por solapamiento de palabras con el
     * título solicitado. Exige que al menos la mitad de las palabras del título
     * (o el 60% del mejor score de similitud) coincidan; nunca toma el primer
     * resultado a ciegas, para no resolver contenido distinto.
     */
    private MediaItem pickBestMatch(List<MediaItem> results, String query) {
        if (results == null || results.isEmpty()) return null;
        String qNorm = SourceMatcher.normalize(query);
        if (qNorm.isEmpty()) return null;
        String[] qWords = qNorm.split("\\s+");

        MediaItem best = null;
        int bestScore = 0;
        for (MediaItem r : results) {
            String rNorm = SourceMatcher.normalize(r.getTitulo());
            if (rNorm.isEmpty()) continue;
            String[] rWords = rNorm.split("\\s+");
            int overlap = 0;
            for (String qw : qWords) {
                if (qw.length() < 2) continue;
                for (String rw : rWords) {
                    if (rw.length() < 2) continue;
                    if (qw.equals(rw)
                            || (qw.length() > 3 && rw.startsWith(qw))
                            || (rw.length() > 3 && qw.startsWith(rw))) {
                        overlap++;
                        break;
                    }
                }
            }
            int meaningful = 0;
            for (String qw : qWords) {
                if (qw.length() >= 2) meaningful++;
            }
            if (meaningful == 0) continue;
            int score = (overlap * 100) / meaningful;
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        if (bestScore < 60) return null;
        return best;
    }
}
