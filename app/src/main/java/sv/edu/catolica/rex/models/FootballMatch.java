package sv.edu.catolica.rex.models;

import java.util.List;

/**
 * Representa un partido de fútbol con sus fuentes de stream disponibles.
 */
public class FootballMatch {

    private String title;       // "UEFA Champions League (Final): PSG vs Arsenal"
    private String competition; // "UEFA Champions League"
    private String time;        // "10:00"
    private String category;    // clase CSS del li, ej: "CHA" (para identificar competición)
    private List<FootballStream> streams;

    public FootballMatch() {}

    public FootballMatch(String title, String competition, String time,
                         String category, List<FootballStream> streams) {
        this.title = title;
        this.competition = competition;
        this.time = time;
        this.category = category;
        this.streams = streams;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCompetition() { return competition; }
    public void setCompetition(String competition) { this.competition = competition; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public List<FootballStream> getStreams() { return streams; }
    public void setStreams(List<FootballStream> streams) { this.streams = streams; }

    @Override
    public String toString() {
        return "FootballMatch{title='" + title + "', time='" + time +
                "', streams=" + (streams != null ? streams.size() : 0) + "}";
    }

    // ─── Stream interno ────────────────────────────────────────────────────────

    public static class FootballStream {
        private String channelName;  // "TVE La 1 Español (Recomendado)"
        private String quality;      // "1080p"
        private String eventsUrl;    // URL completa a eventos.html?r=BASE64
        private String embedUrl;     // URL decodificada del parámetro r= (embed real)
        private String playableUrl;  // URL directa final (.m3u8/.mpd/.mp4), si ya fue resuelta

        public FootballStream() {}

        public FootballStream(String channelName, String quality,
                               String eventsUrl, String embedUrl) {
            this.channelName = channelName;
            this.quality = quality;
            this.eventsUrl = eventsUrl;
            this.embedUrl = embedUrl;
        }

        public String getChannelName() { return channelName; }
        public void setChannelName(String channelName) { this.channelName = channelName; }

        public String getQuality() { return quality; }
        public void setQuality(String quality) { this.quality = quality; }

        public String getEventsUrl() { return eventsUrl; }
        public void setEventsUrl(String eventsUrl) { this.eventsUrl = eventsUrl; }

        public String getEmbedUrl() { return embedUrl; }
        public void setEmbedUrl(String embedUrl) { this.embedUrl = embedUrl; }

        public String getPlayableUrl() { return playableUrl; }
        public void setPlayableUrl(String playableUrl) { this.playableUrl = playableUrl; }

        @Override
        public String toString() {
            return "FootballStream{channel='" + channelName + "', quality='" + quality +
                    "', embed='" + embedUrl + "'}";
        }
    }
}
