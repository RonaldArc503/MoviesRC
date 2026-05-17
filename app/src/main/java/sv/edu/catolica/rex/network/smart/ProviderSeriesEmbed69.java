package sv.edu.catolica.rex.network.smart;

import java.util.Collections;

public class ProviderSeriesEmbed69 extends BaseSmartProvider {

    @Override
    public String getName() {
        return "ProviderSeriesEmbed69";
    }

    @Override
    public boolean supports(SmartContentType contentType) {
        return contentType == SmartContentType.SERIES || contentType == SmartContentType.ANIME;
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
        String embedUrl = "https://embed69.org/f/" + imdbId + "-" + token + "/";
        return resolveFromEmbed(embedUrl, getName(), Collections.emptyList());
    }
}
