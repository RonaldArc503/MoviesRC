package sv.edu.catolica.rex.source;

import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.models.MediaItem;

public final class TmdbContentMatcher {
    private TmdbContentMatcher() {}

    public static MediaItem mergeMetadata(MediaItem base, MediaItem tmdbItem) {
        if (base == null) return tmdbItem;
        if (tmdbItem == null) return base;
        MediaItem out = new MediaItem(
                base.getTitulo() != null && !base.getTitulo().isEmpty() ? base.getTitulo() : tmdbItem.getTitulo(),
                base.getAnio() != null && !base.getAnio().isEmpty() ? base.getAnio() : tmdbItem.getAnio(),
                base.getImagen() != null && !base.getImagen().isEmpty() ? base.getImagen() : tmdbItem.getImagen(),
                base.getDetailUrl() != null && !base.getDetailUrl().isEmpty() ? base.getDetailUrl() : tmdbItem.getDetailUrl(),
                base.getPostId() > 0 ? base.getPostId() : tmdbItem.getPostId());
        out.setBackdrop(nonEmpty(base.getBackdrop(), tmdbItem.getBackdrop()));
        out.setFuente(nonEmpty(base.getFuente(), tmdbItem.getFuente()));
        out.setMediaType(nonEmpty(base.getMediaType(), tmdbItem.getMediaType()));
        out.setSynopsis(nonEmpty(base.getSynopsis(), tmdbItem.getSynopsis()));
        out.setRating(base.getRating() > 0 ? base.getRating() : tmdbItem.getRating());
        out.setTmdbId(base.getTmdbId() > 0 ? base.getTmdbId() : tmdbItem.getTmdbId());
        out.setImdbId(nonEmpty(base.getImdbId(), tmdbItem.getImdbId()));
        out.setDublado(base.isDublado() || tmdbItem.isDublado());
        out.setProgress(Math.max(base.getProgress(), tmdbItem.getProgress()));
        return out;
    }

    public static List<MediaItem> mergeLists(List<MediaItem> first, List<MediaItem> second) {
        List<MediaItem> out = new ArrayList<>();
        if (first != null) out.addAll(first);
        if (second != null) out.addAll(second);
        return out;
    }

    public static boolean isDrama(MediaItem item) {
        if (item == null) return false;
        String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase();
        String title = item.getTitulo() == null ? "" : item.getTitulo().toLowerCase();
        return type.contains("dorama") || type.contains("drama") || title.contains("dorama") || title.contains("k-drama");
    }

    public static boolean isAnime(MediaItem item) {
        if (item == null) return false;
        String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase();
        String title = item.getTitulo() == null ? "" : item.getTitulo().toLowerCase();
        return type.contains("anime") || title.contains("anime");
    }

    private static String nonEmpty(String left, String right) {
        if (left != null && !left.trim().isEmpty()) return left;
        return right == null ? "" : right;
    }
}
