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
        List<SyncOperation> queue = getPending();
        queue.add(op);
        saveQueue(queue);
    }

    public List<SyncOperation> getPending() {
        String json = prefs.getString(KEY_QUEUE, "[]");
        Type type = new TypeToken<List<SyncOperation>>(){}.getType();
        List<SyncOperation> list = gson.fromJson(json, type);
        return list != null ? list : new ArrayList<>();
    }

    public boolean hasPending() {
        return !getPending().isEmpty();
    }

    public void removeProcessed(List<SyncOperation> processed) {
        List<SyncOperation> queue = getPending();
        queue.removeAll(processed);
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
