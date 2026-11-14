package io.github.bangshou1st.modelbridge.core.model;

import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable upstream connection settings. Credentials are supplied at call time and are never
 * rendered by {@link #toString()}.
 */
public final class ProviderConfig {
    private final String id;
    private final ProtocolId protocol;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> headers;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Map<String, Object> options;
    private final EndpointConfig endpointConfig;
    private final ProxyConfig proxy;

    public ProviderConfig(String id, ProtocolId protocol, String baseUrl, String apiKey,
                          Map<String, String> headers, Duration connectTimeout,
                          Duration requestTimeout, Map<String, Object> options) {
        this(id, protocol, baseUrl, apiKey, headers, connectTimeout, requestTimeout, options,
                EndpointConfig.empty(), ProxyConfig.disabled());
    }

    public ProviderConfig(String id, ProviderType type, String baseUrl, String apiKey,
                          Map<String, String> headers, Duration connectTimeout,
                          Duration requestTimeout, Map<String, Object> options) {
        this(id, protocol(type), baseUrl, apiKey, headers, connectTimeout, requestTimeout, options,
                EndpointConfig.empty(), ProxyConfig.disabled());
    }

    public ProviderConfig(String id, ProtocolId protocol, String baseUrl, String apiKey,
                          Map<String, String> headers, Duration connectTimeout,
                          Duration requestTimeout, Map<String, Object> options,
                          EndpointConfig endpointConfig, ProxyConfig proxy) {
        this.id = Objects.requireNonNull(id, "id");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").strip();
        if (this.baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        this.apiKey = apiKey == null ? "" : apiKey;
        this.headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        this.options = options == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(options));
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        this.requestTimeout = requestTimeout == null ? Duration.ofMinutes(2) : requestTimeout;
        this.endpointConfig = endpointConfig == null ? EndpointConfig.empty() : endpointConfig;
        this.proxy = proxy == null ? ProxyConfig.disabled() : proxy;
    }

    public ProviderConfig(String id, ProviderType type, String baseUrl, String apiKey) {
        this(id, type, baseUrl, apiKey, Map.of(), null, null, Map.of());
    }

    public ProviderConfig(String id, ProtocolId protocol, String baseUrl, String apiKey) {
        this(id, protocol, baseUrl, apiKey, Map.of(), null, null, Map.of());
    }

    public static ProviderConfig openAiCompatible(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProtocolId.OPENAI_COMPATIBLE, baseUrl, apiKey);
    }

    public static ProviderConfig anthropic(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProtocolId.ANTHROPIC, baseUrl, apiKey);
    }

    public static ProviderConfig gemini(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProtocolId.GEMINI, baseUrl, apiKey);
    }

    public static ProviderConfig ollama(String id, String baseUrl) {
        return new ProviderConfig(id, ProtocolId.OLLAMA, baseUrl, "");
    }

    public static ProviderConfig llamaCpp(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProtocolId.LLAMA_CPP, baseUrl, apiKey);
    }

    public static Builder builder(String id, ProtocolId protocol) {
        return new Builder(id, protocol);
    }

    public String id() {
        return id;
    }

    public ProtocolId protocol() {
        return protocol;
    }

    /**
     * Compatibility accessor for built-in protocols. Custom protocols use {@link #protocol()}.
     */
    @Deprecated
    public ProviderType type() {
        return ProviderType.fromProtocolId(protocol)
                .orElseThrow(() -> new IllegalStateException(
                        "custom protocol has no ProviderType: " + protocol.value()));
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Map<String, Object> options() {
        return options;
    }

    public EndpointConfig endpointConfig() {
        return endpointConfig;
    }

    public EndpointConfig endpoints() {
        return endpointConfig;
    }

    public ProxyConfig proxy() {
        return proxy;
    }

    public ProviderConfig withTimeout(Duration newRequestTimeout) {
        return new ProviderConfig(id, protocol, baseUrl, apiKey, headers, connectTimeout,
                newRequestTimeout, options, endpointConfig, proxy);
    }

    public ProviderConfig withOptions(Map<String, Object> newOptions) {
        return new ProviderConfig(id, protocol, baseUrl, apiKey, headers, connectTimeout,
                requestTimeout, newOptions, endpointConfig, proxy);
    }

    public ProviderConfig withApiKey(String newApiKey) {
        return new ProviderConfig(id, protocol, baseUrl, newApiKey, headers, connectTimeout,
                requestTimeout, options, endpointConfig, proxy);
    }

    public ProviderConfig withBaseUrl(String newBaseUrl) {
        return new ProviderConfig(id, protocol, newBaseUrl, apiKey, headers, connectTimeout,
                requestTimeout, options, endpointConfig, proxy);
    }

    public ProviderConfig withHeaders(Map<String, String> newHeaders) {
        return new ProviderConfig(id, protocol, baseUrl, apiKey, newHeaders, connectTimeout,
                requestTimeout, options, endpointConfig, proxy);
    }

    public ProviderConfig withEndpointConfig(EndpointConfig newEndpointConfig) {
        return new ProviderConfig(id, protocol, baseUrl, apiKey, headers, connectTimeout,
                requestTimeout, options, newEndpointConfig, proxy);
    }

    public ProviderConfig withProxy(ProxyConfig newProxy) {
        return new ProviderConfig(id, protocol, baseUrl, apiKey, headers, connectTimeout,
                requestTimeout, options, endpointConfig, newProxy);
    }

    private static ProtocolId protocol(ProviderType type) {
        return Objects.requireNonNull(type, "type").toProtocolId();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProviderConfig that)) {
            return false;
        }
        return id.equals(that.id) && protocol.equals(that.protocol) && baseUrl.equals(that.baseUrl)
                && apiKey.equals(that.apiKey) && headers.equals(that.headers)
                && connectTimeout.equals(that.connectTimeout)
                && requestTimeout.equals(that.requestTimeout) && options.equals(that.options)
                && endpointConfig.equals(that.endpointConfig) && proxy.equals(that.proxy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, protocol, baseUrl, apiKey, headers, connectTimeout,
                requestTimeout, options, endpointConfig, proxy);
    }

    @Override
    public String toString() {
        return "ProviderConfig[id=" + id + ", protocol=" + protocol.value()
                + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey.isBlank() ? "<empty>" : "<redacted>")
                + ", endpoints=" + endpointConfig + ", proxy=" + proxy + "]";
    }

    public static final class Builder {
        private final String id;
        private final ProtocolId protocol;
        private String baseUrl;
        private String apiKey = "";
        private Map<String, String> headers = new LinkedHashMap<>();
        private Duration connectTimeout;
        private Duration requestTimeout;
        private final Map<String, Object> options = new LinkedHashMap<>();
        private EndpointConfig endpointConfig = EndpointConfig.empty();
        private ProxyConfig proxy = ProxyConfig.disabled();

        private Builder(String id, ProtocolId protocol) {
            this.id = Objects.requireNonNull(id, "id");
            this.protocol = Objects.requireNonNull(protocol, "protocol");
        }

        public Builder baseUrl(String value) { this.baseUrl = value; return this; }
        public Builder apiKey(String value) { this.apiKey = value; return this; }
        public Builder headers(Map<String, String> value) {
            this.headers = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }
        public Builder header(String key, String value) { this.headers.put(key, value); return this; }
        public Builder connectTimeout(Duration value) { this.connectTimeout = value; return this; }
        public Builder requestTimeout(Duration value) { this.requestTimeout = value; return this; }
        public Builder options(Map<String, Object> value) {
            this.options.clear();
            if (value != null) {
                this.options.putAll(value);
            }
            return this;
        }
        public Builder option(String key, Object value) { this.options.put(key, value); return this; }
        public Builder endpointConfig(EndpointConfig value) { this.endpointConfig = value; return this; }
        public Builder proxy(ProxyConfig value) { this.proxy = value; return this; }

        public ProviderConfig build() {
            return new ProviderConfig(id, protocol, baseUrl, apiKey, headers, connectTimeout,
                    requestTimeout, options, endpointConfig, proxy);
        }
    }
}
