package sv.edu.catolica.rex.repository;

import static org.junit.Assert.*;

import org.junit.Test;

public class SyncOperationTest {

    @Test
    public void constructor_setsTypeAndPayload() {
        SyncOperation op = new SyncOperation("test_type", "test_payload");
        assertEquals("test_type", op.getType());
        assertTrue(op.getPayloadJson().contains("test_payload"));
        assertTrue(op.getTimestamp() > 0);
        assertEquals(0, op.getRetries());
    }

    @Test
    public void incrementRetries_increasesCount() {
        SyncOperation op = new SyncOperation("type", "payload");
        assertEquals(0, op.getRetries());
        op.incrementRetries();
        assertEquals(1, op.getRetries());
        op.incrementRetries();
        assertEquals(2, op.getRetries());
    }

    @Test
    public void setType_updatesType() {
        SyncOperation op = new SyncOperation("old", "payload");
        op.setType("new_type");
        assertEquals("new_type", op.getType());
    }

    @Test
    public void setRetries_updatesRetries() {
        SyncOperation op = new SyncOperation("type", "payload");
        op.setRetries(3);
        assertEquals(3, op.getRetries());
    }

    @Test
    public void typeConstants_areCorrect() {
        assertEquals("save_cw", SyncOperation.TYPE_SAVE_CONTINUE_WATCHING);
        assertEquals("mark_completed", SyncOperation.TYPE_MARK_COMPLETED);
        assertEquals("remove_cw", SyncOperation.TYPE_REMOVE_CONTINUE_WATCHING);
        assertEquals("add_favorite", SyncOperation.TYPE_ADD_FAVORITE);
        assertEquals("remove_favorite", SyncOperation.TYPE_REMOVE_FAVORITE);
        assertEquals("add_history", SyncOperation.TYPE_ADD_HISTORY);
        assertEquals("save_settings", SyncOperation.TYPE_SAVE_SETTINGS);
        assertEquals("save_list", SyncOperation.TYPE_SAVE_PERSONAL_LIST);
        assertEquals("delete_list", SyncOperation.TYPE_DELETE_PERSONAL_LIST);
    }
}
