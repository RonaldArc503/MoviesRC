package sv.edu.catolica.rex.source;

public class SourceRegistry {
    private static MultiSourceRepository INSTANCE;

    public static synchronized MultiSourceRepository getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new MultiSourceRepository();
            // register default sources: AllCalidad primary, then Pelispedia fallback
            try {
                INSTANCE.registerSource(new AllCalidadSource());
            } catch (Throwable ignored) { }
            try {
                INSTANCE.registerSource(new PelispediaSource());
            } catch (Throwable ignored) { }
        }
        return INSTANCE;
    }
}
