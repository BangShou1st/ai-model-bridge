package io.github.bangshou1st.modelbridge.core.model;

import java.util.ArrayList;
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
        Map<String, Object> metadata,
        List<ContentPart> parts
) {
    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
        usage = usage == null ? TokenUsage.empty() : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (parts == null) {
            List<ContentPart> values = new ArrayList<>();
            if (content != null && !content.isEmpty()) {
                values.add(new ContentPart.Text(content));
            }
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                values.add(new ContentPart.Thinking(reasoningContent, null));
            }
            parts = List.copyOf(values);
        } else {
            parts = List.copyOf(parts);
        }
        content = text(parts);
        reasoningContent = thinking(parts);
    }

    public ChatResponse(String content, String reasoningContent, List<ToolCall> toolCalls,
                        FinishReason finishReason, TokenUsage usage, String provider, String model,
                        long latencyMs, Map<String, Object> metadata) {
        this(content, reasoningContent, toolCalls, finishReason, usage, provider, model,
                latencyMs, metadata, null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public TurnResult toTurnResult() {
        return new TurnResult(content, toolCalls, finishReason, usage, provider, model, latencyMs);
    }

    private static String text(List<ContentPart> values) {
        StringBuilder result = new StringBuilder();
        for (ContentPart part : values) {
            if (part instanceof ContentPart.Text value) {
                result.append(value.text());
            }
        }
        return result.toString();
    }

    private static String thinking(List<ContentPart> values) {
        StringBuilder result = new StringBuilder();
        for (ContentPart part : values) {
            if (part instanceof ContentPart.Thinking value) {
                result.append(value.text());
            }
        }
        return result.isEmpty() ? null : result.toString();
    }
}
