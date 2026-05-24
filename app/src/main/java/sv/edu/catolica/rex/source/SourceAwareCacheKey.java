package sv.edu.catolica.rex.source;

public class SourceAwareCacheKey {
    private final String sourceName;
    private final String key;

    public SourceAwareCacheKey(String sourceName, String key) {
        this.sourceName = sourceName == null ? "" : sourceName;
        this.key = key == null ? "" : key;
    }

    @Override
    public String toString() { return sourceName + ":" + key; }
}
