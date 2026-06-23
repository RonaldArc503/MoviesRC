package sv.edu.catolica.rex.models;

import com.google.firebase.Timestamp;

import java.util.HashMap;
import java.util.Map;

public class UserSettings {

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String LANGUAGE_ES = "es";
    public static final String LANGUAGE_EN = "en";
    public static final String QUALITY_AUTO = "auto";
    public static final String QUALITY_720P = "720p";
    public static final String QUALITY_1080P = "1080p";
    public static final String QUALITY_4K = "4k";

    private String theme;
    private String language;
    private String defaultQuality;
    private boolean autoplayNext;
    private Timestamp updatedAt;

    public UserSettings() {
        this.theme = THEME_DARK;
        this.language = LANGUAGE_ES;
        this.defaultQuality = QUALITY_AUTO;
        this.autoplayNext = true;
        this.updatedAt = Timestamp.now();
    }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getDefaultQuality() { return defaultQuality; }
    public void setDefaultQuality(String defaultQuality) { this.defaultQuality = defaultQuality; }

    public boolean isAutoplayNext() { return autoplayNext; }
    public void setAutoplayNext(boolean autoplayNext) { this.autoplayNext = autoplayNext; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("theme", theme);
        map.put("language", language);
        map.put("defaultQuality", defaultQuality);
        map.put("autoplayNext", autoplayNext);
        map.put("updatedAt", Timestamp.now());
        return map;
    }
}
