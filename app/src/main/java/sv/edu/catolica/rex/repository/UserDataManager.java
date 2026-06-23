package sv.edu.catolica.rex.repository;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.FavoriteItem;
import sv.edu.catolica.rex.models.PersonalList;
import sv.edu.catolica.rex.models.UserData;
import sv.edu.catolica.rex.models.UserSettings;
import sv.edu.catolica.rex.models.WatchedHistoryItem;
import sv.edu.catolica.rex.storage.ContinueWatchingStore;

public class UserDataManager {

    private static final String TAG = "UserDataManager";
    private static final String FIELD_UPDATED_AT = "updatedAt";

    private static UserDataManager instance;

    private final Context appContext;
    private final FirebaseAuth firebaseAuth;
    private final FirestoreRepository firestoreRepo;
    private final SyncQueueManager syncQueue;
    private final ContinueWatchingStore cwStore;
    private ListenerRegistration snapshotListener;
    private boolean restoreInProgress = false;
    private boolean restoreComplete = false;

    // Local caches
    private final List<FavoriteItem> favoritesCache = new ArrayList<>();
    private final List<WatchedHistoryItem> historyCache = new ArrayList<>();
    private final Map<String, PersonalList> listsCache = new HashMap<>();
    private UserSettings settingsCache = new UserSettings();

    public interface RestoreCallback {
        void onRestoreComplete(boolean fromFirestore);
        void onError(Exception e);
    }

    public interface DataChangeListener {
        void onFavoritesChanged(List<FavoriteItem> favorites);
        void onHistoryChanged(List<WatchedHistoryItem> history);
        void onSettingsChanged(UserSettings settings);
    }

    private final List<DataChangeListener> dataListeners = new ArrayList<>();

