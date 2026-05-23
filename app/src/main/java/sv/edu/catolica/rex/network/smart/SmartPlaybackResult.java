package sv.edu.catolica.rex.network.smart;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SmartPlaybackResult {
    public String providerName;
    public String embedUrl;
    public final List<String> hostUrls = new ArrayList<>();
    public final List<String> streamUrls = new ArrayList<>();

    public boolean hasPlayableUrls() {
        return !toPlayableUrls().isEmpty();
    }

    public List<String> toPlayableUrls() {
        Set<String> merged = new LinkedHashSet<>();
        String embed = embedUrl == null ? "" : embedUrl.trim();
        boolean hasAlternatives = (hostUrls != null && !hostUrls.isEmpty())
                || (streamUrls != null && !streamUrls.isEmpty());
        boolean isXupalaceEmbed = !embed.isEmpty()
                && embed.toLowerCase(Locale.ROOT).contains("xupalace.org/");

        if (!embed.isEmpty() && (!isXupalaceEmbed || !hasAlternatives)) {
            merged.add(embed);
        }
        for (String url : hostUrls) {
            if (url != null && !url.trim().isEmpty()) {
                merged.add(url.trim());
            }
        }
        for (String url : streamUrls) {
            if (url != null && !url.trim().isEmpty()) {
                merged.add(url.trim());
            }
        }
        if (!embed.isEmpty() && isXupalaceEmbed && hasAlternatives) {
            merged.add(embed);
        }
        return new ArrayList<>(merged);
    }
}
