package sv.edu.catolica.rex.models;

import com.google.firebase.Timestamp;

import java.util.ArrayList;
import java.util.List;

public class PersonalList {

    private String listId;
    private String name;
    private String description;
    private List<String> contentIds;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public PersonalList() {
        this.contentIds = new ArrayList<>();
    }

    public PersonalList(String listId, String name, String description) {
        this.listId = listId;
        this.name = name;
        this.description = description;
        this.contentIds = new ArrayList<>();
        this.createdAt = Timestamp.now();
        this.updatedAt = Timestamp.now();
    }

    public String getListId() { return listId; }
    public void setListId(String listId) { this.listId = listId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getContentIds() { return contentIds; }
    public void setContentIds(List<String> contentIds) { this.contentIds = contentIds; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
