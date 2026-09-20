package sv.edu.catolica.rex.network.smart;

public class SmartPlaybackRequest {
    public String title;
    public String detailUrl;
    public int tmdbId;
    public String imdbId;
    public int postId;
    public int episodePostId;
    public String mediaType;
    public SmartContentType contentType;
    public int seasonNumber;
    public int episodeNumber;

    public boolean isEpisodeRequest() {
        return seasonNumber > 0 && episodeNumber > 0;
    }

    public String cacheKey() {
        String identity;
        if (postId > 0) {
            identity = "P" + postId + (episodePostId > 0 ? "_E" + episodePostId : "");
        } else if (tmdbId > 0) {
            identity = "T" + tmdbId;
        } else {
            String t = title == null ? "" : title.trim().toLowerCase();
            identity = "N" + (t.isEmpty() ? "unknown" : t);
        }
        return (contentType != null ? contentType.name() : "UNKNOWN")
                + "|" + identity
                + "|S" + seasonNumber
                + "|E" + episodeNumber;
    }
}
