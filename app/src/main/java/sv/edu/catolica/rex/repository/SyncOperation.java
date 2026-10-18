package sv.edu.catolica.rex.repository;

import com.google.gson.Gson;

public class SyncOperation {

    public static final String TYPE_SAVE_CONTINUE_WATCHING = "save_cw";
    public static final String TYPE_MARK_COMPLETED = "mark_completed";
    public static final String TYPE_REMOVE_CONTINUE_WATCHING = "remove_cw";
    public static final String TYPE_ADD_FAVORITE = "add_favorite";
    public static final String TYPE_REMOVE_FAVORITE = "remove_favorite";
    public static final String TYPE_ADD_HISTORY = "add_history";
    public static final String TYPE_SAVE_SETTINGS = "save_settings";
    public static final String TYPE_SAVE_PERSONAL_LIST = "save_list";
    public static final String TYPE_DELETE_PERSONAL_LIST = "delete_list";

    private String id = java.util.UUID.randomUUID().toString();
    private String type;
    private String payloadJson;
    private long timestamp;
    private int retries;

    public SyncOperation() {
    }

    public SyncOperation(String type, Object payload) {
        this.id = java.util.UUID.randomUUID().toString();
        this.type = type;
        this.payloadJson = new Gson().toJson(payload);
        this.timestamp = System.currentTimeMillis();
        this.retries = 0;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }

    public void incrementRetries() { this.retries++; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncOperation that = (SyncOperation) o;
        if (id != null && that.id != null) {
            return id.equals(that.id);
        }
        return timestamp == that.timestamp
                && java.util.Objects.equals(type, that.type)
                && java.util.Objects.equals(payloadJson, that.payloadJson);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : java.util.Objects.hash(type, payloadJson, timestamp);
    }
}