    private UserDataManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.firebaseAuth = FirebaseAuth.getInstance();
        this.firestoreRepo = new FirestoreRepository(
                com.google.firebase.firestore.FirebaseFirestore.getInstance());
        this.syncQueue = SyncQueueManager.getInstance(context);
        this.cwStore = new ContinueWatchingStore();
    }

    public static synchronized UserDataManager getInstance(Context context) {
        if (instance == null) {
            instance = new UserDataManager(context);
        }
        return instance;
    }

    public void addDataChangeListener(DataChangeListener listener) {
        dataListeners.add(listener);
    }

    public void removeDataChangeListener(DataChangeListener listener) {
        dataListeners.remove(listener);
    }

    public boolean isRestoreComplete() { return restoreComplete; }
    public boolean isRestoreInProgress() { return restoreInProgress; }

    public List<FavoriteItem> getFavorites() { return new ArrayList<>(favoritesCache); }
    public List<WatchedHistoryItem> getHistory() { return new ArrayList<>(historyCache); }
    public UserSettings getSettings() { return settingsCache; }

    public boolean isFavorite(String contentId) {
        for (FavoriteItem f : favoritesCache) {
            if (f.getContentId() != null && f.getContentId().equals(contentId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Restore all user data from Firestore into local storage.
     * Called after successful login.
     */
    public void restoreFromFirestore(final RestoreCallback callback) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) {
            if (callback != null) callback.onError(new Exception("User not authenticated"));
            return;
        }

        String uid = user.getUid();
        restoreInProgress = true;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                DocumentSnapshot snapshot = Tasks.await(firestoreRepo.getUserData(uid));

                if (snapshot.exists()) {
                    UserData remoteData = snapshot.toObject(UserData.class);

                    if (remoteData != null) {
                        boolean hasLocalData = cwStore.hasData(appContext);

                        // Resolve conflicts: compare updatedAt
                        Timestamp remoteUpdated = remoteData.getUpdatedAt();
                        Timestamp localUpdated = getLocalUpdatedAt();

                        boolean useRemote = shouldUseRemote(hasLocalData, remoteUpdated, localUpdated);

                        if (useRemote) {
                            applyRemoteData(remoteData);
                        } else {
                            pushLocalData(uid);
                        }

                        restoreComplete = true;
                        restoreInProgress = false;
                        startRealtimeSync(uid);

                        if (callback != null) callback.onRestoreComplete(useRemote);
                    } else {
                        restoreComplete = true;
                        restoreInProgress = false;
                        pushLocalData(uid);
                        if (callback != null) callback.onRestoreComplete(false);
                    }
                } else {
                    // No Firestore data — push local if any
                    if (cwStore.hasData(appContext)) {
                        pushLocalData(uid);
                    } else {
                        createInitialUserData(uid, user);
                    }
                    restoreComplete = true;
                    restoreInProgress = false;
                    if (callback != null) callback.onRestoreComplete(false);
                }
            } catch (Exception e) {
                Log.e(TAG, "restoreFromFirestore error", e);
                restoreInProgress = false;
                restoreComplete = true;
                if (callback != null) callback.onError(e);
            }
        });
    }

    private boolean shouldUseRemote(boolean hasLocalData,
                                     @Nullable Timestamp remoteUpdated,
                                     @Nullable Timestamp localUpdated) {
        if (!hasLocalData) return true;
        if (remoteUpdated == null && localUpdated == null) return false;
        if (remoteUpdated == null) return false;
        if (localUpdated == null) return true;
        return remoteUpdated.compareTo(localUpdated) >= 0;
    }

    private void applyRemoteData(UserData remote) {
        if (remote.getContinueWatching() != null) {
            for (ContinueWatchingItem item : remote.getContinueWatching()) {
                cwStore.save(appContext, item);
            }
        }
        if (remote.getFavorites() != null) {
            favoritesCache.clear();
            favoritesCache.addAll(remote.getFavorites());
        }
        if (remote.getHistory() != null) {
            historyCache.clear();
            historyCache.addAll(remote.getHistory());
        }
        if (remote.getPersonalLists() != null) {
            listsCache.clear();
            listsCache.putAll(remote.getPersonalLists());
        }
        if (remote.getSettings() != null) {
            settingsCache = remote.getSettings();
        }
    }

    private void pushLocalData(String uid) {
        try {
            List<ContinueWatchingItem> localCW = cwStore.getAll(appContext);
            if (localCW != null && !localCW.isEmpty()) {
                Tasks.await(firestoreRepo.setContinueWatchingList(uid, localCW));
            }
            if (!favoritesCache.isEmpty()) {
                Tasks.await(firestoreRepo.setFavoritesList(uid, favoritesCache));
            }
            if (!historyCache.isEmpty()) {
                Tasks.await(firestoreRepo.setHistoryList(uid, historyCache));
            }
        } catch (Exception e) {
            Log.w(TAG, "pushLocalData error", e);
        }
    }

    private void createInitialUserData(String uid, FirebaseUser user) {
        UserData data = new UserData();
        data.setUid(uid);
        data.setDisplayName(user.getDisplayName());
        data.setEmail(user.getEmail());
        data.setPhotoUrl(user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null);
        data.setCreatedAt(Timestamp.now());
        data.setUpdatedAt(Timestamp.now());
        data.setSettings(new UserSettings());

        firestoreRepo.saveUserData(uid, data)
                .addOnFailureListener(e -> Log.w(TAG, "Error creating initial user data", e));
    }

    private void startRealtimeSync(String uid) {
        if (snapshotListener != null) {
            snapshotListener.remove();
        }
        snapshotListener = firestoreRepo.listenUserData(uid,
                new FirestoreRepository.OnUserDataChangedListener() {
                    @Override
                    public void onDataChanged(UserData data) {
                        if (data == null || !restoreComplete) return;
                        processRemoteUpdate(data);
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "Realtime sync error", e);
                    }
                });
    }

    private void processRemoteUpdate(UserData remote) {
        resolveAndApply(remote);
        processPendingQueue();
    }

    private void resolveAndApply(UserData remote) {
        Timestamp remoteUpdated = remote.getUpdatedAt();
        Timestamp localUpdated = getLocalUpdatedAt();

        if (shouldUseRemote(true, remoteUpdated, localUpdated)) {
            applyRemoteData(remote);
            notifyListeners();
        }
    }

    public void onLocalDataChanged(String uid, String type, Object payload) {
        syncQueue.enqueue(new SyncOperation(type, payload));
        if (restoreComplete) {
            syncImmediately(uid, type, payload);
        }
    }

    private void syncImmediately(String uid, String type, Object payload) {
        try {
            switch (type) {
                case SyncOperation.TYPE_SAVE_CONTINUE_WATCHING:
                    if (payload instanceof ContinueWatchingItem) {
                        Tasks.await(firestoreRepo.saveContinueWatching(uid,
                                (ContinueWatchingItem) payload));
                    }
                    break;
                case SyncOperation.TYPE_ADD_FAVORITE:
                    if (payload instanceof FavoriteItem) {
                        Tasks.await(firestoreRepo.addFavorite(uid,
                                (FavoriteItem) payload));
                    }
                    break;
                case SyncOperation.TYPE_REMOVE_FAVORITE:
                    if (payload instanceof String) {
                        Tasks.await(firestoreRepo.removeFavorite(uid, (String) payload));
                    }
                    break;
                case SyncOperation.TYPE_ADD_HISTORY:
                    if (payload instanceof WatchedHistoryItem) {
                        Tasks.await(firestoreRepo.addToHistory(uid,
                                (WatchedHistoryItem) payload));
                    }
                    break;
                case SyncOperation.TYPE_SAVE_SETTINGS:
                    if (payload instanceof UserSettings) {
                        Tasks.await(firestoreRepo.saveSettings(uid,
                                (UserSettings) payload));
                    }
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "syncImmediately failed for " + type + ", queued for retry", e);
        }
    }

    private void processPendingQueue() {
        if (!syncQueue.hasPending()) return;
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        String uid = user.getUid();
        Gson gson = new Gson();
        List<SyncOperation> pending = syncQueue.getPending();
        List<SyncOperation> processed = new ArrayList<>();

        for (SyncOperation op : pending) {
            try {
                Object payload = deserializePayload(op.getType(), op.getPayloadJson(), gson);
                if (payload != null) {
                    syncImmediately(uid, op.getType(), payload);
                }
                processed.add(op);
            } catch (Exception e) {
                op.incrementRetries();
                if (!syncQueue.shouldRetry(op)) {
                    processed.add(op);
                }
            }
        }

        if (!processed.isEmpty()) {
            syncQueue.removeProcessed(processed);
        }
    }

    private Object deserializePayload(String type, String json, Gson gson) {
        if (json == null || json.isEmpty()) return null;
        try {
            switch (type) {
                case SyncOperation.TYPE_SAVE_CONTINUE_WATCHING:
                    return gson.fromJson(json, ContinueWatchingItem.class);
                case SyncOperation.TYPE_ADD_FAVORITE:
                    return gson.fromJson(json, FavoriteItem.class);
                case SyncOperation.TYPE_REMOVE_FAVORITE:
                    return gson.fromJson(json, String.class);
                case SyncOperation.TYPE_ADD_HISTORY:
                    return gson.fromJson(json, WatchedHistoryItem.class);
                case SyncOperation.TYPE_SAVE_SETTINGS:
                    return gson.fromJson(json, UserSettings.class);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error deserializing payload for " + type, e);
        }
        return null;
    }

    public void processPendingOnReconnect() {
        processPendingQueue();
    }

    public void addToFavorites(String contentId, String title, String imageUrl, String mediaType) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        if (isFavorite(contentId)) return;

        FavoriteItem item = new FavoriteItem(contentId, title, imageUrl, mediaType);
        favoritesCache.add(item);
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_ADD_FAVORITE, item);
    }

    public void removeFromFavorites(String contentId) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        favoritesCache.removeIf(f -> contentId.equals(f.getContentId()));
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_REMOVE_FAVORITE, contentId);
    }

    public void addToHistory(String contentId, String title, String imageUrl, String mediaType) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        WatchedHistoryItem item = new WatchedHistoryItem(contentId, title, imageUrl, mediaType);
        historyCache.add(0, item);
        if (historyCache.size() > 200) {
            historyCache.remove(historyCache.size() - 1);
        }
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_ADD_HISTORY, item);
    }

    public void updateSettings(UserSettings newSettings) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        this.settingsCache = newSettings;
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_SAVE_SETTINGS, newSettings);
    }

    private void notifyListeners() {
        for (DataChangeListener l : dataListeners) {
            l.onFavoritesChanged(new ArrayList<>(favoritesCache));
            l.onHistoryChanged(new ArrayList<>(historyCache));
            l.onSettingsChanged(settingsCache);
        }
    }

    private Timestamp getLocalUpdatedAt() {
        try {
            String ts = appContext.getSharedPreferences("user_metadata", Context.MODE_PRIVATE)
                    .getString("updatedAt", null);
            if (ts != null) {
                return new com.google.firebase.Timestamp(
                        Long.parseLong(ts) / 1000, 0);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void cleanup() {
        if (snapshotListener != null) {
            snapshotListener.remove();
            snapshotListener = null;
        }
    }
}
