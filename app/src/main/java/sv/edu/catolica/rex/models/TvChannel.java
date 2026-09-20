package sv.edu.catolica.rex.models;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * Representa un canal de televisión en vivo.
 */
public class TvChannel implements Serializable {

    public static final String TYPE_HLS = "HLS";
    public static final String TYPE_YOUTUBE = "YOUTUBE";

    private String id;
    private String name;
    private String category;
    private String posterUrl;
    private String hlsUrl;
    private String dashUrl;
    private String streamType;
    private String country;
    private String badge;

    public TvChannel() {
        this.streamType = TYPE_HLS;
    }

    public TvChannel(String id, String name, String category, String posterUrl, String hlsUrl, String dashUrl, String streamType, String country) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.posterUrl = posterUrl;
        this.hlsUrl = hlsUrl;
        this.dashUrl = dashUrl;
        this.streamType = streamType != null ? streamType : TYPE_HLS;
        this.country = country;
        this.badge = "EN VIVO";
    }

    public TvChannel(String id, String name, String category, String posterUrl, String hlsUrl) {
        this(id, name, category, posterUrl, hlsUrl, null, TYPE_HLS, "SV");
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public void setPosterUrl(String posterUrl) {
        this.posterUrl = posterUrl;
    }

    public String getHlsUrl() {
        return hlsUrl;
    }

    public void setHlsUrl(String hlsUrl) {
        this.hlsUrl = hlsUrl;
    }

    public String getDashUrl() {
        return dashUrl;
    }

    public void setDashUrl(String dashUrl) {
        this.dashUrl = dashUrl;
    }

    public String getStreamType() {
        return streamType;
    }

    public void setStreamType(String streamType) {
        this.streamType = streamType;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getBadge() {
        return badge;
    }

    public void setBadge(String badge) {
        this.badge = badge;
    }

    public boolean isYouTube() {
        return TYPE_YOUTUBE.equalsIgnoreCase(streamType);
    }

    public ArrayList<String> getStreamList() {
        ArrayList<String> streams = new ArrayList<>();
        if (hlsUrl != null && !hlsUrl.trim().isEmpty()) {
            streams.add(hlsUrl.trim());
        }
        if (dashUrl != null && !dashUrl.trim().isEmpty()) {
            streams.add(dashUrl.trim());
        }
        return streams;
    }
}
