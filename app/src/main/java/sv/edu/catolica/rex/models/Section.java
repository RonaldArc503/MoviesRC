package sv.edu.catolica.rex.models;

import java.util.List;

public class Section {
    private String title;
    private List<MediaItem> items;

    public Section(String title, List<MediaItem> items) {
        this.title = title;
        this.items = items;
    }

    public String getTitle() { return title; }
    public List<MediaItem> getItems() { return items; }
}
