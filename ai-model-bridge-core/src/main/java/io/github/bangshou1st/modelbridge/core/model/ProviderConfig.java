package io.github.bangshou1st.modelbridge.core.model;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable upstream connection settings. Credentials are supplied at call time and must never
 * be logged or persisted by the library.
 */
public record ProviderConfig(
        String id,
        ProviderType type,
        String baseUrl,
        String apiKey,
        Map<String, String> headers,
        Duration connectTimeout,
        Duration requestTimeout,
        Map<String, Object> options
) {
    public ProviderConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
        options = options == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(options));
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofMinutes(2) : requestTimeout;
    }

    public ProviderConfig(String id, ProviderType type, String baseUrl, String apiKey) {
        this(id, type, baseUrl, apiKey, Map.of(), null, null, Map.of());
    }

    public static ProviderConfig openAiCompatible(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProviderType.OPENAI_COMPATIBLE, baseUrl, apiKey);
    }

    public static ProviderConfig anthropic(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProviderType.ANTHROPIC, baseUrl, apiKey);
    }

    public static ProviderConfig gemini(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProviderType.GEMINI, baseUrl, apiKey);
    }

    public static ProviderConfig ollama(String id, String baseUrl) {
        return new ProviderConfig(id, ProviderType.OLLAMA, baseUrl, "");
    }

    public static ProviderConfig llamaCpp(String id, String baseUrl, String apiKey) {
        return new ProviderConfig(id, ProviderType.LLAMA_CPP, baseUrl, apiKey);
    }

    public ProviderConfig withTimeout(Duration requestTimeout) {
        return new ProviderConfig(id, type, baseUrl, apiKey, headers, connectTimeout, requestTimeout, options);
    }
}
