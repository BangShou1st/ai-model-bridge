package io.github.bangshou1st.modelbridge.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Objects;

public record ToolDefinition(String name, String description, JsonNode inputSchema) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null
                ? JsonNodeFactory.instance.objectNode().put("type", "object").set("properties",
                        JsonNodeFactory.instance.objectNode())
                : inputSchema.deepCopy();
    }
}
