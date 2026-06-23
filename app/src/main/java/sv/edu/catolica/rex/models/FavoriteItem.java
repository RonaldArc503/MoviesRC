package sv.edu.catolica.rex.models;

import com.google.firebase.Timestamp;

public class FavoriteItem {

    private String contentId;
    private String title;
    private String imageUrl;
    private String mediaType;
    private Timestamp addedAt;

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
}
