package sv.edu.catolica.rex.models;

import com.google.firebase.Timestamp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserData {

    private String uid;
    private String displayName;
    private String email;
    private String photoUrl;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    private List<ContinueWatchingItem> continueWatching;
    private List<WatchedHistoryItem> history;
    private List<FavoriteItem> favorites;
    private Map<String, PersonalList> personalLists;
    private UserSettings settings;

    public UserData() {
        this.continueWatching = new ArrayList<>();
        this.history = new ArrayList<>();
        this.favorites = new ArrayList<>();
        this.personalLists = new HashMap<>();
        this.settings = new UserSettings();
    }

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    public List<ContinueWatchingItem> getContinueWatching() { return continueWatching; }
    public void setContinueWatching(List<ContinueWatchingItem> continueWatching) { this.continueWatching = continueWatching; }

    public List<WatchedHistoryItem> getHistory() { return history; }
    public void setHistory(List<WatchedHistoryItem> history) { this.history = history; }

    public List<FavoriteItem> getFavorites() { return favorites; }
    public void setFavorites(List<FavoriteItem> favorites) { this.favorites = favorites; }

    public Map<String, PersonalList> getPersonalLists() { return personalLists; }
    public void setPersonalLists(Map<String, PersonalList> personalLists) { this.personalLists = personalLists; }

    public UserSettings getSettings() { return settings; }
    public void setSettings(UserSettings settings) { this.settings = settings; }
}
