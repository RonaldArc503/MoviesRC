package sv.edu.catolica.rex.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import sv.edu.catolica.rex.models.ContinueWatchingItem;
import sv.edu.catolica.rex.network.AllCalidadScraper;
import sv.edu.catolica.rex.network.smart.SeriesSeasonsCache;

public class ContinueWatchingStore {

    private static final String PREF_NAME = "continue_watching";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 20;
    public static final int MOVIE_COMPLETION_PERCENT = 99;
    public static final int SERIES_COMPLETION_PERCENT = 95;

    private static final Gson gson = new Gson();

    private static String currentUid() {
        try {
            com.google.firebase.auth.FirebaseAuth auth =
                    com.google.firebase.auth.FirebaseAuth.getInstance();
            return auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        String uid = currentUid();
        String name = uid != null && !uid.isEmpty() ? PREF_NAME + "_" + uid : PREF_NAME;
        return context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static SharedPreferences getDefaultPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static void save(Context context, ContinueWatchingItem item) {
        saveLocal(context, item);
        syncToFirestore(context, item);
    }

    /** Igual que {@link #save} pero solo escribe localmente, sin reenviar a
     *  Firestore. Se usa al aplicar datos remotos para no provocar un bucle de
     *  eco (Firestore -> listener -> aplicar -> Firestore). */
    public static void saveLocal(Context context, ContinueWatchingItem item) {
        if (context == null || item == null || item.getContentId() == null) return;

        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAllWithFallback(context, prefs);
        updateOrAddItem(items, item);
        trimToMax(items);
        saveAll(prefs, items);
        touchLocalUpdatedAt(context);

        // Respaldar siempre en las preferencias por defecto para disponibilidad inmediata y offline
        SharedPreferences defaultPrefs = getDefaultPrefs(context);
        if (defaultPrefs != prefs) {
            List<ContinueWatchingItem> defItems = loadAll(defaultPrefs);
            updateOrAddItem(defItems, item);
            trimToMax(defItems);
            saveAll(defaultPrefs, defItems);
        }
    }

    public static boolean isSeries(ContinueWatchingItem item) {
        if (item == null) return false;
        if (item.getEpisodeId() > 0 || item.getSeasonNumber() > 0 || item.getEpisodeNumber() > 0) {
            return true;
        }
        String type = item.getMediaType() == null ? "" : item.getMediaType().toLowerCase(Locale.ROOT);
        return type.contains("tv") || type.contains("series") || type.contains("dorama") || type.contains("anime");
    }

    public static String getCleanSeriesTitle(ContinueWatchingItem item) {
        if (item == null) return "";
        String s = item.getSeriesTitle();
        if (s != null && !s.trim().isEmpty()) {
            return s.trim().toLowerCase(Locale.ROOT);
        }
        String t = item.getTitle();
        if (t != null && !t.trim().isEmpty()) {
            return t.replaceAll("(?i)\\s*-\\s*(?:T|S)\\d+.*", "")
                    .replaceAll("(?i)\\s*(?:T|S)\\d+[:E\\s].*", "")
                    .trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    public static boolean isSameSeries(ContinueWatchingItem a, ContinueWatchingItem b) {
        if (a == null || b == null) return false;
        if (!isSeries(a) || !isSeries(b)) return false;
        if (a.getPostId() > 0 && b.getPostId() > 0 && a.getPostId() == b.getPostId()) {
            return true;
        }
        String titleA = getCleanSeriesTitle(a);
        String titleB = getCleanSeriesTitle(b);
        return !titleA.isEmpty() && !titleB.isEmpty() && titleA.equalsIgnoreCase(titleB);
    }

    public static void normalizeCompletion(ContinueWatchingItem item) {
        if (item == null) return;
        boolean completed = item.isCompleted() || item.getProgressPercent() >= completionPercent(item);
        if (item.getDurationMs() > 0 && item.getPositionMs() > 0) {
            long safePosition = Math.min(item.getPositionMs(), item.getDurationMs());
            int pct = (int) Math.round((safePosition * 100.0) / item.getDurationMs());
            if (pct >= completionPercent(item)) {
                completed = true;
            }
        }
        if (completed) {
            item.setCompleted(true);
            item.setProgressPercent(100);
            item.setEverCompleted(true);
        }
    }

    public static int completionPercent(ContinueWatchingItem item) {
        return isSeries(item) ? SERIES_COMPLETION_PERCENT : MOVIE_COMPLETION_PERCENT;
    }

    public static boolean isCompleted(ContinueWatchingItem item) {
        if (item == null) return false;
        if (item.isCompleted() || item.getProgressPercent() >= completionPercent(item)) return true;
        if (item.getDurationMs() <= 0 || item.getPositionMs() <= 0) return false;
        long safePosition = Math.min(item.getPositionMs(), item.getDurationMs());
        int pct = (int) Math.round((safePosition * 100.0) / item.getDurationMs());
        return pct >= completionPercent(item);
    }

    /** Aplica el progreso real del reproductor con límites seguros. Es la
     * única regla compartida para WebView y ExoPlayer. */
    public static void applyPlaybackProgress(ContinueWatchingItem item, long positionMs, long durationMs) {
        if (item == null) return;
        long safeDuration = Math.max(0L, durationMs);
        long safePosition = Math.max(0L, positionMs);
        if (safeDuration > 0L) safePosition = Math.min(safePosition, safeDuration);
        item.setPositionMs(safePosition);
        item.setDurationMs(safeDuration);
        int percent = safeDuration > 0L
                ? (int) Math.round((safePosition * 100.0) / safeDuration) : 0;
        if (percent >= completionPercent(item)) {
            item.setProgressPercent(100);
            item.setCompleted(true);
            item.setEverCompleted(true);
        } else {
            item.setProgressPercent(Math.max(0, Math.min(99, percent)));
            item.setCompleted(false);
        }
    }

    public static void deduplicateSeries(List<ContinueWatchingItem> list) {
        if (list == null || list.size() <= 1) return;
        Collections.sort(list, (a, b) -> Long.compare(b.getLastPlayedAt(), a.getLastPlayedAt()));
        List<ContinueWatchingItem> deduplicated = new ArrayList<>();
        for (ContinueWatchingItem item : list) {
            if (item == null) continue;
            normalizeCompletion(item);
            if (isSeries(item)) {
                boolean alreadyHaveSeries = false;
                for (ContinueWatchingItem kept : deduplicated) {
                    if (isSameSeries(kept, item)) {
                        alreadyHaveSeries = true;
                        break;
                    }
                }
                if (alreadyHaveSeries) {
                    continue; // Descarta episodios más antiguos de la misma serie
                }
            }
            deduplicated.add(item);
        }
        list.clear();
        list.addAll(deduplicated);
    }

    private static void updateOrAddItem(List<ContinueWatchingItem> items, ContinueWatchingItem item) {
        if (items == null || item == null) return;
        normalizeCompletion(item);

        // Si es una serie, protegemos un episodio siguiente ya encolado y removemos previos
        if (isSeries(item)) {
            for (ContinueWatchingItem existing : items) {
                if (existing != null && !item.getContentId().equals(existing.getContentId()) && isSameSeries(item, existing)) {
                    int existS = existing.getSeasonNumber() > 0 ? existing.getSeasonNumber() : 1;
                    int existE = existing.getEpisodeNumber() > 0 ? existing.getEpisodeNumber() : 1;
                    int itemS = item.getSeasonNumber() > 0 ? item.getSeasonNumber() : 1;
                    int itemE = item.getEpisodeNumber() > 0 ? item.getEpisodeNumber() : 1;
                    if (item.isCompleted() && !existing.isCompleted()) {
                        if (existS > itemS || (existS == itemS && existE > itemE)) {
                            // El elemento existente es un episodio posterior (ej. E2) listo para verse.
                            // NO permitir que el episodio anterior completado (ej. E1) lo sobreescriba!
                            return;
                        }
                    }
                }
            }
            items.removeIf(existing -> existing != null
                    && !item.getContentId().equals(existing.getContentId())
                    && isSameSeries(item, existing));
        }

        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getContentId() != null && items.get(i).getContentId().equals(item.getContentId())) {
                ContinueWatchingItem existing = items.get(i);
                if (item.getPositionMs() > 0) existing.setPositionMs(item.getPositionMs());
                if (item.getDurationMs() > 0) existing.setDurationMs(item.getDurationMs());
                existing.setProgressPercent(item.getProgressPercent());
                existing.setCompleted(item.isCompleted());
                existing.setEverCompleted(existing.isEverCompleted()
                        || item.isEverCompleted() || item.isCompleted());
                existing.setLastPlayedAt(System.currentTimeMillis());
                if (item.getMediaType() != null && !item.getMediaType().isEmpty()) {
                    existing.setMediaType(item.getMediaType());
                }
                if (item.getTitle() != null && !item.getTitle().isEmpty()) {
                    existing.setTitle(item.getTitle());
                }
                if (item.getSeriesTitle() != null && !item.getSeriesTitle().isEmpty()) {
                    existing.setSeriesTitle(item.getSeriesTitle());
                }
                if (item.getImageUrl() != null && !item.getImageUrl().isEmpty()) {
                    existing.setImageUrl(item.getImageUrl());
                }
                if (item.getPostId() > 0) existing.setPostId(item.getPostId());
                if (item.getEpisodeId() > 0) existing.setEpisodeId(item.getEpisodeId());
                if (item.getSeasonNumber() > 0) existing.setSeasonNumber(item.getSeasonNumber());
                if (item.getEpisodeNumber() > 0) existing.setEpisodeNumber(item.getEpisodeNumber());
                normalizeCompletion(existing);
                found = true;
                break;
            }
        }

        if (!found) {
            item.setLastPlayedAt(System.currentTimeMillis());
            items.add(0, item);
        }
    }

    /** Hace merge (upsert) de una lista remota sobre la lista local en una sola
     *  escritura Gson: actualiza los ítems que coinciden por contentId (prefiriendo
     *  el más reciente por lastPlayedAt, sin retroceder el progreso local),
     *  agrega los que no existen y PRESERVA los ítems locales que no están en
     *  remoto. No reenvía a Firestore (evita el bucle de eco). */
    public static void upsertAll(Context context, List<ContinueWatchingItem> remoteItems) {
        if (context == null || remoteItems == null) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> local = loadAllWithFallback(context, prefs);
        for (ContinueWatchingItem remote : remoteItems) {
            if (remote == null || remote.getContentId() == null) continue;
            boolean found = false;
            for (int i = 0; i < local.size(); i++) {
                ContinueWatchingItem existing = local.get(i);
                if (remote.getContentId().equals(existing.getContentId())) {
                    if (remote.getLastPlayedAt() >= existing.getLastPlayedAt()) {
                        if (remote.getPositionMs() > 0) existing.setPositionMs(remote.getPositionMs());
                        if (remote.getDurationMs() > 0) existing.setDurationMs(remote.getDurationMs());
                        existing.setProgressPercent(remote.getProgressPercent());
                        existing.setCompleted(remote.isCompleted());
                        existing.setEverCompleted(existing.isEverCompleted()
                                || remote.isEverCompleted() || remote.isCompleted());
                        existing.setLastPlayedAt(remote.getLastPlayedAt());
                        if (remote.getMediaType() != null && !remote.getMediaType().isEmpty()) {
                            existing.setMediaType(remote.getMediaType());
                        }
                        if (remote.getTitle() != null && !remote.getTitle().isEmpty()) {
                            existing.setTitle(remote.getTitle());
                        }
                        if (remote.getSeriesTitle() != null && !remote.getSeriesTitle().isEmpty()) {
                            existing.setSeriesTitle(remote.getSeriesTitle());
                        }
                        if (remote.getImageUrl() != null && !remote.getImageUrl().isEmpty()) {
                            existing.setImageUrl(remote.getImageUrl());
                        }
                        if (remote.getEpisodeId() > 0) existing.setEpisodeId(remote.getEpisodeId());
                        if (remote.getSeasonNumber() > 0) existing.setSeasonNumber(remote.getSeasonNumber());
                        if (remote.getEpisodeNumber() > 0) existing.setEpisodeNumber(remote.getEpisodeNumber());
                        normalizeCompletion(existing);
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                local.add(0, remote);
            }
        }
        deduplicateSeries(local);
        Collections.sort(local, (a, b) -> Long.compare(b.getLastPlayedAt(), a.getLastPlayedAt()));
        trimToMax(local);
        saveAll(prefs, local);

        SharedPreferences defaultPrefs = getDefaultPrefs(context);
        if (defaultPrefs != prefs) {
            saveAll(defaultPrefs, local);
        }

        touchLocalUpdatedAt(context);
    }

    /** Registra en "user_metadata" la última modificación local de continuar viendo,
     *  para que el conflicto remoto no sobrescriba datos locales más recientes. */
    public static void touchLocalUpdatedAt(Context context) {
        if (context == null) return;
        try {
            context.getApplicationContext()
                    .getSharedPreferences("user_metadata", Context.MODE_PRIVATE)
                    .edit()
                    .putString("updatedAt", String.valueOf(System.currentTimeMillis()))
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static void syncToFirestore(Context context, ContinueWatchingItem item) {
        try {
            sv.edu.catolica.rex.repository.UserDataManager manager =
                    sv.edu.catolica.rex.repository.UserDataManager.getInstance(context);
            com.google.firebase.auth.FirebaseAuth auth =
                    com.google.firebase.auth.FirebaseAuth.getInstance();
            if (auth.getCurrentUser() != null) {
                manager.onLocalDataChanged(auth.getCurrentUser().getUid(),
                        sv.edu.catolica.rex.repository.SyncOperation.TYPE_SAVE_CONTINUE_WATCHING, item);
            }
        } catch (Exception ignored) {
        }
    }

    private static List<ContinueWatchingItem> loadAllWithFallback(Context context, SharedPreferences prefs) {
        List<ContinueWatchingItem> items = loadAll(prefs);
        if (items.isEmpty() && context != null) {
            SharedPreferences defaultPrefs = getDefaultPrefs(context);
            if (defaultPrefs != prefs) {
                List<ContinueWatchingItem> fallback = loadAll(defaultPrefs);
                if (!fallback.isEmpty()) {
                    saveAll(prefs, fallback);
                    return fallback;
                }
            }
        }
        return items;
    }

    public static List<ContinueWatchingItem> getAll(Context context) {
        if (context == null) return new ArrayList<>();
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAllWithFallback(context, prefs);
        deduplicateSeries(items);
        trimToMax(items);
        Collections.sort(items, (a, b) -> Long.compare(b.getLastPlayedAt(), a.getLastPlayedAt()));
        return items;
    }

    public static ContinueWatchingItem getByPostId(Context context, int postId) {
        if (context == null || postId <= 0) return null;
        for (ContinueWatchingItem item : getAll(context)) {
            if (item.getPostId() == postId) return item;
        }
        return null;
    }

    /** Busca el seguimiento de una obra aunque su proveedor no exponga postId. */
    public static ContinueWatchingItem getForMedia(
            Context context, int postId, String title, String detailUrl, String mediaType) {
        if (context == null) return null;
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        boolean series = type.contains("tv") || type.contains("series")
                || type.contains("anime") || type.contains("dorama");
        String normalizedTitle = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        String normalizedUrl = detailUrl == null ? "" : detailUrl.trim().toLowerCase(Locale.ROOT);
        for (ContinueWatchingItem item : getAll(context)) {
            if (item == null) continue;
            if (postId > 0 && item.getPostId() == postId) return item;
            if (series && isSeries(item)) {
                if (!normalizedTitle.isEmpty() && normalizedTitle.equals(getCleanSeriesTitle(item))) return item;
                String itemUrl = item.getDetailUrl() == null ? "" : item.getDetailUrl().trim().toLowerCase(Locale.ROOT);
                if (!normalizedUrl.isEmpty() && normalizedUrl.equals(itemUrl)) return item;
            }
            if (!series && !isSeries(item)) {
                String itemTitle = item.getTitle() != null ? item.getTitle().trim().toLowerCase(Locale.ROOT) : "";
                if (!normalizedTitle.isEmpty() && normalizedTitle.equals(itemTitle)) return item;
                String itemUrl = item.getDetailUrl() == null ? "" : item.getDetailUrl().trim().toLowerCase(Locale.ROOT);
                if (!normalizedUrl.isEmpty() && normalizedUrl.equals(itemUrl)) return item;
            }
        }
        return null;
    }

    public static ContinueWatchingItem getByContentId(Context context, String contentId) {
        if (context == null || contentId == null) return null;
        for (ContinueWatchingItem item : getAll(context)) {
            if (contentId.equals(item.getContentId())) return item;
        }
        return null;
    }

    public static void remove(Context context, String contentId) {
        if (context == null || contentId == null) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAll(prefs);
        items.removeIf(item -> contentId.equals(item.getContentId()));
        saveAll(prefs, items);

        SharedPreferences defaultPrefs = getDefaultPrefs(context);
        if (defaultPrefs != prefs) {
            List<ContinueWatchingItem> defItems = loadAll(defaultPrefs);
            defItems.removeIf(item -> contentId.equals(item.getContentId()));
            saveAll(defaultPrefs, defItems);
        }
    }

    public static void removeByPostId(Context context, int postId) {
        if (context == null || postId <= 0) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAll(prefs);
        items.removeIf(item -> item.getPostId() == postId);
        saveAll(prefs, items);

        SharedPreferences defaultPrefs = getDefaultPrefs(context);
        if (defaultPrefs != prefs) {
            List<ContinueWatchingItem> defItems = loadAll(defaultPrefs);
            defItems.removeIf(item -> item.getPostId() == postId);
            saveAll(defaultPrefs, defItems);
        }
    }

    public static void markCompleted(Context context, String contentId) {
        if (context == null || contentId == null) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAll(prefs);
        ContinueWatchingItem completedItem = null;
        for (ContinueWatchingItem item : items) {
            if (contentId.equals(item.getContentId())) {
                item.setCompleted(true);
                item.setProgressPercent(100);
                item.setEverCompleted(true);
                item.setLastPlayedAt(System.currentTimeMillis());
                completedItem = item;
                break;
            }
        }
        saveAll(prefs, items);

        SharedPreferences defaultPrefs = getDefaultPrefs(context);
        if (defaultPrefs != prefs) {
            List<ContinueWatchingItem> defItems = loadAll(defaultPrefs);
            for (ContinueWatchingItem item : defItems) {
                if (contentId.equals(item.getContentId())) {
                    item.setCompleted(true);
                    item.setProgressPercent(100);
                    item.setEverCompleted(true);
                    item.setLastPlayedAt(System.currentTimeMillis());
                    break;
                }
            }
            saveAll(defaultPrefs, defItems);
        }

        if (completedItem != null) {
            syncToFirestore(context, completedItem);
            if (isSeries(completedItem)) {
                queueNextEpisodeIfAvailable(context, completedItem);
            }
        }
    }

    public static int parseSeasonNumber(ContinueWatchingItem item) {
        if (item == null) return 1;
        if (item.getSeasonNumber() > 0) return item.getSeasonNumber();
        String[] candidates = { item.getTitle(), item.getContentId(), item.getDetailUrl() };
        for (String s : candidates) {
            if (s == null || s.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(?i)(?:t|s|temporada\\s*)(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
            if (m2.find()) {
                try { return Integer.parseInt(m2.group(1)); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    public static int parseEpisodeNumber(ContinueWatchingItem item) {
        if (item == null) return 1;
        if (item.getEpisodeNumber() > 0) return item.getEpisodeNumber();
        String[] candidates = { item.getTitle(), item.getContentId(), item.getDetailUrl() };
        for (String s : candidates) {
            if (s == null || s.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                try { return Integer.parseInt(m.group(2)); } catch (Exception ignored) {}
            }
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(?i)(?:e|ep|episodio|capitulo\\s*)(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
            if (m2.find()) {
                try { return Integer.parseInt(m2.group(1)); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    public static ContinueWatchingItem resolveNextEpisodeItem(ContinueWatchingItem currentItem) {
        if (currentItem == null || !isSeries(currentItem)) return null;

        String seriesTitle = currentItem.getSeriesTitle();
        if (seriesTitle == null || seriesTitle.trim().isEmpty()) {
            seriesTitle = getCleanSeriesTitle(currentItem);
        }
        int curSeason = parseSeasonNumber(currentItem);
        int curEpisode = parseEpisodeNumber(currentItem);
        int curEpId = currentItem.getEpisodeId();

        int nextSeason = -1;
        int nextEpisode = -1;
        int nextEpId = -1;
        String nextTitle = "";

        // 1. Intentar buscar en SeriesSeasonsCache (en memoria)
        List<AllCalidadScraper.Season> seasons = SeriesSeasonsCache.get(
                seriesTitle, currentItem.getPostId(), currentItem.getDetailUrl());

        if (seasons != null && !seasons.isEmpty()) {
            boolean foundCurrent = false;
            for (AllCalidadScraper.Season season : seasons) {
                if (season == null || season.episodes == null) continue;
                int sNum = season.seasonNumber > 0 ? season.seasonNumber : 1;
                for (AllCalidadScraper.Episode ep : season.episodes) {
                    if (ep == null) continue;
                    int eNum = ep.episodeNumber > 0 ? ep.episodeNumber : 1;
                    int id = ep.id > 0 ? ep.id : Math.abs((sNum + "x" + eNum).hashCode() % 1000000) + 100000;

                    if (foundCurrent) {
                        nextSeason = sNum;
                        nextEpisode = eNum;
                        nextEpId = id;
                        nextTitle = ep.title != null ? ep.title : "";
                        break;
                    }

                    if ((sNum == curSeason && eNum == curEpisode) || (curEpId > 0 && id == curEpId)) {
                        foundCurrent = true;
                    }
                }
                if (nextEpisode > 0) break;
            }

            // Si encontró el actual pero no el inmediato o hubo saltos de temporada
            if (nextEpisode <= 0) {
                for (AllCalidadScraper.Season season : seasons) {
                    if (season == null || season.episodes == null) continue;
                    int sNum = season.seasonNumber > 0 ? season.seasonNumber : 1;
                    for (AllCalidadScraper.Episode ep : season.episodes) {
                        if (ep == null) continue;
                        int eNum = ep.episodeNumber > 0 ? ep.episodeNumber : 1;
                        int id = ep.id > 0 ? ep.id : Math.abs((sNum + "x" + eNum).hashCode() % 1000000) + 100000;

                        if (sNum > curSeason || (sNum == curSeason && eNum > curEpisode)) {
                            nextSeason = sNum;
                            nextEpisode = eNum;
                            nextEpId = id;
                            nextTitle = ep.title != null ? ep.title : "";
                            break;
                        }
                    }
                    if (nextEpisode > 0) break;
                }
            }

            // Si los seasons existían en caché pero no hay ningún episodio posterior,
            // significa que completó el último episodio de la serie.
            if (nextEpisode <= 0 && foundCurrent) {
                return null;
            }
        }

        // 2. Si no estaba en caché de temporadas, calcular siguiente episodio secuencialmente:
        // Nunca inferir E+1 sin un catálogo real: puede no existir.
        if (nextEpisode <= 0) return null;

        ContinueWatchingItem nextItem = new ContinueWatchingItem();
        String contentId = (currentItem.getPostId() > 0 && nextEpId > 0)
                ? (currentItem.getPostId() + "_" + nextEpId)
                : (seriesTitle + "_s" + nextSeason + "e" + nextEpisode);
        nextItem.setContentId(contentId);

        String epCode = String.format(Locale.getDefault(), "T%d:E%d", nextSeason, nextEpisode);
        String displayTitle = (nextTitle != null && !nextTitle.trim().isEmpty())
                ? (seriesTitle + " - " + epCode + " " + nextTitle.trim())
                : (seriesTitle + " - " + epCode);

        nextItem.setTitle(displayTitle);
        nextItem.setSeriesTitle(seriesTitle);
        nextItem.setImageUrl(currentItem.getImageUrl());
        nextItem.setDetailUrl(currentItem.getDetailUrl());
        nextItem.setSource(currentItem.getSource());
        nextItem.setMediaType(currentItem.getMediaType() != null ? currentItem.getMediaType() : "tvshows");
        nextItem.setPostId(currentItem.getPostId());
        nextItem.setTmdbId(currentItem.getTmdbId());
        nextItem.setEpisodeId(nextEpId);
        nextItem.setSeasonNumber(nextSeason);
        nextItem.setEpisodeNumber(nextEpisode);
        nextItem.setPositionMs(0L); // 00:00 para empezar desde el principio
        nextItem.setDurationMs(0L);
        nextItem.setProgressPercent(0);
        nextItem.setCompleted(false);
        nextItem.setLastPlayedAt(System.currentTimeMillis());

        return nextItem;
    }

    public static void queueNextEpisodeIfAvailable(Context context, ContinueWatchingItem completedItem) {
        if (context == null || completedItem == null || !isSeries(completedItem)) return;
        ContinueWatchingItem nextItem = resolveNextEpisodeItem(completedItem);
        if (nextItem != null) {
            save(context, nextItem);
        }
    }

    /** Reinicio explícito para "Ver de nuevo". No se usa el upsert normal
     * porque durante el arranque un player puede informar 00:00 antes de
     * aplicar una reanudación válida. */
    public static void prepareForReplay(Context context, String contentId) {
        if (context == null || contentId == null || contentId.isEmpty()) return;
        SharedPreferences prefs = getPrefs(context);
        List<ContinueWatchingItem> items = loadAllWithFallback(context, prefs);
        ContinueWatchingItem changed = null;
        for (ContinueWatchingItem item : items) {
            if (item != null && contentId.equals(item.getContentId())) {
                item.setPositionMs(0L);
                item.setDurationMs(0L);
                item.setProgressPercent(0);
                item.setCompleted(false);
                item.setEverCompleted(true);
                item.setLastPlayedAt(System.currentTimeMillis());
                changed = item;
                break;
            }
        }
        if (changed == null) return;
        saveAll(prefs, items);
        SharedPreferences defaultPrefs = getDefaultPrefs(context);
        if (defaultPrefs != prefs) saveAll(defaultPrefs, items);
        touchLocalUpdatedAt(context);
        syncToFirestore(context, changed);
    }

    public static boolean hasData(Context context) {
        if (context == null) return false;
        if (getPrefs(context).contains(KEY_ITEMS)) {
            List<ContinueWatchingItem> items = loadAll(getPrefs(context));
            if (!items.isEmpty()) return true;
        }
        return getDefaultPrefs(context).contains(KEY_ITEMS) && !loadAll(getDefaultPrefs(context)).isEmpty();
    }

    public static void clear(Context context) {
        if (context == null) return;
        getPrefs(context).edit().remove(KEY_ITEMS).apply();
        getDefaultPrefs(context).edit().remove(KEY_ITEMS).apply();
    }

    /** Limpia los datos de continuar viendo del usuario actualmente logueado. */
    public static void clearForCurrentUser(Context context) {
        if (context == null) return;
        clear(context);
    }

    private static List<ContinueWatchingItem> loadAll(SharedPreferences prefs) {
        String json = prefs.getString(KEY_ITEMS, null);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            Type type = new TypeToken<List<ContinueWatchingItem>>() {}.getType();
            List<ContinueWatchingItem> items = gson.fromJson(json, type);
            return items != null ? items : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void saveAll(SharedPreferences prefs, List<ContinueWatchingItem> items) {
        String json = gson.toJson(items);
        prefs.edit().putString(KEY_ITEMS, json).apply();
    }

    private static void trimToMax(List<ContinueWatchingItem> items) {
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
    }
}
