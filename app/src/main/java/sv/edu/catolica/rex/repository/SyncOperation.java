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

    private String type;
    private String payloadJson;
    private long timestamp;
    private int retries;

    public SyncOperation() {
    }

    public SyncOperation(String type, Object payload) {
        this.type = type;
        this.payloadJson = new Gson().toJson(payload);
        this.timestamp = System.currentTimeMillis();
        this.retries = 0;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }

    public void incrementRetries() { this.retries++; }
}
