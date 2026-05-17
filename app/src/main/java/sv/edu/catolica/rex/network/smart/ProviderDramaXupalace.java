package sv.edu.catolica.rex.network.smart;

import java.util.Arrays;
import java.util.List;

public class ProviderDramaXupalace extends BaseSmartProvider {

    private static final List<String> DRAMA_HOSTS = Arrays.asList(
            "streamwish",
            "filemoon",
            "vidhide",
            "stape",
            "vox",
            "1fichier"
    );

    @Override
    public String getName() {
        return "ProviderDramaXupalace";
    }

    @Override
    public boolean supports(SmartContentType contentType) {
        return contentType == SmartContentType.DRAMA;
    }

    @Override
    public SmartPlaybackResult resolve(SmartPlaybackRequest request) throws Exception {
        String imdbId = normalizeImdbId(request.imdbId);
        if (imdbId.isEmpty()) {
            return new SmartPlaybackResult();
        }

        int season = request.seasonNumber > 0 ? request.seasonNumber : 1;
        int episode = request.episodeNumber > 0 ? request.episodeNumber : 1;
        String token = episodeToken(season, episode);
        String embedUrl = "https://xupalace.org/video/" + imdbId + "-" + token + "/";
        return resolveFromEmbed(embedUrl, getName(), DRAMA_HOSTS);
    }
}
