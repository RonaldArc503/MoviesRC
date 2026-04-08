package sv.edu.catolica.rex.models;

import java.util.List;

public class Season {
    private String name;
    private List<Episode> episodes;

    public Season(String name) { this.name = name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Episode> getEpisodes() { return episodes; }
    public void setEpisodes(List<Episode> episodes) { this.episodes = episodes; }
}
