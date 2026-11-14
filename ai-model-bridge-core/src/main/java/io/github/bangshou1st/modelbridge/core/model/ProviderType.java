package io.github.bangshou1st.modelbridge.core.model;

import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;

import java.util.Locale;
import java.util.Optional;

/** Wire protocol family exposed by an upstream provider. */
public enum ProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
    OLLAMA,
    LLAMA_CPP;

    public ProtocolId toProtocolId() {
        return switch (this) {
            case OPENAI_COMPATIBLE -> ProtocolId.OPENAI_COMPATIBLE;
            case ANTHROPIC -> ProtocolId.ANTHROPIC;
            case GEMINI -> ProtocolId.GEMINI;
            case OLLAMA -> ProtocolId.OLLAMA;
            case LLAMA_CPP -> ProtocolId.LLAMA_CPP;
        };
    }

    public static Optional<ProviderType> fromProtocolId(ProtocolId protocolId) {
        if (protocolId == null) {
            return Optional.empty();
        }
        return switch (protocolId.value()) {
            case "openai-compatible" -> Optional.of(OPENAI_COMPATIBLE);
            case "anthropic" -> Optional.of(ANTHROPIC);
            case "gemini" -> Optional.of(GEMINI);
            case "ollama" -> Optional.of(OLLAMA);
            case "llama-cpp" -> Optional.of(LLAMA_CPP);
            default -> Optional.empty();
        };
    }

    public static ProviderType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("provider type is required");
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return valueOf(normalized);
    }
}
