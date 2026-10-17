package dev.stateverdict.core.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Minimal HTTP helper for talking to the system under test. */
public final class HttpJson {

    public record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Response get(URI uri) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build());
    }

    public Response post(URI uri, String jsonBody, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        headers.forEach(request::header);
        return send(request.build());
    }

    public Response delete(URI uri) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).DELETE().build());
    }

    private Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
