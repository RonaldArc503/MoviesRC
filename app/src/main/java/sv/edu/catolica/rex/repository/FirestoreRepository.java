package sv.edu.catolica.rex.repository;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.models.FavoriteItem;
import sv.edu.catolica.rex.models.PersonalList;
import sv.edu.catolica.rex.models.UserData;
import sv.edu.catolica.rex.models.UserSettings;
import sv.edu.catolica.rex.models.WatchedHistoryItem;

public class FirestoreRepository {

    private static final String COLLECTION_USERS = "users";
    private static final String FIELD_CONTINUE_WATCHING = "continueWatching";
    private static final String FIELD_HISTORY = "history";
    private static final String FIELD_FAVORITES = "favorites";
    private static final String FIELD_PERSONAL_LISTS = "personalLists";
    private static final String FIELD_SETTINGS = "settings";

    private final FirebaseFirestore firestore;

    public FirestoreRepository(FirebaseFirestore firestore) {
        this.firestore = firestore;
    }

    private DocumentReference userDoc(String uid) {
        return firestore.collection(COLLECTION_USERS).document(uid);
    }

    public Task<DocumentSnapshot> getUserData(String uid) {
        return userDoc(uid).get();
    }

    public Task<Void> saveUserData(String uid, UserData data) {
        return userDoc(uid).set(data, SetOptions.merge());
    }

    public Task<Void> saveContinueWatching(String uid, ContinueWatchingItem item) {
        return userDoc(uid).get().continueWithTask(task -> {
            List<ContinueWatchingItem> list = new ArrayList<>();
            if (task.isSuccessful() && task.getResult().exists()) {
                UserData data = task.getResult().toObject(UserData.class);
                if (data != null && data.getContinueWatching() != null) {
                    list = data.getContinueWatching();
                }
            }
            boolean found = false;
            for (int i = 0; i < list.size(); i++) {
                if (item.getContentId() != null && item.getContentId().equals(list.get(i).getContentId())) {
                    list.set(i, item);
                    found = true;
                    break;
                }
            }
            if (!found) list.add(0, item);
            Map<String, Object> updates = new HashMap<>();
            updates.put(FIELD_CONTINUE_WATCHING, list);
            return userDoc(uid).set(updates, SetOptions.merge());
        });
    }

    public Task<Void> setContinueWatchingList(String uid, List<ContinueWatchingItem> list) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_CONTINUE_WATCHING, list);
        return userDoc(uid).set(updates, SetOptions.merge());
    }

    public Task<Void> addToHistory(String uid, WatchedHistoryItem item) {
        return userDoc(uid).get().continueWithTask(task -> {
            List<WatchedHistoryItem> list = new ArrayList<>();
            if (task.isSuccessful() && task.getResult().exists()) {
                UserData data = task.getResult().toObject(UserData.class);
                if (data != null && data.getHistory() != null) {
                    list = data.getHistory();
                }
            }
            list.add(0, item);
            if (list.size() > 200) {
                list = list.subList(0, 200);
            }
            Map<String, Object> updates = new HashMap<>();
            updates.put(FIELD_HISTORY, list);
            return userDoc(uid).set(updates, SetOptions.merge());
        });
    }

    public Task<Void> setHistoryList(String uid, List<WatchedHistoryItem> list) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_HISTORY, list);
        return userDoc(uid).set(updates, SetOptions.merge());
    }

    public Task<Void> addFavorite(String uid, FavoriteItem item) {
        return userDoc(uid).get().continueWithTask(task -> {
            List<FavoriteItem> list = new ArrayList<>();
            if (task.isSuccessful() && task.getResult().exists()) {
                UserData data = task.getResult().toObject(UserData.class);
                if (data != null && data.getFavorites() != null) {
                    list = data.getFavorites();
                }
            }
            for (FavoriteItem f : list) {
                if (item.getContentId() != null && item.getContentId().equals(f.getContentId())) {
                    return Tasks.forResult(null);
                }
            }
            list.add(item);
            Map<String, Object> updates = new HashMap<>();
            updates.put(FIELD_FAVORITES, list);
            return userDoc(uid).set(updates, SetOptions.merge());
        });
    }

    public Task<Void> removeFavorite(String uid, String contentId) {
        return userDoc(uid).get().continueWithTask(task -> {
            List<FavoriteItem> updated = new ArrayList<>();
            if (task.isSuccessful() && task.getResult().exists()) {
                UserData data = task.getResult().toObject(UserData.class);
                if (data != null && data.getFavorites() != null) {
                    for (FavoriteItem f : data.getFavorites()) {
                        if (!contentId.equals(f.getContentId())) {
                            updated.add(f);
                        }
                    }
                }
            }
            Map<String, Object> updates = new HashMap<>();
            updates.put(FIELD_FAVORITES, updated);
            return userDoc(uid).set(updates, SetOptions.merge());
        });
    }

    public Task<Void> setFavoritesList(String uid, List<FavoriteItem> list) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_FAVORITES, list);
        return userDoc(uid).set(updates, SetOptions.merge());
    }

    public Task<Void> savePersonalList(String uid, PersonalList list) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_PERSONAL_LISTS + "." + list.getListId(), list);
        return userDoc(uid).set(updates, SetOptions.merge());
    }

    public Task<Void> deletePersonalList(String uid, String listId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_PERSONAL_LISTS + "." + listId,
                com.google.firebase.firestore.FieldValue.delete());
        return userDoc(uid).set(updates, SetOptions.merge());
    }

    public Task<Void> saveSettings(String uid, UserSettings settings) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_SETTINGS, settings);
        return userDoc(uid).set(updates, SetOptions.merge());
    }

    public ListenerRegistration listenUserData(String uid,
                                               final OnUserDataChangedListener listener) {
        return userDoc(uid).addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                listener.onError(error);
                return;
            }
            if (snapshot != null && snapshot.exists()) {
                UserData data = snapshot.toObject(UserData.class);
                listener.onDataChanged(data);
            }
        });
    }

    public interface OnUserDataChangedListener {
        void onDataChanged(UserData data);
        void onError(Exception e);
    }
}
