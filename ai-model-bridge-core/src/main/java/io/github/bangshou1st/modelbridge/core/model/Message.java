package io.github.bangshou1st.modelbridge.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral message. System messages are kept in the list so callers can preserve the
 * original conversation; adapters place/merge them according to their wire protocol.
 */
public record Message(
        Role role,
        String content,
        String name,
        String toolCallId,
        List<ToolCall> toolCalls,
        JsonNode toolResult
) {
    public Message {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResult = toolResult == null ? null : toolResult.deepCopy();
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, List.of(), null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, List.of(), null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, List.of(), null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, null, null, toolCalls, null);
    }

    public static Message tool(String toolCallId, String name, String content) {
        return new Message(Role.TOOL, content, name, toolCallId, List.of(), null);
    }

    public static Message tool(String toolCallId, String name, JsonNode result) {
        return new Message(Role.TOOL, result == null ? "" : result.toString(), name, toolCallId, List.of(), result);
    }
}
