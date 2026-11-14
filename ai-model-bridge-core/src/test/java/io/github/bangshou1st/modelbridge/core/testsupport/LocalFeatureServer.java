package io.github.bangshou1st.modelbridge.core.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A tiny local HTTP server for real transport tests (no mocking framework). */
public final class LocalFeatureServer implements AutoCloseable {
    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();

    private LocalFeatureServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, handler));
        server.start();
    }

    public static LocalFeatureServer start(Handler handler) throws IOException {
        return new LocalFeatureServer(handler);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange, Handler handler) throws IOException {
        Request request = new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                headers(exchange),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        synchronized (requests) {
            requests.add(request);
        }
        Response response;
        try {
            response = handler.handle(request);
        } catch (Exception e) {
            response = new Response(500, "text/plain", "handler failure: " + e.getClass().getSimpleName());
        }
        byte[] body = response.body() == null ? new byte[0]
                : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                response.contentType() == null ? "application/json" : response.contentType());
        response.headers().forEach(exchange.getResponseHeaders()::set);
        if (response.status() == 204) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
        exchange.close();
    }

    private static Map<String, List<String>> headers(HttpExchange exchange) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) ->
                result.put(key.toLowerCase(Locale.ROOT), List.copyOf(values)));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Request(String method, String path, String query,
                          Map<String, List<String>> headers, String body) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    public record Response(int status, String contentType, String body,
                           Map<String, String> headers) {
        public Response(int status, String contentType, String body) {
            this(status, contentType, body, Map.of());
        }

        public Response {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }
}
