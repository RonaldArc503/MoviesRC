package sv.edu.catolica.rex.network.smart;

public class SmartPlaybackRequest {
    public String title;
    public int tmdbId;
    public String imdbId;
    public String mediaType;
    public SmartContentType contentType;
    public int seasonNumber;
    public int episodeNumber;

    public boolean isEpisodeRequest() {
        return seasonNumber > 0 && episodeNumber > 0;
    }

    public String cacheKey() {
        String imdb = imdbId == null ? "" : imdbId.trim();
        return (contentType != null ? contentType.name() : "UNKNOWN")
                + "|" + imdb
                + "|S" + seasonNumber
                + "|E" + episodeNumber;
    }
}
