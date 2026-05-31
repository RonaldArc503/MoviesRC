package sv.edu.catolica.rex.viewmodel;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import sv.edu.catolica.rex.models.FootballMatch;
import sv.edu.catolica.rex.network.FootballLibreScraper;

/**
 * ViewModel para la pantalla de partidos de fútbol en vivo.
 *
 * Expone:
 *  - matches      → lista de partidos del día
 *  - isLoading    → indicador de carga
 *  - errorMessage → mensaje de error si algo falla
 *  - resolvedUrl  → URL reproducible resuelta para un stream específico
 */
public class FootballViewModel extends AndroidViewModel {

    private static final String TAG = "FootballViewModel";

    private final MutableLiveData<List<FootballMatch>> matches = new MutableLiveData<>();
    private final MutableLiveData<Boolean>             isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String>              errorMessage = new MutableLiveData<>();
    private final MutableLiveData<String>              resolvedUrl = new MutableLiveData<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final FootballLibreScraper scraper = FootballLibreScraper.getInstance();

    public FootballViewModel(@NonNull Application application) {
        super(application);
    }

    // ── LiveData públicos ──────────────────────────────────────────────────────

    public LiveData<List<FootballMatch>> getMatches()      { return matches; }
    public LiveData<Boolean>             getIsLoading()    { return isLoading; }
    public LiveData<String>              getErrorMessage() { return errorMessage; }
    public LiveData<String>              getResolvedUrl()  { return resolvedUrl; }

    public void clearResolvedUrl() {
        resolvedUrl.postValue(null);
    }

    // ── Acciones ───────────────────────────────────────────────────────────────

    /**
     * Carga la lista de partidos en background.
     * Actualiza matches y isLoading via LiveData.
     */
    public void loadMatches() {
        isLoading.setValue(true);
        executor.execute(() -> {
            try {
                List<FootballMatch> result = scraper.getMatches();
                if (result.isEmpty()) {
                    errorMessage.postValue("No hay partidos disponibles en este momento.");
                } else {
                    matches.postValue(result);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error cargando partidos", e);
                errorMessage.postValue("Error de red: " + e.getMessage());
            } finally {
                isLoading.postValue(false);
            }
        });
    }

    /**
     * Resuelve la URL reproducible de un stream y la publica en resolvedUrl.
     *
     * @param stream El stream elegido por el usuario.
     */
    public void resolveStream(FootballMatch.FootballStream stream) {
        if (stream == null) return;
        isLoading.setValue(true);
        executor.execute(() -> {
            try {
                String url = scraper.resolveStream(stream);
                if (url != null && !url.isEmpty()) {
                    resolvedUrl.postValue(url);
                } else {
                    errorMessage.postValue("No se pudo obtener el stream para: "
                            + stream.getChannelName());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error resolviendo stream", e);
                errorMessage.postValue("Error al cargar stream: " + e.getMessage());
            } finally {
                isLoading.postValue(false);
            }
        });
    }

    /**
     * Resuelve un stream con fallback a WebView y publica la URL final.
     */
    public void resolveStreamWithFallback(Context context, FootballMatch.FootballStream stream) {
        if (stream == null) return;
        resolvedUrl.postValue(null);
        isLoading.postValue(true);
        executor.execute(() -> scraper.resolveStreamWithFallback(context, stream, new FootballLibreScraper.ResolveCallback() {
            @Override
            public void onResolved(String url) {
                if (url != null && !url.isEmpty()) {
                    resolvedUrl.postValue(url);
                } else {
                    errorMessage.postValue("No se pudo obtener el stream para: " + stream.getChannelName());
                }
                isLoading.postValue(false);
            }

            @Override
            public void onFailed(String reason) {
                errorMessage.postValue(reason != null ? reason : "No se pudo resolver el stream.");
                isLoading.postValue(false);
            }
        }));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
    }
}
