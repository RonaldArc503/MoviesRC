package sv.edu.catolica.rex.network;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared request pacing/backoff guard to reduce bursty traffic patterns
 * and respect server-side rate limiting signals.
 */
public final class RequestGuard {

    private static final Object LOCK = new Object();
    private static final Map<String, Long> hostNextAllowedAtMs = new ConcurrentHashMap<>();
    private static final Map<String, Integer> hostPenaltyLevel = new ConcurrentHashMap<>();

    private static final long MIN_JITTER_MS = 40L;
    private static final int MAX_PENALTY_LEVEL = 6;

    private RequestGuard() { }

    public static void waitForSlot(String urlString, long baseDelayMs, long jitterMs, int attempt) {
        String host = hostOf(urlString);
        long now = System.currentTimeMillis();
        long waitMs;
        long spacingMs = Math.max(80L, baseDelayMs) + randomBetween(0L, Math.max(0L, jitterMs));

        synchronized (LOCK) {
            long nextAllowed = hostNextAllowedAtMs.getOrDefault(host, now);
            int penalty = hostPenaltyLevel.getOrDefault(host, 0);
            long penaltyDelay = penaltyDelayMs(penalty);
            long retryPenalty = Math.max(0, attempt - 1) * 180L;

            waitMs = Math.max(0L, nextAllowed - now)
                    + randomBetween(MIN_JITTER_MS, Math.max(MIN_JITTER_MS, jitterMs))
                    + penaltyDelay
                    + retryPenalty;

            hostNextAllowedAtMs.put(host, Math.max(now, nextAllowed) + spacingMs);
        }

        sleepQuietly(waitMs);
    }

    public static void onResponse(String urlString, int statusCode, long retryAfterMs) {
        String host = hostOf(urlString);
        long now = System.currentTimeMillis();

        synchronized (LOCK) {
            int penalty = hostPenaltyLevel.getOrDefault(host, 0);
            if (statusCode == 429 || statusCode == 403 || statusCode >= 500) {
                penalty = Math.min(MAX_PENALTY_LEVEL, penalty + 1);
                hostPenaltyLevel.put(host, penalty);

                long forcedCooldown = Math.max(retryAfterMs, penaltyDelayMs(penalty));
                long nextAllowed = Math.max(now + forcedCooldown, hostNextAllowedAtMs.getOrDefault(host, now));
                hostNextAllowedAtMs.put(host, nextAllowed);
                return;
            }

            if (penalty > 0) {
                hostPenaltyLevel.put(host, penalty - 1);
            }
        }
    }

    public static long computeRetryDelayMs(int attempt, int statusCode, long retryAfterMs) {
        if (retryAfterMs > 0) {
            return retryAfterMs + randomBetween(80L, 220L);
        }

        long base;
        if (statusCode == 429 || statusCode == 403) {
            base = 1000L;
        } else if (statusCode >= 500) {
            base = 700L;
        } else {
            base = 550L;
        }

        long exponential = base * (1L << Math.max(0, Math.min(4, attempt - 1)));
        return Math.min(12_000L, exponential + randomBetween(60L, 240L));
    }

    private static long penaltyDelayMs(int level) {
        if (level <= 0) {
            return 0L;
        }
        long exponential = 260L * (1L << Math.max(0, Math.min(4, level - 1)));
        return Math.min(8_000L, exponential);
    }

    private static long randomBetween(long min, long max) {
        if (max <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1L);
    }

    private static void sleepQuietly(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostOf(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return "unknown";
        }
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            return host == null || host.trim().isEmpty() ? "unknown" : host.trim().toLowerCase();
        } catch (MalformedURLException e) {
            return "unknown";
        }
    }
}
