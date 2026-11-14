package io.github.bangshou1st.modelbridge.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Objects;

public record ToolCall(String id, String name, JsonNode arguments, String thoughtSignature) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? JsonNodeFactory.instance.objectNode() : arguments.deepCopy();
    }

    public ToolCall(String id, String name, JsonNode arguments) {
        this(id, name, arguments, null);
    }
}
