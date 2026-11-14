package io.github.bangshou1st.modelbridge.core.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Structured-output request. AUTO asks for the provider's structured mode when possible and
 * permits a single PROMPT_JSON fallback if the upstream rejects or violates it.
 */
public record StructuredOutput(Mode mode, String name, JsonNode schema, boolean fallbackToPromptJson) {
    public enum Mode {
        NONE,
        JSON_OBJECT,
        JSON_SCHEMA,
        PROMPT_JSON
    }

    public StructuredOutput {
        mode = mode == null ? Mode.NONE : mode;
        schema = schema == null ? null : schema.deepCopy();
        if (mode == Mode.JSON_SCHEMA && schema == null) {
            throw new IllegalArgumentException("JSON_SCHEMA requires a schema");
        }
        if (mode == Mode.PROMPT_JSON && schema == null) {
            throw new IllegalArgumentException("PROMPT_JSON requires a schema or object instruction");
        }
    }

    public static StructuredOutput none() {
        return new StructuredOutput(Mode.NONE, null, null, false);
    }

    public static StructuredOutput jsonObject(String name, boolean fallbackToPromptJson) {
        return new StructuredOutput(Mode.JSON_OBJECT, name, null, fallbackToPromptJson);
    }

    public static StructuredOutput jsonSchema(String name, JsonNode schema, boolean fallbackToPromptJson) {
        return new StructuredOutput(Mode.JSON_SCHEMA, name, schema, fallbackToPromptJson);
    }

    public static StructuredOutput promptJson(String name, JsonNode schema) {
        return new StructuredOutput(Mode.PROMPT_JSON, name, schema, false);
    }

    public boolean enabled() {
        return mode != Mode.NONE;
    }
}
