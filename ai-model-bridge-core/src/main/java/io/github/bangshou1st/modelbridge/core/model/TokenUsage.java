package io.github.bangshou1st.modelbridge.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Integer reasoningTokens,
        Integer cachedInputTokens
) {
    public TokenUsage {
        if (totalTokens == null && inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        }
    }

    public TokenUsage(Integer inputTokens, Integer outputTokens) {
        this(inputTokens, outputTokens, null, null, null);
    }

    public static TokenUsage empty() {
        return new TokenUsage(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return inputTokens == null && outputTokens == null && totalTokens == null;
    }
}
