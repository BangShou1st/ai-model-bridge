package io.github.bangshou1st.modelbridge.core.model;

import java.util.Comparator;
import java.util.List;

public record RerankResult(
        String model,
        String provider,
        List<RerankMatch> matches,
        TokenUsage usage,
        long latencyMs
) {
    public RerankResult {
        matches = matches == null ? List.of() : List.copyOf(matches);
        usage = usage == null ? TokenUsage.empty() : usage;
    }

    public RerankResult topN(int n) {
        return new RerankResult(model, provider,
                matches.stream().sorted(Comparator.comparingDouble(RerankMatch::score).reversed()).limit(n).toList(),
                usage, latencyMs);
    }
}
