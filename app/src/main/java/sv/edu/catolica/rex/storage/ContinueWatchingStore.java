package sv.edu.catolica.rex.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import sv.edu.catolica.rex.models.ContinueWatchingItem;

public class ContinueWatchingStore {

    private static final String PREF_NAME = "continue_watching";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 20;

    private static final Gson gson = new Gson();

    public static void save(Context context, ContinueWatchingItem item) {
        if (context == null || item == null || item.getContentId() == null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<ContinueWatchingItem> items = loadAll(prefs);

        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getContentId() != null && items.get(i).getContentId().equals(item.getContentId())) {
                ContinueWatchingItem existing = items.get(i);
                if (item.getPositionMs() > 0) existing.setPositionMs(item.getPositionMs());
                if (item.getDurationMs() > 0) existing.setDurationMs(item.getDurationMs());
                existing.setProgressPercent(item.getProgressPercent());
                existing.setCompleted(item.isCompleted());
                existing.setLastPlayedAt(System.currentTimeMillis());
                if (item.getEpisodeId() > 0) existing.setEpisodeId(item.getEpisodeId());
                if (item.getSeasonNumber() > 0) existing.setSeasonNumber(item.getSeasonNumber());
                if (item.getEpisodeNumber() > 0) existing.setEpisodeNumber(item.getEpisodeNumber());
                found = true;
                break;
            }
        }

        if (!found) {
            item.setLastPlayedAt(System.currentTimeMillis());
            items.add(0, item);
        }

        trimToMax(items);
        saveAll(prefs, items);

        syncToFirestore(context, item);
    }

    private static void syncToFirestore(Context context, ContinueWatchingItem item) {
        try {
            sv.edu.catolica.rex.repository.UserDataManager manager =
                    sv.edu.catolica.rex.repository.UserDataManager.getInstance(context);
            com.google.firebase.auth.FirebaseAuth auth =
                    com.google.firebase.auth.FirebaseAuth.getInstance();
            if (auth.getCurrentUser() != null) {
                manager.onLocalDataChanged(auth.getCurrentUser().getUid(),
                        sv.edu.catolica.rex.repository.SyncOperation.TYPE_SAVE_CONTINUE_WATCHING, item);
            }
        } catch (Exception ignored) {
        }
    }

    public static List<ContinueWatchingItem> getAll(Context context) {
        if (context == null) return new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<ContinueWatchingItem> items = loadAll(prefs);
        trimToMax(items);
        Collections.sort(items, (a, b) -> Long.compare(b.getLastPlayedAt(), a.getLastPlayedAt()));
        return items;
    }

    public static ContinueWatchingItem getByPostId(Context context, int postId) {
        if (context == null || postId <= 0) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        for (ContinueWatchingItem item : loadAll(prefs)) {
            if (item.getPostId() == postId) return item;
        }
        return null;
    }

    public static ContinueWatchingItem getByContentId(Context context, String contentId) {
        if (context == null || contentId == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        for (ContinueWatchingItem item : loadAll(prefs)) {
            if (contentId.equals(item.getContentId())) return item;
        }
        return null;
    }

    public static void remove(Context context, String contentId) {
        if (context == null || contentId == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<ContinueWatchingItem> items = loadAll(prefs);
        items.removeIf(item -> contentId.equals(item.getContentId()));
        saveAll(prefs, items);
    }

    public static void removeByPostId(Context context, int postId) {
        if (context == null || postId <= 0) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<ContinueWatchingItem> items = loadAll(prefs);
        items.removeIf(item -> item.getPostId() == postId);
        saveAll(prefs, items);
    }

    public static void markCompleted(Context context, String contentId) {
        if (context == null || contentId == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<ContinueWatchingItem> items = loadAll(prefs);
        ContinueWatchingItem completedItem = null;
        for (ContinueWatchingItem item : items) {
            if (contentId.equals(item.getContentId())) {
                item.setCompleted(true);
                item.setProgressPercent(100);
                item.setLastPlayedAt(System.currentTimeMillis());
                completedItem = item;
                break;
            }
        }
        saveAll(prefs, items);
        if (completedItem != null) {
            syncToFirestore(context, completedItem);
        }
    }

    public static boolean hasData(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .contains(KEY_ITEMS);
    }

    public static void clear(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ITEMS)
                .apply();
    }

    private static List<ContinueWatchingItem> loadAll(SharedPreferences prefs) {
        String json = prefs.getString(KEY_ITEMS, null);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            Type type = new TypeToken<List<ContinueWatchingItem>>() {}.getType();
            List<ContinueWatchingItem> items = gson.fromJson(json, type);
            return items != null ? items : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void saveAll(SharedPreferences prefs, List<ContinueWatchingItem> items) {
        String json = gson.toJson(items);
        prefs.edit().putString(KEY_ITEMS, json).apply();
    }

    private static void trimToMax(List<ContinueWatchingItem> items) {
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
    }
}
