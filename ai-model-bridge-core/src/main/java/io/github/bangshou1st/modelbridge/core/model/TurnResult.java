package io.github.bangshou1st.modelbridge.core.model;

import java.util.List;
import java.util.Map;

public record TurnResult(
        String content,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        TokenUsage usage,
        String provider,
        String model,
        long latencyMs
) {
    public TurnResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
        usage = usage == null ? TokenUsage.empty() : usage;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public ChatResponse asChatResponse() {
        return new ChatResponse(content, null, toolCalls, finishReason, usage, provider, model, latencyMs, Map.of());
    }
}
