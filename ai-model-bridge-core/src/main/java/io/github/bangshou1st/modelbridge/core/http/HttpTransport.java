package io.github.bangshou1st.modelbridge.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.client.DefaultStreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ProxyConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Small synchronous transport around java.net.http.HttpClient. Redirects are never followed,
 * responses are bounded, and errors are normalized without exposing upstream bodies.
 *
 * <p>The JDK client does not expose a public DNS resolver hook. This transport therefore performs
 * a complete pre-flight DNS answer validation and relies on a custom preconfigured
 * {@link HttpClient} constructor for deployments that need connection-layer pinning. It never
 * claims that a pre-flight answer has already been pinned by the default client.</p>
 */
public final class HttpTransport {
    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);
    private static final int MAX_BODY_CHARS = 8 * 1024 * 1024;
    private static final int MAX_ERROR_BODY_CHARS = 8 * 1024;

    private final ObjectMapper mapper;
    private final OutboundEndpointPolicy policy;
    private final HttpClient client;
    private final CloseableHttpClient pinnedClient;
    private final Duration connectTimeout;
    private final Duration defaultRequestTimeout;
    private final ProxyConfig configuredProxy;
    private final Map<ProxyConfig, HttpClient> proxyClients = new ConcurrentHashMap<>();

    public HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy) {
        this(mapper, policy, Duration.ofSeconds(10), Duration.ofMinutes(2));
    }

    public HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy, Duration connectTimeout,
                         Duration defaultRequestTimeout, HttpClient client) {
        this(mapper, policy, connectTimeout, defaultRequestTimeout, client,
                ProxyConfig.disabled(), null);
    }

    /**
     * Creates a transport with connection-time public-only DNS enforcement when
     * the policy is {@code PUBLIC_HTTPS_ONLY}. Private-network policies keep the
     * legacy JDK client so local mocks and explicitly allowed private endpoints
     * continue to work.
     */
    public HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy,
                         Duration connectTimeout, Duration defaultRequestTimeout) {
        this(mapper, policy, connectTimeout, defaultRequestTimeout,
                client(connectTimeout, ProxyConfig.disabled()), ProxyConfig.disabled(),
                policy.mode() == io.github.bangshou1st.modelbridge.core.security.NetworkPolicyMode.PUBLIC_HTTPS_ONLY
                        ? pinnedClient(policy, connectTimeout, defaultRequestTimeout) : null);
    }

    HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy, Duration connectTimeout,
                  Duration defaultRequestTimeout, HttpClient client, ProxyConfig proxy,
                  CloseableHttpClient pinnedClient) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        this.defaultRequestTimeout = defaultRequestTimeout == null ? Duration.ofMinutes(2) : defaultRequestTimeout;
        this.client = Objects.requireNonNull(client, "client");
        this.pinnedClient = pinnedClient;
        this.configuredProxy = proxy == null ? ProxyConfig.disabled() : proxy;
    }

    public HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy, ProxyConfig proxy) {
        this(mapper, policy, Duration.ofSeconds(10), Duration.ofMinutes(2),
                client(Duration.ofSeconds(10), proxy), proxy, null);
    }

    public HttpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy,
                         Duration connectTimeout, Duration defaultRequestTimeout,
                         ProxyConfig proxy) {
        this(mapper, policy, connectTimeout, defaultRequestTimeout,
                client(connectTimeout, proxy), proxy, null);
    }

    public ProxyConfig proxy() {
        return configuredProxy;
    }

    public JsonNode getJson(ProviderConfig config, String url, Map<String, String> headers) {
        URI uri = policy.validate(url).uri();
        if (pinnedClient != null) {
            return getJsonPinned(config, uri, headers);
        }
        HttpRequest request = request(config, uri, headers, "application/json")
                .GET().build();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = clientFor(config).send(request,
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
        if (pinnedClient != null) {
            return postJsonPinned(config, uri, headers, body);
        }
        HttpRequest request = request(config, uri, headers, "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = clientFor(config).send(request,
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
        postSse(config, url, headers, body, consumer, new DefaultStreamHandle());
    }

    public void postSse(ProviderConfig config, String url, Map<String, String> headers, JsonNode body,
                        Consumer<SseParser.Event> consumer, StreamHandle handle) {
        Objects.requireNonNull(handle, "handle");
        URI uri = policy.validate(url).uri();
        if (pinnedClient != null) {
            postSsePinned(config, uri, headers, body, consumer, handle);
            return;
        }
        HttpRequest request = request(config, uri, headers, "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build();
        if (handle.isCancelled()) {
            return;
        }
        Thread owner = Thread.currentThread();
        AtomicBoolean interruptible = new AtomicBoolean(true);
        AtomicReference<InputStream> responseBody = new AtomicReference<>();
        handle.onCancel(() -> {
            InputStream input = responseBody.getAndSet(null);
            if (input != null) {
                closeQuietly(input);
            }
            if (interruptible.compareAndSet(true, false)) {
                owner.interrupt();
            }
        });
        try {
            HttpResponse<InputStream> response = clientFor(config).send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            InputStream bodyStream = response.body();
            responseBody.set(bodyStream);
            if (handle.isCancelled()) {
                closeQuietly(bodyStream);
                return;
            }
            try (InputStream input = bodyStream) {
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
                    if (handle.isCancelled()) {
                        return;
                    }
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
            if (handle.isCancelled()) {
                return;
            }
            throw timeout(config, e);
        } catch (InterruptedException e) {
            if (handle.isCancelled()) {
                Thread.interrupted();
                return;
            }
            Thread.currentThread().interrupt();
            throw timeout(config, e);
        } catch (IOException e) {
            if (handle.isCancelled()) {
                Thread.interrupted();
                return;
            }
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "upstream stream failure", config.id(), 0, null, null, e);
        } finally {
            interruptible.set(false);
            responseBody.set(null);
            if (handle.isCancelled()) {
                Thread.interrupted();
            }
        }
    }

    private JsonNode getJsonPinned(ProviderConfig config, URI uri, Map<String, String> headers) {
        var request = pinnedRequest(config, uri, headers, "application/json", null);
        long started = System.nanoTime();
        try (var response = pinnedClient.execute(request)) {
            var body = responseBody(response);
            ensureSuccess(response.getCode(), body, config, headerValue(response, "retry-after"));
            log.debug("GET {} completed in {} ms", safeUri(uri), elapsedMs(started));
            return parseJson(body, config);
        } catch (ModelBridgeException e) {
            throw e;
        } catch (IOException e) {
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "upstream network failure", config.id(), 0, null, null, e);
        }
    }

    private JsonNode postJsonPinned(ProviderConfig config, URI uri, Map<String, String> headers,
                                    JsonNode body) {
        var request = pinnedRequest(config, uri, headers, "application/json", write(body));
        long started = System.nanoTime();
        try (var response = pinnedClient.execute(request)) {
            var responseBody = responseBody(response);
            ensureSuccess(response.getCode(), responseBody, config, headerValue(response, "retry-after"));
            log.debug("POST {} completed in {} ms", safeUri(uri), elapsedMs(started));
            return parseJson(responseBody, config);
        } catch (ModelBridgeException e) {
            throw e;
        } catch (IOException e) {
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "upstream network failure", config.id(), 0, null, null, e);
        }
    }

    private void postSsePinned(ProviderConfig config, URI uri, Map<String, String> headers,
                               JsonNode body, Consumer<SseParser.Event> consumer, StreamHandle handle) {
        var request = pinnedRequest(config, uri, headers, "text/event-stream", write(body));
        if (handle.isCancelled()) {
            return;
        }
        Thread owner = Thread.currentThread();
        AtomicBoolean interruptible = new AtomicBoolean(true);
        AtomicReference<InputStream> responseBody = new AtomicReference<>();
        handle.onCancel(() -> {
            InputStream input = responseBody.getAndSet(null);
            if (input != null) {
                closeQuietly(input);
            }
            if (interruptible.compareAndSet(true, false)) {
                owner.interrupt();
            }
        });
        try (var response = pinnedClient.execute(request)) {
            var entity = response.getEntity();
            if (entity == null) {
                throw ModelBridgeException.invalidResponse(
                        "upstream returned no response body", config.id(), null);
            }
            if (response.getCode() < 200 || response.getCode() >= 300) {
                ensureSuccess(response.getCode(), boundedError(entity.getContent()), config,
                        headerValue(response, "retry-after"));
            }
            var contentType = headerValue(response, "content-type");
            if (contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                throw ModelBridgeException.invalidResponse(
                        "upstream did not return text/event-stream", config.id(), null);
            }
            var input = entity.getContent();
            responseBody.set(input);
            if (handle.isCancelled()) {
                closeQuietly(input);
                return;
            }
            try (input) {
                SseParser.parse(input, event -> {
                    if (!handle.isCancelled()) {
                        consumer.accept(event);
                    }
                });
            }
        } catch (ModelBridgeException e) {
            throw e;
        } catch (IOException e) {
            if (handle.isCancelled()) {
                Thread.interrupted();
                return;
            }
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "upstream stream failure", config.id(), 0, null, null, e);
        } finally {
            interruptible.set(false);
            responseBody.set(null);
            if (handle.isCancelled()) {
                Thread.interrupted();
            }
        }
    }

    private org.apache.hc.core5.http.ClassicHttpRequest pinnedRequest(
            ProviderConfig config, URI uri, Map<String, String> headers,
            String accept, String body) {
        org.apache.hc.core5.http.ClassicHttpRequest request = body == null
                ? new HttpGet(uri)
                : new HttpPost(uri);
        request.setHeader("Accept", accept);
        request.setHeader("User-Agent", "ai-model-bridge/0.2.1");
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank()) {
                    request.setHeader(key, value);
                }
            });
        }
        if (body != null && request instanceof HttpPost post) {
            post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        }
        var timeout = config.requestTimeout() == null ? defaultRequestTimeout : config.requestTimeout();
        if (request instanceof org.apache.hc.client5.http.classic.methods.HttpUriRequestBase base) {
            base.setConfig(RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                    .setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis()))
                    .build());
        }
        return request;
    }

    private static String responseBody(CloseableHttpResponse response) throws IOException {
        var entity = response.getEntity();
        if (entity == null) {
            return "";
        }
        try {
            return EntityUtils.toString(entity, StandardCharsets.UTF_8);
        } catch (ParseException failure) {
            throw new IOException("upstream response body could not be decoded", failure);
        }
    }

    private static String headerValue(
            org.apache.hc.core5.http.MessageHeaders headers, String name) {
        var header = headers.getFirstHeader(name);
        return header == null ? null : header.getValue();
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
                .header("User-Agent", "ai-model-bridge/0.2.1");
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
        ensureSuccess(status, body, config, headers.firstValue("retry-after").orElse(null));
    }

    private void ensureSuccess(int status, String body, ProviderConfig config,
                               String retryAfterValue) {
        if (status >= 200 && status < 300) {
            return;
        }
        Duration retryAfter = retryAfter(retryAfterValue);
        log.warn("upstream rejected request: provider={}, status={}, bodyChars={}",
                config.id(), status, body == null ? 0 : body.length());
        throw ModelBridgeException.fromHttpStatus(status, config.id(), retryAfter);
    }

    private static String boundedError(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_ERROR_BODY_CHARS);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (RuntimeException ignored) {
            try {
                ZonedDateTime when = ZonedDateTime.parse(value.strip(),
                        DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration delay = Duration.between(ZonedDateTime.now(when.getZone()), when);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }

    private String write(JsonNode body) {
        try {
            return mapper.writeValueAsString(body == null ? mapper.createObjectNode() : body);
        } catch (Exception e) {
            throw new ModelBridgeException(ErrorCategory.INTERNAL, "could not encode request", null);
        }
    }

    private HttpClient clientFor(ProviderConfig config) {
        ProxyConfig proxy = config.proxy();
        if (proxy == null || !proxy.enabled()) {
            return client;
        }
        return proxyClients.computeIfAbsent(proxy, value ->
                client(config.connectTimeout() == null ? connectTimeout : config.connectTimeout(), value));
    }

    private static HttpClient client(Duration connectTimeout, ProxyConfig proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null && proxy.enabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
            if (proxy.username() != null && !proxy.username().isBlank()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(proxy.username(),
                                proxy.password() == null ? new char[0] : proxy.password().toCharArray());
                    }
                });
            }
        }
        return builder.build();
    }

    static CloseableHttpClient pinnedClient(
            OutboundEndpointPolicy policy, Duration connectTimeout, Duration defaultRequestTimeout) {
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicOnlyDnsResolver(policy))
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(defaultRequestTimeout.toMillis()))
                        .build())
                .build();
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Cancellation is best effort and must not leak the stream failure.
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
