package sv.edu.catolica.rex.network.smart;

import java.util.Collections;

public class ProviderMovieEmbed69 extends BaseSmartProvider {

    @Override
    public String getName() {
        return "ProviderMovieEmbed69";
    }

    @Override
    public boolean supports(SmartContentType contentType) {
        return contentType == SmartContentType.MOVIE;
    }

    @Override
    public SmartPlaybackResult resolve(SmartPlaybackRequest request) throws Exception {
        String imdbId = normalizeImdbId(request.imdbId);
        if (imdbId.isEmpty()) {
            return new SmartPlaybackResult();
        }

        String embedUrl = "https://embed69.org/f/" + imdbId + "/";
        return resolveFromEmbed(embedUrl, getName(), Collections.emptyList());
    }
}
