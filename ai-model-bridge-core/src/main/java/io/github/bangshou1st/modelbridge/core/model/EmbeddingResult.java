package io.github.bangshou1st.modelbridge.core.model;

import java.util.List;

public record EmbeddingResult(
        String model,
        String provider,
        List<List<Double>> embeddings,
        TokenUsage usage,
        long latencyMs
) {
    public EmbeddingResult {
        embeddings = embeddings == null ? List.of() : embeddings.stream().map(List::copyOf).toList();
        usage = usage == null ? TokenUsage.empty() : usage;
    }

    public static EmbeddingResult of(String model, String provider, List<List<Double>> embeddings, long latencyMs) {
        return new EmbeddingResult(model, provider, embeddings, TokenUsage.empty(), latencyMs);
    }
}
