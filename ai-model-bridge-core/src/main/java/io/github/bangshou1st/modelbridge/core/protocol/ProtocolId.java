package io.github.bangshou1st.modelbridge.core.protocol;

import java.util.Locale;
import java.util.Objects;

/**
 * Open, normalized protocol identifier. Third-party protocols can participate in the same
 * registry as the built-in wire protocols without changing an enum.
 */
public record ProtocolId(String value) {
    public static final ProtocolId OPENAI_COMPATIBLE = of("openai-compatible");
    public static final ProtocolId ANTHROPIC = of("anthropic");
    public static final ProtocolId GEMINI = of("gemini");
    public static final ProtocolId OLLAMA = of("ollama");
    public static final ProtocolId LLAMA_CPP = of("llama-cpp");

    public ProtocolId {
        value = normalize(value);
    }

    public static ProtocolId of(String raw) {
        return new ProtocolId(raw);
    }

    private static String normalize(String raw) {
        Objects.requireNonNull(raw, "protocol id");
        String normalized = raw.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_]+", "-")
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("protocol id must not be blank");
        }
        return normalized;
    }
}
