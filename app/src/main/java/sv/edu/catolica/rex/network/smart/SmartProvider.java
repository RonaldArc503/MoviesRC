package sv.edu.catolica.rex.network.smart;

public interface SmartProvider {
    String getName();
    boolean supports(SmartContentType contentType);
    SmartPlaybackResult resolve(SmartPlaybackRequest request) throws Exception;
}
