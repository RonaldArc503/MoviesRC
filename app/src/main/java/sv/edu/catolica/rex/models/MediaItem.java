package sv.edu.catolica.rex.models;

import java.io.Serializable;

public class MediaItem implements Serializable {
    private String titulo, anio, imagen, detailUrl, fuente, mediaType;
    private int postId;
    private String synopsis;
    private boolean isDublado;

    public MediaItem(String titulo, String anio, String imagen, String detailUrl, int dummy) {
        this.titulo = titulo;
        this.anio = anio;
        this.imagen = imagen;
        this.detailUrl = detailUrl;
        this.postId = 0;
        this.synopsis = "";
    }

    public MediaItem(String titulo, String anio, String imagen, String detailUrl, int postId, boolean isDummyConstructor) {
        this.titulo = titulo;
        this.anio = anio;
        this.imagen = imagen;
        this.detailUrl = detailUrl;
        this.postId = postId;
        this.synopsis = "";
    }

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public String getAnio() { return anio; }
    public void setAnio(String anio) { this.anio = anio; }
    public String getImagen() { return imagen; }
    public void setImagen(String imagen) { this.imagen = imagen; }
    public String getDetailUrl() { return detailUrl; }
    public void setDetailUrl(String detailUrl) { this.detailUrl = detailUrl; }
    public String getFuente() { return fuente; }
    public void setFuente(String fuente) { this.fuente = fuente; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public int getPostId() { return postId; }
    public void setPostId(int postId) { this.postId = postId; }
    public String getSynopsis() { return synopsis; }
    public void setSynopsis(String synopsis) { this.synopsis = synopsis; }
    public boolean isDublado() { return isDublado; }
    public void setDublado(boolean dublado) { isDublado = dublado; }
}
