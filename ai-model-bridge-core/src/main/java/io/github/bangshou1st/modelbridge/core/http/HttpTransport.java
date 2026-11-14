package io.github.bangshou1st.modelbridge.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Small synchronous transport around java.net.http.HttpClient. Redirects are never followed,
 * responses are bounded, and errors are normalized without exposing upstream bodies.
 */
public final class HttpTransport {
    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);
    private static final int MAX_BODY_CHARS = 8 * 1024 * 1024;
    private static final int MAX_ERROR_BODY_CHARS = 8 * 1024;

    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy policy;
    private final HttpClient client;
    private final Duration connectTimeout;
    private final Duration defaultRequestTimeout;

    public HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy) {
        this(mapper, policy, Duration.ofSeconds(10), Duration.ofMinutes(2),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    public HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy, Duration connectTimeout,
                         Duration defaultRequestTimeout, HttpClient client) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        this.defaultRequestTimeout = defaultRequestTimeout == null ? Duration.ofMinutes(2) : defaultRequestTimeout;
        this.client = Objects.requireNonNull(client, "client");
    }

    public JsonNode getJson(ProviderConfig config, String url, Map<String, String> headers) {
        URI uri = policy.validate(url).uri();
        HttpRequest request = request(config, uri, headers, "application/json")
                .GET().build();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body(), config, response.headers());
            log.debug("GET {} completed in {} ms", safeUri(uri), elapsedMs(started));
            return parseJson(response.body(), config);
        } catch (ModelBridgeException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw timeout(config, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw timeout(config, e);
        } catch (IOException e) {
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "upstream network failure", config.id(), 0, null, null, e);
        }
    }

    public JsonNode postJson(ProviderConfig config, String url, Map<String, String> headers, JsonNode body) {
        URI uri = policy.validate(url).uri();
        HttpRequest request = request(config, uri, headers, "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body(), config, response.headers());
            log.debug("POST {} completed in {} ms", safeUri(uri), elapsedMs(started));
            return parseJson(response.body(), config);
        } catch (ModelBridgeException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw timeout(config, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw timeout(config, e);
        } catch (IOException e) {
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "upstream network failure", config.id(), 0, null, null, e);
        }
    }

    public void postSse(ProviderConfig config, String url, Map<String, String> headers, JsonNode body,
                        Consumer<SseParser.Event> consumer) {
        URI uri = policy.validate(url).uri();
        HttpRequest request = request(config, uri, headers, "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    ensureSuccess(response.statusCode(), boundedError(input), config, response.headers());
                }
                String contentType = response.headers().firstValue("content-type")
                        .orElse("").toLowerCase(Locale.ROOT);
                if (!contentType.startsWith("text/event-stream")) {
                    throw ModelBridgeException.invalidResponse(
                            "upstream did not return text/event-stream", config.id(), null);
                }
                SseParser.parse(input, event -> {
                    if ("[DONE]".equals(event.data())) {
                        consumer.accept(event);
                    } else {
                        consumer.accept(event);
                    }
                });
            }
        } catch (ModelBridgeException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw timeout(config, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw timeout(config, e);
        } catch (IOException e) {
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "upstream stream failure", config.id(), 0, null, null, e);
        }
    }

    public JsonNode parseJson(String body, ProviderConfig config) {
        if (body == null || body.isBlank()) {
            throw ModelBridgeException.invalidResponse("empty upstream response", config.id(), null);
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw ModelBridgeException.invalidResponse("upstream response exceeds size limit", config.id(), null);
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw ModelBridgeException.invalidResponse("invalid upstream JSON", config.id(), null);
        }
    }

    private HttpRequest.Builder request(ProviderConfig config, URI uri,
                                        Map<String, String> headers, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout() == null ? defaultRequestTimeout : config.requestTimeout())
                .header("Accept", accept)
                .header("User-Agent", "ai-model-bridge/0.1.2");
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank()) {
                    builder.header(key, value);
                }
            });
        }
        return builder;
    }

    private void ensureSuccess(int status, String body, ProviderConfig config,
                               java.net.http.HttpHeaders headers) {
        if (status >= 200 && status < 300) {
            return;
        }
        Duration retryAfter = retryAfter(headers);
        log.warn("upstream rejected request: provider={}, status={}, bodyChars={}",
                config.id(), status, body == null ? 0 : body.length());
        throw ModelBridgeException.fromHttpStatus(status, config.id(), retryAfter);
    }

    private static String boundedError(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_ERROR_BODY_CHARS);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Duration retryAfter(java.net.http.HttpHeaders headers) {
        var value = headers.firstValue("retry-after");
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.get().strip()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String write(JsonNode body) {
        try {
            return mapper.writeValueAsString(body == null ? mapper.createObjectNode() : body);
        } catch (Exception e) {
            throw new ModelBridgeException(ErrorCategory.INTERNAL, "could not encode request", null);
        }
    }

    private static ModelBridgeException timeout(ProviderConfig config, Exception cause) {
        return new ModelBridgeException(ErrorCategory.TIMEOUT, "upstream timed out", config.id(),
                0, null, null, cause);
    }

    private static String safeUri(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                + (uri.getPath() == null ? "" : uri.getPath());
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
