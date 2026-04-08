package sv.edu.catolica.rex.utils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class HttpUtils {
    private static final OkHttpClient client = new OkHttpClient();

    public static String get(String url, String userAgent) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/json")
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) return null;
            return res.body() != null ? res.body().string() : null;
        }
    }
}
