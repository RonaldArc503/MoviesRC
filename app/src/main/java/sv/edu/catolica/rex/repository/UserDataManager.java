package sv.edu.catolica.rex.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.FavoriteItem;
import sv.edu.catolica.rex.models.MediaItem;
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

    // Local caches. Volátiles: se reasignan como una nueva instancia desde el
    // hilo de fondo del listener, de modo que la UI (hilo principal) siempre
    // lee una referencia consistente sin ConcurrentModificationException.
    private volatile List<FavoriteItem> favoritesCache = new ArrayList<>();
    private volatile List<WatchedHistoryItem> historyCache = new ArrayList<>();
    private volatile Map<String, PersonalList> listsCache = new HashMap<>();
    private volatile UserSettings settingsCache = new UserSettings();

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, Long> lastSyncTimestamps = new ConcurrentHashMap<>();
    private static final long SYNC_THROTTLE_MS = 60_000L;
    private final AtomicBoolean isLocalSyncInProgress = new AtomicBoolean(false);

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
        loadFavoritesFromLocal();
    }

    public static synchronized UserDataManager getInstance(Context context) {
        if (instance == null) {
            instance = new UserDataManager(context);
        }
        return instance;
    }

    /**
     * Reinicia el manager al cerrar sesión: libera el listener y el executor,
     * descarta las cachés del usuario anterior y limpia el almacenamiento local
     * para que el siguiente usuario arranque sin datos ajenos.
     */
    public static synchronized void reset(Context context) {
        if (instance != null) {
            instance.cleanup();
            instance = null;
        }
        if (context != null) {
            try {
                ContinueWatchingStore.clearForCurrentUser(context);
            } catch (Exception ignored) {}
            try {
                SyncQueueManager.getInstance(context).clear();
            } catch (Exception ignored) {}
            try {
                context.getSharedPreferences("user_metadata", Context.MODE_PRIVATE)
                        .edit().clear().apply();
            } catch (Exception ignored) {}
            try {
                context.getSharedPreferences("rex_favorites_cache", Context.MODE_PRIVATE)
                        .edit().clear().apply();
            } catch (Exception ignored) {}
        }
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
            cwStore.upsertAll(appContext, remote.getContinueWatching());
        }
        if (remote.getFavorites() != null) {
            favoritesCache = new ArrayList<>(remote.getFavorites());
            saveFavoritesToLocal();
        }
        if (remote.getHistory() != null) {
            historyCache = new ArrayList<>(remote.getHistory());
        }
        if (remote.getPersonalLists() != null) {
            listsCache = new HashMap<>(remote.getPersonalLists());
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
        if (!isLocalSyncInProgress.get() && syncQueue.hasPending()) {
            syncExecutor.execute(this::processPendingQueue);
        }
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
        if (!shouldSyncNow(type, payload)) {
            return;
        }
        SyncOperation op = new SyncOperation(type, payload);
        syncQueue.enqueue(op);
        if (restoreComplete) {
            String uidCopy = uid;
            syncExecutor.execute(() -> {
                isLocalSyncInProgress.set(true);
                try {
                    boolean ok = syncImmediately(uidCopy, type, payload);
                    if (ok) {
                        syncQueue.removeProcessed(java.util.Collections.singletonList(op));
                    }
                } finally {
                    isLocalSyncInProgress.set(false);
                }
            });
        }
    }

    private boolean shouldSyncNow(String type, Object payload) {
        if (!SyncOperation.TYPE_SAVE_CONTINUE_WATCHING.equals(type)) return true;
        if (payload instanceof ContinueWatchingItem) {
            String contentId = ((ContinueWatchingItem) payload).getContentId();
            if (contentId != null) {
                Long lastSync = lastSyncTimestamps.get(contentId);
                long now = System.currentTimeMillis();
                if (lastSync != null && (now - lastSync) < SYNC_THROTTLE_MS) {
                    return false;
                }
                lastSyncTimestamps.put(contentId, now);
            }
        }
        return true;
    }

    private boolean syncImmediately(String uid, String type, Object payload) {
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
            return true;
        } catch (Exception e) {
            Log.w(TAG, "syncImmediately failed for " + type + ", queued for retry", e);
            return false;
        }
    }

    private void processPendingQueue() {
        if (!syncQueue.hasPending()) return;
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        String uid = user.getUid();
        Gson gson = new Gson();
        List<SyncOperation> pending = syncQueue.getPending();
        if (pending.isEmpty()) return;
        List<SyncOperation> processed = new ArrayList<>();

        for (SyncOperation op : pending) {
            if (op == null) continue;
            try {
                Object payload = deserializePayload(op.getType(), op.getPayloadJson(), gson);
                if (payload != null) {
                    boolean ok = syncImmediately(uid, op.getType(), payload);
                    if (ok) {
                        processed.add(op);
                    } else {
                        op.incrementRetries();
                        if (!syncQueue.shouldRetry(op)) {
                            processed.add(op);
                        }
                    }
                } else {
                    processed.add(op);
                }
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
        syncExecutor.execute(this::processPendingQueue);
    }

    public static boolean isSeriesType(String mediaType) {
        if (mediaType == null) return false;
        String type = mediaType.toLowerCase(Locale.ROOT);
        return type.contains("tv") || type.contains("series") || type.contains("dorama")
                || type.contains("anime") || type.contains("novela");
    }

    public static String buildFavoriteId(MediaItem item) {
        if (item == null) return "";
        boolean isSeries = isSeriesType(item.getMediaType());
        if (item.getPostId() > 0) {
            return (isSeries ? "tv_" : "post_") + item.getPostId();
        }
        if (item.getTmdbId() > 0) {
            return (isSeries ? "tmdb_tv_" : "tmdb_movie_") + item.getTmdbId();
        }
        if (item.getDetailUrl() != null && !item.getDetailUrl().isEmpty()) {
            return (isSeries ? "tv_url_" : "movie_url_") + Math.abs(item.getDetailUrl().hashCode());
        }
        return (isSeries ? "tv_" : "post_") + Math.abs((item.getTitulo() != null ? item.getTitulo() : "").hashCode());
    }

    public boolean matchesFavorite(MediaItem item, FavoriteItem f) {
        if (item == null || f == null) return false;
        String favId = buildFavoriteId(item);
        if (favId.equals(f.getContentId())) return true;
        if (item.getPostId() > 0 && f.getPostId() > 0 && item.getPostId() == f.getPostId()) return true;
        if (item.getTmdbId() > 0 && f.getTmdbId() > 0 && item.getTmdbId() == f.getTmdbId()) return true;
        if (item.getDetailUrl() != null && !item.getDetailUrl().isEmpty() && item.getDetailUrl().equals(f.getDetailUrl())) return true;
        if (item.getTitulo() != null && f.getTitle() != null
                && item.getTitulo().trim().equalsIgnoreCase(f.getTitle().trim())) {
            if (item.getMediaType() != null && f.getMediaType() != null) {
                return isSeriesType(item.getMediaType()) == isSeriesType(f.getMediaType());
            }
            return true;
        }
        return false;
    }

    public boolean isFavorite(MediaItem item) {
        if (item == null) return false;
        for (FavoriteItem f : favoritesCache) {
            if (matchesFavorite(item, f)) {
                return true;
            }
        }
        return false;
    }

    public void addToFavorites(MediaItem mediaItem) {
        if (mediaItem == null) return;
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        if (isFavorite(mediaItem)) return;

        String favId = buildFavoriteId(mediaItem);
        FavoriteItem item = new FavoriteItem(favId, mediaItem.getTitulo(), mediaItem.getImagen(), mediaItem.getMediaType());
        item.setBackdrop(mediaItem.getBackdrop());
        item.setDetailUrl(mediaItem.getDetailUrl());
        item.setFuente(mediaItem.getFuente());
        item.setAnio(mediaItem.getAnio());
        item.setRating(mediaItem.getRating());
        item.setPostId(mediaItem.getPostId());
        item.setTmdbId(mediaItem.getTmdbId());
        item.setSynopsis(mediaItem.getSynopsis());

        List<FavoriteItem> updated = new ArrayList<>(favoritesCache);
        updated.add(0, item);
        favoritesCache = updated;
        saveFavoritesToLocal();
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_ADD_FAVORITE, item);
    }

    public void removeFromFavorites(MediaItem mediaItem) {
        if (mediaItem == null) return;
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        FavoriteItem match = null;
        for (FavoriteItem f : favoritesCache) {
            if (matchesFavorite(mediaItem, f)) {
                match = f;
                break;
            }
        }

        String targetId = match != null && match.getContentId() != null
                ? match.getContentId()
                : buildFavoriteId(mediaItem);

        List<FavoriteItem> updated = new ArrayList<>(favoritesCache);
        if (match != null) {
            updated.remove(match);
        } else {
            updated.removeIf(f -> matchesFavorite(mediaItem, f));
        }
        favoritesCache = updated;
        saveFavoritesToLocal();
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_REMOVE_FAVORITE, targetId);
    }

    public boolean toggleFavorite(MediaItem mediaItem) {
        if (isFavorite(mediaItem)) {
            removeFromFavorites(mediaItem);
            return false;
        } else {
            addToFavorites(mediaItem);
            return true;
        }
    }

    public void addToFavorites(String contentId, String title, String imageUrl, String mediaType) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        if (isFavorite(contentId)) return;

        FavoriteItem item = new FavoriteItem(contentId, title, imageUrl, mediaType);
        List<FavoriteItem> updated = new ArrayList<>(favoritesCache);
        updated.add(0, item);
        favoritesCache = updated;
        saveFavoritesToLocal();
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_ADD_FAVORITE, item);
    }

    public void removeFromFavorites(String contentId) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        List<FavoriteItem> updated = new ArrayList<>(favoritesCache);
        updated.removeIf(f -> contentId.equals(f.getContentId()));
        favoritesCache = updated;
        saveFavoritesToLocal();
        notifyListeners();

        onLocalDataChanged(user.getUid(), SyncOperation.TYPE_REMOVE_FAVORITE, contentId);
    }

    private void loadFavoritesFromLocal() {
        try {
            SharedPreferences sp = appContext.getSharedPreferences("rex_favorites_cache", Context.MODE_PRIVATE);
            String json = sp.getString("favorites_json", null);
            if (json != null && !json.isEmpty()) {
                Type type = new TypeToken<List<FavoriteItem>>() {}.getType();
                List<FavoriteItem> loaded = new Gson().fromJson(json, type);
                if (loaded != null) {
                    favoritesCache = new ArrayList<>(loaded);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading favorites from local cache", e);
        }
    }

    private void saveFavoritesToLocal() {
        try {
            SharedPreferences sp = appContext.getSharedPreferences("rex_favorites_cache", Context.MODE_PRIVATE);
            String json = new Gson().toJson(favoritesCache);
            sp.edit().putString("favorites_json", json).apply();
        } catch (Exception e) {
            Log.w(TAG, "Error saving favorites to local cache", e);
        }
    }

    public void addToHistory(String contentId, String title, String imageUrl, String mediaType) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user == null) return;

        WatchedHistoryItem item = new WatchedHistoryItem(contentId, title, imageUrl, mediaType);
        List<WatchedHistoryItem> updated = new ArrayList<>(historyCache);
        updated.add(0, item);
        if (updated.size() > 200) {
            updated = new ArrayList<>(updated.subList(0, 200));
        }
        historyCache = updated;
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::notifyListeners);
            return;
        }
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
        syncExecutor.shutdown();
    }
}
