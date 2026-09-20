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

    private static String currentUid() {
        try {
            com.google.firebase.auth.FirebaseAuth auth =
                    com.google.firebase.auth.FirebaseAuth.getInstance();
            return auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        String uid = currentUid();
        String name = uid != null && !uid.isEmpty() ? PREF_NAME + "_" + uid : PREF_NAME;
        return context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static void save(Context context, ContinueWatchingItem item) {
        saveLocal(context, item);
        syncToFirestore(context, item);
    }

    /** Igual que {@link #save} pero solo escribe localmente, sin reenviar a
     *  Firestore. Se usa al aplicar datos remotos para no provocar un bucle de
     *  eco (Firestore -> listener -> aplicar -> Firestore). */
    public static void saveLocal(Context context, ContinueWatchingItem item) {
        if (context == null || item == null || item.getContentId() == null) return;

        SharedPreferences prefs = getPrefs(context);
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
        touchLocalUpdatedAt(context);
    }

    /** Hace merge (upsert) de una lista remota sobre la lista local en una sola
     *  escritura Gson: actualiza los ítems que coinciden por contentId (prefiriendo
     *  el más reciente por lastPlayedAt, sin retroceder el progreso local),
     *  agrega los que no existen y PRESERVA los ítems locales que no están en
     *  remoto. No reenvía a Firestore (evita el bucle de eco). */
    public static void upsertAll(Context context, List<ContinueWatchingItem> remoteItems) {
        if (context == null || remoteItems == null) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> local = loadAll(prefs);
        for (ContinueWatchingItem remote : remoteItems) {
            if (remote == null || remote.getContentId() == null) continue;
            boolean found = false;
            for (int i = 0; i < local.size(); i++) {
                ContinueWatchingItem existing = local.get(i);
                if (remote.getContentId().equals(existing.getContentId())) {
                    if (remote.getLastPlayedAt() >= existing.getLastPlayedAt()) {
                        if (remote.getPositionMs() > 0) existing.setPositionMs(remote.getPositionMs());
                        if (remote.getDurationMs() > 0) existing.setDurationMs(remote.getDurationMs());
                        existing.setProgressPercent(remote.getProgressPercent());
                        existing.setCompleted(remote.isCompleted());
                        existing.setLastPlayedAt(remote.getLastPlayedAt());
                        if (remote.getEpisodeId() > 0) existing.setEpisodeId(remote.getEpisodeId());
                        if (remote.getSeasonNumber() > 0) existing.setSeasonNumber(remote.getSeasonNumber());
                        if (remote.getEpisodeNumber() > 0) existing.setEpisodeNumber(remote.getEpisodeNumber());
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                local.add(0, remote);
            }
        }
        Collections.sort(local, (a, b) -> Long.compare(b.getLastPlayedAt(), a.getLastPlayedAt()));
        trimToMax(local);
        saveAll(prefs, local);
        touchLocalUpdatedAt(context);
    }

    /** Registra en "user_metadata" la última modificación local de continuar viendo,
     *  para que el conflicto remoto no sobrescriba datos locales más recientes. */
    public static void touchLocalUpdatedAt(Context context) {
        if (context == null) return;
        try {
            context.getApplicationContext()
                    .getSharedPreferences("user_metadata", Context.MODE_PRIVATE)
                    .edit()
                    .putString("updatedAt", String.valueOf(System.currentTimeMillis()))
                    .apply();
        } catch (Exception ignored) {
        }
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
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAll(prefs);
        trimToMax(items);
        Collections.sort(items, (a, b) -> Long.compare(b.getLastPlayedAt(), a.getLastPlayedAt()));
        return items;
    }

    public static ContinueWatchingItem getByPostId(Context context, int postId) {
        if (context == null || postId <= 0) return null;
        SharedPreferences prefs = getPrefs(context);
        for (ContinueWatchingItem item : loadAll(prefs)) {
            if (item.getPostId() == postId) return item;
        }
        return null;
    }

    public static ContinueWatchingItem getByContentId(Context context, String contentId) {
        if (context == null || contentId == null) return null;
        SharedPreferences prefs = getPrefs(context);
        for (ContinueWatchingItem item : loadAll(prefs)) {
            if (contentId.equals(item.getContentId())) return item;
        }
        return null;
    }

    public static void remove(Context context, String contentId) {
        if (context == null || contentId == null) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAll(prefs);
        items.removeIf(item -> contentId.equals(item.getContentId()));
        saveAll(prefs, items);
    }

    public static void removeByPostId(Context context, int postId) {
        if (context == null || postId <= 0) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAll(prefs);
        items.removeIf(item -> item.getPostId() == postId);
        saveAll(prefs, items);
    }

    public static void markCompleted(Context context, String contentId) {
        if (context == null || contentId == null) return;
        SharedPreferences prefs = getPrefs(context);
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
        return getPrefs(context).contains(KEY_ITEMS);
    }

    public static void clear(Context context) {
        if (context == null) return;
        getPrefs(context).edit().remove(KEY_ITEMS).apply();
    }

    /** Limpia los datos de continuar viendo del usuario actualmente logueado. */
    public static void clearForCurrentUser(Context context) {
        if (context == null) return;
        clear(context);
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
