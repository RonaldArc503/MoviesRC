package sv.edu.catolica.rex.models;

import java.util.List;

public class MediaDetail {
    private String title, description, poster;
    private List<Season> seasons;
    private List<RelatedMedia> related;
    private boolean hasLatino;

    public static class RelatedMedia {
        private String title, type, poster, url;
        public RelatedMedia(String title, String type, String poster, String url) {
            this.title = title; this.type = type; this.poster = poster; this.url = url;
        }
        public String getTitle() { return title; }
        public String getType() { return type; }
        public String getPoster() { return poster; }
        public String getUrl() { return url; }
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPoster() { return poster; }
    public void setPoster(String poster) { this.poster = poster; }
    public List<Season> getSeasons() { return seasons; }
    public void setSeasons(List<Season> seasons) { this.seasons = seasons; }
    public List<RelatedMedia> getRelated() { return related; }
    public void setRelated(List<RelatedMedia> related) { this.related = related; }
    public boolean isHasLatino() { return hasLatino; }
    public void setHasLatino(boolean hasLatino) { this.hasLatino = hasLatino; }
}
