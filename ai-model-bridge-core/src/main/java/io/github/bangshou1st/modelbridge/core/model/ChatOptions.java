package io.github.bangshou1st.modelbridge.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral sampling and framework controls. A {@code null} field means "not specified"
 * so the bridge can inherit it from global, provider, model and request layers in that order.
 * Boolean switches expose resolved defaults through their normal accessors.
 */
public final class ChatOptions {
    private static final Set<String> RESERVED_EXTRA_BODY_KEYS = Set.of(
            "model", "messages", "stream", "stream_options", "tools", "tool_choice",
            "response_format", "functions", "function_call", "input", "contents",
            "system", "system_instruction", "systeminstruction", "max_tokens",
            "max_completion_tokens", "max_output_tokens", "maxtokens",
            "maxcompletiontokens", "maxoutputtokens", "toolchoice", "responseformat"
    );

    private final Double temperature;
    private final Double topP;
    private final Integer maxOutputTokens;
    private final List<String> stop;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Long seed;
    private final Boolean failOnLength;
    private final Boolean includeStreamUsage;
    private final Boolean strictJsonSchema;
    private final Map<String, Object> extraBody;

    public ChatOptions() {
        this(null, null, null, null, null, null, null, null, null, null, Map.of());
    }

    public ChatOptions(Double temperature, Double topP, Integer maxOutputTokens, List<String> stop,
                       Double frequencyPenalty, Double presencePenalty, Long seed,
                       Boolean failOnLength, Boolean includeStreamUsage,
                       Boolean strictJsonSchema, Map<String, Object> extraBody) {
        this.temperature = temperature;
        this.topP = topP;
        this.maxOutputTokens = maxOutputTokens;
        this.stop = stop == null ? null : List.copyOf(stop);
        this.frequencyPenalty = frequencyPenalty;
        this.presencePenalty = presencePenalty;
        this.seed = seed;
        this.failOnLength = failOnLength;
        this.includeStreamUsage = includeStreamUsage;
        this.strictJsonSchema = strictJsonSchema;
        this.extraBody = sanitizeExtraBody(extraBody);
    }

    public static ChatOptions defaults() {
        return new ChatOptions();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Double temperature() {
        return temperature;
    }

    public Double topP() {
        return topP;
    }

    public Integer maxOutputTokens() {
        return maxOutputTokens;
    }

    public List<String> stop() {
        return stop == null ? List.of() : stop;
    }

    public Double frequencyPenalty() {
        return frequencyPenalty;
    }

    public Double presencePenalty() {
        return presencePenalty;
    }

    public Long seed() {
        return seed;
    }

    public boolean failOnLength() {
        return Boolean.TRUE.equals(failOnLength);
    }

    public boolean includeStreamUsage() {
        return includeStreamUsage == null || includeStreamUsage;
    }

    public boolean strictJsonSchema() {
        return strictJsonSchema == null || strictJsonSchema;
    }

    public Map<String, Object> extraBody() {
        return extraBody;
    }

    public Boolean failOnLengthOverride() {
        return failOnLength;
    }

    public Boolean includeStreamUsageOverride() {
        return includeStreamUsage;
    }

    public Boolean strictJsonSchemaOverride() {
        return strictJsonSchema;
    }

    public ChatOptions mergeWith(ChatOptions override) {
        if (override == null) {
            return this;
        }
        List<String> mergedStop = override.stop != null ? override.stop : stop;
        Map<String, Object> mergedExtra = new LinkedHashMap<>(extraBody);
        mergedExtra.putAll(override.extraBody);
        return new ChatOptions(
                override.temperature != null ? override.temperature : temperature,
                override.topP != null ? override.topP : topP,
                override.maxOutputTokens != null ? override.maxOutputTokens : maxOutputTokens,
                mergedStop,
                override.frequencyPenalty != null ? override.frequencyPenalty : frequencyPenalty,
                override.presencePenalty != null ? override.presencePenalty : presencePenalty,
                override.seed != null ? override.seed : seed,
                override.failOnLength != null ? override.failOnLength : failOnLength,
                override.includeStreamUsage != null ? override.includeStreamUsage : includeStreamUsage,
                override.strictJsonSchema != null ? override.strictJsonSchema : strictJsonSchema,
                mergedExtra);
    }

    public ChatOptions withTemperature(Double value) {
        return toBuilder().temperature(value).build();
    }

    public ChatOptions withMaxOutputTokens(Integer value) {
        return toBuilder().maxOutputTokens(value).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private static Map<String, Object> sanitizeExtraBody(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                return;
            }
            String normalized = key.strip().toLowerCase();
            if (RESERVED_EXTRA_BODY_KEYS.contains(normalized)) {
                throw new IllegalArgumentException("extraBody must not override core field: " + key);
            }
            safe.put(key, value);
        });
        return Map.copyOf(safe);
    }

    public static final class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxOutputTokens;
        private List<String> stop;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Long seed;
        private Boolean failOnLength;
        private Boolean includeStreamUsage;
        private Boolean strictJsonSchema;
        private final Map<String, Object> extraBody = new LinkedHashMap<>();

        public Builder() {
        }

        private Builder(ChatOptions source) {
            this.temperature = source.temperature;
            this.topP = source.topP;
            this.maxOutputTokens = source.maxOutputTokens;
            this.stop = source.stop;
            this.frequencyPenalty = source.frequencyPenalty;
            this.presencePenalty = source.presencePenalty;
            this.seed = source.seed;
            this.failOnLength = source.failOnLength;
            this.includeStreamUsage = source.includeStreamUsage;
            this.strictJsonSchema = source.strictJsonSchema;
            this.extraBody.putAll(source.extraBody);
        }

        public Builder temperature(Double value) { this.temperature = value; return this; }
        public Builder topP(Double value) { this.topP = value; return this; }
        public Builder maxOutputTokens(Integer value) { this.maxOutputTokens = value; return this; }
        public Builder stop(List<String> values) { this.stop = values; return this; }
        public Builder stop(String... values) {
            this.stop = values == null ? List.of() : new ArrayList<>(List.of(values));
            return this;
        }
        public Builder frequencyPenalty(Double value) { this.frequencyPenalty = value; return this; }
        public Builder presencePenalty(Double value) { this.presencePenalty = value; return this; }
        public Builder seed(Long value) { this.seed = value; return this; }
        public Builder failOnLength(boolean value) { this.failOnLength = value; return this; }
        public Builder includeStreamUsage(boolean value) { this.includeStreamUsage = value; return this; }
        public Builder strictJsonSchema(boolean value) { this.strictJsonSchema = value; return this; }
        public Builder extraBody(Map<String, Object> values) {
            this.extraBody.clear();
            if (values != null) {
                this.extraBody.putAll(values);
            }
            return this;
        }
        public Builder extraBody(String key, Object value) {
            this.extraBody.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public ChatOptions build() {
            return new ChatOptions(temperature, topP, maxOutputTokens, stop, frequencyPenalty,
                    presencePenalty, seed, failOnLength, includeStreamUsage, strictJsonSchema,
                    extraBody);
        }
    }
}
