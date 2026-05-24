package sv.edu.catolica.rex.source;

import java.util.Locale;
import sv.edu.catolica.rex.models.MediaItem;

public final class SourceMatcher {
    private SourceMatcher() {}

    public static String normalize(String value) {
        if (value == null) return "";
        String out = value.trim().toLowerCase(Locale.ROOT);
        out = out.replaceAll("[^a-z0-9\\s]+", " ");
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    public static boolean matchesByPriority(MediaItem item, String imdbId, int tmdbId, String title, String year) {
        if (item == null) return false;
        if (imdbId != null && !imdbId.trim().isEmpty() && imdbId.equalsIgnoreCase(item.getImdbId())) {
            return true;
        }
        if (tmdbId > 0 && tmdbId == item.getTmdbId()) {
            return true;
        }
        String lhs = normalize(item.getTitulo()) + "|" + normalize(item.getAnio());
        String rhs = normalize(title) + "|" + normalize(year);
        return !lhs.isEmpty() && lhs.equals(rhs);
    }

    public static int fuzzyScore(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        if (a.contains(b) || b.contains(a)) return 85;
        int distance = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        return Math.max(0, 100 - ((distance * 100) / Math.max(1, max)));
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
