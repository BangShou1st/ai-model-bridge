package io.github.bangshou1st.modelbridge.core.model;

import java.util.Map;

public record StreamChunk(
        Type type,
        String delta,
        ToolCallDelta toolCall,
        FinishReason finishReason,
        TokenUsage usage,
        String model,
        String provider,
        Map<String, Object> metadata
) {
    public enum Type {
        CONTENT,
        REASONING,
        TOOL_CALL_DELTA,
        USAGE,
        DONE
    }

    public StreamChunk {
        delta = delta == null ? "" : delta;
        usage = usage == null ? TokenUsage.empty() : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static StreamChunk content(String delta, String model, String provider) {
        return new StreamChunk(Type.CONTENT, delta, null, null, TokenUsage.empty(), model, provider, Map.of());
    }

    public static StreamChunk reasoning(String delta, String model, String provider) {
        return new StreamChunk(Type.REASONING, delta, null, null, TokenUsage.empty(), model, provider, Map.of());
    }

    public static StreamChunk toolCall(ToolCallDelta delta, String model, String provider) {
        return new StreamChunk(Type.TOOL_CALL_DELTA, "", delta, null, TokenUsage.empty(), model, provider, Map.of());
    }

    public static StreamChunk done(FinishReason finishReason, TokenUsage usage, String model, String provider) {
        return new StreamChunk(Type.DONE, "", null, finishReason, usage, model, provider, Map.of());
    }
}
