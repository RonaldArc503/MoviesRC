package sv.edu.catolica.rex.repository;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class SyncQueueManager {

    private static final String PREF_NAME = "sync_queue";
    private static final String KEY_QUEUE = "pending_ops";
    private static final int MAX_RETRIES = 5;

    private static SyncQueueManager instance;
    private final SharedPreferences prefs;
    private final Gson gson;

    private SyncQueueManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
    }

    public static synchronized SyncQueueManager getInstance(Context context) {
        if (instance == null) {
            instance = new SyncQueueManager(context);
        }
        return instance;
    }

    public void enqueue(SyncOperation op) {
        if (op == null) return;
        List<SyncOperation> queue = getPending();
        if (SyncOperation.TYPE_SAVE_CONTINUE_WATCHING.equals(op.getType())) {
            try {
                sv.edu.catolica.rex.models.ContinueWatchingItem newItem =
                        gson.fromJson(op.getPayloadJson(), sv.edu.catolica.rex.models.ContinueWatchingItem.class);
                if (newItem != null && newItem.getContentId() != null) {
                    queue.removeIf(existing -> {
                        if (!SyncOperation.TYPE_SAVE_CONTINUE_WATCHING.equals(existing.getType())) return false;
                        try {
                            sv.edu.catolica.rex.models.ContinueWatchingItem oldItem =
                                    gson.fromJson(existing.getPayloadJson(), sv.edu.catolica.rex.models.ContinueWatchingItem.class);
                            return oldItem != null && newItem.getContentId().equals(oldItem.getContentId());
                        } catch (Exception ignored) {
                            return false;
                        }
                    });
                }
            } catch (Exception ignored) {}
        }
        queue.add(op);
        while (queue.size() > 30) {
            queue.remove(0);
        }
        saveQueue(queue);
    }

    public List<SyncOperation> getPending() {
        String json = prefs.getString(KEY_QUEUE, "[]");
        Type type = new TypeToken<List<SyncOperation>>(){}.getType();
        List<SyncOperation> list = gson.fromJson(json, type);
        if (list == null) return new ArrayList<>();
        list.removeIf(op -> op == null || !shouldRetry(op));
        return list;
    }

    public boolean hasPending() {
        return !getPending().isEmpty();
    }

    public void removeProcessed(List<SyncOperation> processed) {
        if (processed == null || processed.isEmpty()) return;
        List<SyncOperation> queue = getPending();
        queue.removeIf(item -> {
            if (item == null) return true;
            for (SyncOperation p : processed) {
                if (p == null) continue;
                if (item.equals(p) || (item.getId() != null && item.getId().equals(p.getId()))) {
                    return true;
                }
            }
            return false;
        });
        saveQueue(queue);
    }

    public void clear() {
        prefs.edit().remove(KEY_QUEUE).apply();
    }

    public boolean shouldRetry(SyncOperation op) {
        return op.getRetries() < MAX_RETRIES;
    }

    private void saveQueue(List<SyncOperation> queue) {
        prefs.edit().putString(KEY_QUEUE, gson.toJson(queue)).apply();
    }
}
