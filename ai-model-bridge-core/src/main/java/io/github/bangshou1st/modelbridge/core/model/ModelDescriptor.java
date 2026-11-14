package io.github.bangshou1st.modelbridge.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rich, OpenAI-compatible model metadata. Unknown upstream fields are preserved in
 * {@link #extra}; known fields are normalized for consumers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelDescriptor(
        String id,
        @JsonProperty("display_name")
        String displayName,
        @JsonProperty("owned_by")
        String ownedBy,
        String provider,
        @JsonProperty("context_window")
        Integer contextWindow,
        @JsonProperty("max_input_tokens")
        Integer maxInputTokens,
        @JsonProperty("max_output_tokens")
        Integer maxOutputTokens,
        @JsonProperty("supports_tools")
        boolean supportsTools,
        @JsonProperty("supports_reasoning")
        boolean supportsReasoning,
        @JsonProperty("supports_streaming")
        boolean supportsStreaming,
        @JsonProperty("supports_structured_output")
        boolean supportsStructuredOutput,
        @JsonProperty("supports_embeddings")
        boolean supportsEmbeddings,
        @JsonProperty("supports_rerank")
        boolean supportsRerank,
        @JsonProperty("reasoning_levels")
        List<String> reasoningLevels,
        @JsonProperty("supported_parameters")
        List<String> supportedParameters,
        @JsonProperty("pricing")
        @JsonSerialize(contentUsing = ToStringSerializer.class)
        Map<String, BigDecimal> pricing,
        @JsonProperty("input_modalities")
        List<String> inputModalities,
        @JsonProperty("output_modalities")
        List<String> outputModalities,
        @JsonProperty("native_endpoints")
        List<String> nativeEndpoints,
        @JsonProperty("created_at")
        Instant createdAt,
        Map<String, Object> extra
) {
    public ModelDescriptor {
        reasoningLevels = reasoningLevels == null ? List.of() : List.copyOf(reasoningLevels);
        supportedParameters = supportedParameters == null ? List.of() : List.copyOf(supportedParameters);
        pricing = pricing == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(pricing));
        inputModalities = inputModalities == null ? List.of("text") : List.copyOf(inputModalities);
        outputModalities = outputModalities == null ? List.of("text") : List.copyOf(outputModalities);
        nativeEndpoints = nativeEndpoints == null || nativeEndpoints.isEmpty()
                ? inferredEndpoints(supportsStreaming, supportsStructuredOutput,
                        supportsEmbeddings, supportsRerank)
                : List.copyOf(nativeEndpoints);
        extra = extra == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(extra));
    }

    public ModelDescriptor(String id, String displayName, String ownedBy, String provider,
                           Integer contextWindow, Integer maxInputTokens, Integer maxOutputTokens,
                           boolean supportsTools, boolean supportsReasoning, boolean supportsStreaming,
                           boolean supportsStructuredOutput, boolean supportsEmbeddings,
                           List<String> reasoningLevels, List<String> supportedParameters,
                           Map<String, BigDecimal> pricing, List<String> inputModalities,
                           List<String> outputModalities, List<String> nativeEndpoints,
                           Instant createdAt, Map<String, Object> extra) {
        this(id, displayName, ownedBy, provider, contextWindow, maxInputTokens, maxOutputTokens,
                supportsTools, supportsReasoning, supportsStreaming, supportsStructuredOutput,
                supportsEmbeddings, false, reasoningLevels, supportedParameters, pricing,
                inputModalities, outputModalities, nativeEndpoints, createdAt, extra);
    }

    @JsonProperty("reasoning")
    public boolean reasoning() {
        return supportsReasoning;
    }

    @JsonProperty("tool_call")
    public boolean toolCall() {
        return supportsTools;
    }

    @JsonProperty("modalities")
    public Map<String, List<String>> modalities() {
        return Map.of("input", inputModalities, "output", outputModalities);
    }

    @JsonProperty("supported_reasoning_levels")
    public List<Map<String, String>> supportedReasoningLevels() {
        return reasoningLevels.stream().map(level -> Map.of("effort", level)).toList();
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String displayName;
        private String ownedBy;
        private String provider;
        private Integer contextWindow;
        private Integer maxInputTokens;
        private Integer maxOutputTokens;
        private boolean supportsTools;
        private boolean supportsReasoning;
        private boolean supportsStreaming = true;
        private boolean supportsStructuredOutput = true;
        private boolean supportsEmbeddings;
        private boolean supportsRerank;
        private List<String> reasoningLevels = List.of();
        private List<String> supportedParameters = List.of();
        private Map<String, BigDecimal> pricing = Map.of();
        private List<String> inputModalities = List.of("text");
        private List<String> outputModalities = List.of("text");
        private List<String> nativeEndpoints = List.of();
        private Instant createdAt;
        private Map<String, Object> extra = Map.of();

        private Builder(String id) {
            this.id = id;
        }

        public Builder displayName(String value) { this.displayName = value; return this; }
        public Builder ownedBy(String value) { this.ownedBy = value; return this; }
        public Builder provider(String value) { this.provider = value; return this; }
        public Builder contextWindow(Integer value) { this.contextWindow = value; return this; }
        public Builder maxInputTokens(Integer value) { this.maxInputTokens = value; return this; }
        public Builder maxOutputTokens(Integer value) { this.maxOutputTokens = value; return this; }
        public Builder supportsTools(boolean value) { this.supportsTools = value; return this; }
        public Builder supportsReasoning(boolean value) { this.supportsReasoning = value; return this; }
        public Builder supportsStreaming(boolean value) { this.supportsStreaming = value; return this; }
        public Builder supportsStructuredOutput(boolean value) { this.supportsStructuredOutput = value; return this; }
        public Builder supportsEmbeddings(boolean value) { this.supportsEmbeddings = value; return this; }
        public Builder supportsRerank(boolean value) { this.supportsRerank = value; return this; }
        public Builder reasoningLevels(List<String> value) { this.reasoningLevels = value; return this; }
        public Builder supportedParameters(List<String> value) { this.supportedParameters = value; return this; }
        public Builder pricing(Map<String, BigDecimal> value) { this.pricing = value; return this; }
        public Builder inputModalities(List<String> value) { this.inputModalities = value; return this; }
        public Builder outputModalities(List<String> value) { this.outputModalities = value; return this; }
        public Builder nativeEndpoints(List<String> value) { this.nativeEndpoints = value; return this; }
        public Builder createdAt(Instant value) { this.createdAt = value; return this; }
        public Builder extra(Map<String, Object> value) { this.extra = value; return this; }

        public ModelDescriptor build() {
            return new ModelDescriptor(id, displayName, ownedBy, provider, contextWindow, maxInputTokens,
                    maxOutputTokens, supportsTools, supportsReasoning, supportsStreaming,
                    supportsStructuredOutput, supportsEmbeddings, supportsRerank, reasoningLevels,
                    supportedParameters, pricing, inputModalities, outputModalities,
                    nativeEndpoints, createdAt, extra);
        }
    }

    private static List<String> inferredEndpoints(boolean supportsStreaming,
                                                  boolean supportsStructuredOutput,
                                                  boolean supportsEmbeddings,
                                                  boolean supportsRerank) {
        List<String> values = new java.util.ArrayList<>();
        if (supportsStreaming || supportsStructuredOutput) {
            values.add("/v1/chat/completions");
        }
        if (supportsEmbeddings) {
            values.add("/v1/embeddings");
        }
        if (supportsRerank) {
            values.add("/v1/rerank");
        }
        return List.copyOf(values);
    }
}
