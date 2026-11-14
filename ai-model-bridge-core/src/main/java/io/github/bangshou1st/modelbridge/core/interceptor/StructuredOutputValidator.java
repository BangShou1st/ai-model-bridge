package io.github.bangshou1st.modelbridge.core.interceptor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.model.StructuredOutput;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.Iterator;

/** Provider-neutral JSON/schema validation used by the structured-output interceptor. */
public final class StructuredOutputValidator {
    private final ObjectMapper mapper;

    public StructuredOutputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode validate(String content, StructuredOutput structured, String provider) {
        if (structured == null || !structured.enabled()) {
            return null;
        }
        JsonNode value;
        try {
            value = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(content);
        } catch (Exception e) {
            throw ModelBridgeException.schema("structured output is not valid JSON", provider);
        }
        if (value == null || value.isMissingNode() || value.isNull()) {
            throw ModelBridgeException.schema("structured output is empty", provider);
        }
        if ((structured.mode() == StructuredOutput.Mode.JSON_OBJECT
                || structured.mode() == StructuredOutput.Mode.PROMPT_JSON)
                && structured.schema() == null && !value.isObject()) {
            throw ModelBridgeException.schema("structured output is not a JSON object", provider);
        }
        if (structured.schema() != null) {
            validateSchema(value, structured.schema(), "$", provider);
        }
        return value;
    }

    public void validateSchema(JsonNode value, JsonNode schema, String path, String provider) {
        if (schema == null || schema.isMissingNode() || schema.isNull()
                || schema.isBoolean() && schema.asBoolean()) {
            return;
        }
        if (schema.isBoolean() && !schema.asBoolean()) {
            throw fail("structured output is rejected by schema at " + path, provider);
        }
        String type = schema.path("type").asText("");
        if (!type.isEmpty() && !typeMatches(value, type)) {
            throw fail("structured output violates schema at " + path, provider);
        }
        JsonNode enumValues = schema.path("enum");
        if (enumValues.isArray() && !enumValues.isEmpty()) {
            boolean matched = false;
            for (JsonNode allowed : enumValues) {
                if (allowed.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw fail("structured output violates enum at " + path, provider);
            }
        }
        if (value.isObject()) {
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode field : required) {
                    if (!value.has(field.asText())) {
                        throw fail("structured output is missing required field " + path + "."
                                + field.asText(), provider);
                    }
                }
            }
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                Iterator<String> names = properties.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    JsonNode child = value.get(name);
                    if (child != null) {
                        validateSchema(child, properties.path(name), path + "." + name, provider);
                    }
                }
            }
            if (schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()
                    && properties.isObject()) {
                Iterator<String> fields = value.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!properties.has(field)) {
                        throw fail("structured output has an unexpected field " + path + "."
                                + field, provider);
                    }
                }
            }
        }
        if (value.isArray() && schema.path("items").isObject()) {
            for (int index = 0; index < value.size(); index++) {
                validateSchema(value.get(index), schema.path("items"),
                        path + "[" + index + "]", provider);
            }
        }
    }

    private static boolean typeMatches(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static ModelBridgeException fail(String message, String provider) {
        return ModelBridgeException.schema(message, provider);
    }
}
