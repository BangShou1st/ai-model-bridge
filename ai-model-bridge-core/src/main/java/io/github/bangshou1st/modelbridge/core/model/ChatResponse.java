package io.github.bangshou1st.modelbridge.core.model;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        TokenUsage usage,
        String provider,
        String model,
        long latencyMs,
        Map<String, Object> metadata
) {
    public ChatResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
        usage = usage == null ? TokenUsage.empty() : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
