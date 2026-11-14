package io.github.bangshou1st.modelbridge.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral message. The plain-text {@link #content()} accessor remains compatible with
 * callers written against the original string-only contract.
 */
public record Message(
        Role role,
        String content,
        String name,
        String toolCallId,
        List<ToolCall> toolCalls,
        JsonNode toolResult,
        List<ContentPart> parts
) {
    public Message {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResult = toolResult == null ? null : toolResult.deepCopy();
        if (parts == null) {
            parts = content == null || content.isEmpty()
                    ? List.of() : List.of(new ContentPart.Text(content));
        } else {
            parts = List.copyOf(parts);
        }
        content = aggregateText(parts);
    }

    public Message(Role role, String content, String name, String toolCallId,
                   List<ToolCall> toolCalls, JsonNode toolResult) {
        this(role, content, name, toolCallId, toolCalls, toolResult, null);
    }

    @Override
    public String content() {
        return aggregateText(parts);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, List.of(), null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, List.of(), null);
    }

    public static Message user(List<ContentPart> parts) {
        return new Message(Role.USER, null, null, null, List.of(), null, parts);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, List.of(), null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, null, null, toolCalls, null);
    }

    public static Message assistant(List<ContentPart> parts, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, null, null, null, toolCalls, null, parts);
    }

    public static Message tool(String toolCallId, String name, String content) {
        return new Message(Role.TOOL, content, name, toolCallId, List.of(), null);
    }

    public static Message tool(String toolCallId, String name, JsonNode result) {
        return new Message(Role.TOOL, result == null ? "" : result.toString(), name, toolCallId,
                List.of(), result);
    }

    public boolean hasParts() {
        return !parts.isEmpty();
    }

    public Message withParts(List<ContentPart> newParts) {
        return new Message(role, null, name, toolCallId, toolCalls, toolResult, newParts);
    }

    private static String aggregateText(List<ContentPart> values) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : values) {
            if (part instanceof ContentPart.Text value) {
                text.append(value.text());
            }
        }
        return text.toString();
    }

    public static List<ContentPart> textParts(String content) {
        return content == null || content.isEmpty()
                ? List.of() : new ArrayList<>(List.of(new ContentPart.Text(content)));
    }
}
