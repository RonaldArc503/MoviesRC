package sv.edu.catolica.rex.models;

import com.google.firebase.Timestamp;

public class FavoriteItem {

    private String contentId;
    private String title;
    private String imageUrl;
    private String mediaType;
    private Timestamp addedAt;

    private String backdrop;
    private String detailUrl;
    private String fuente;
    private String anio;
    private double rating;
    private int postId;
    private int tmdbId;
    private String synopsis;

    public FavoriteItem() {
    }

    public FavoriteItem(String contentId, String title, String imageUrl, String mediaType) {
        this.contentId = contentId;
        this.title = title;
        this.imageUrl = imageUrl;
        this.mediaType = mediaType;
        this.addedAt = Timestamp.now();
    }

    public String getContentId() { return contentId; }
    public void setContentId(String contentId) { this.contentId = contentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public Timestamp getAddedAt() { return addedAt; }
    public void setAddedAt(Timestamp addedAt) { this.addedAt = addedAt; }

    public String getBackdrop() { return backdrop; }
    public void setBackdrop(String backdrop) { this.backdrop = backdrop; }

    public String getDetailUrl() { return detailUrl; }
    public void setDetailUrl(String detailUrl) { this.detailUrl = detailUrl; }

    public String getFuente() { return fuente; }
    public void setFuente(String fuente) { this.fuente = fuente; }

    public String getAnio() { return anio; }
    public void setAnio(String anio) { this.anio = anio; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public int getPostId() { return postId; }
    public void setPostId(int postId) { this.postId = postId; }

    public int getTmdbId() { return tmdbId; }
    public void setTmdbId(int tmdbId) { this.tmdbId = tmdbId; }

    public String getSynopsis() { return synopsis; }
    public void setSynopsis(String synopsis) { this.synopsis = synopsis; }
}
