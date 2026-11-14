package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelDefinition {
    private String id;
    private String alias;
    private String displayName;
    private String ownedBy;
    private Integer contextWindow;
    private Integer maxInputTokens;
    private Integer maxOutputTokens;
    private Boolean supportsTools;
    private Boolean supportsReasoning;
    private Boolean supportsStreaming;
    private Boolean supportsStructuredOutput;
    private Boolean supportsEmbeddings;
    private Boolean supportsRerank;
    private List<String> reasoningLevels = new ArrayList<>();
    private List<String> supportedParameters = new ArrayList<>();
    private Map<String, BigDecimal> pricing = new LinkedHashMap<>();
    private List<String> inputModalities = new ArrayList<>();
    private List<String> outputModalities = new ArrayList<>();
    private List<String> nativeEndpoints = new ArrayList<>();
    private Map<String, Object> extra = new LinkedHashMap<>();
    private ChatDefaults defaults = new ChatDefaults();

    public ModelDefinition() {
    }

    public ModelDefinition(String id) {
        this.id = id;
    }

    public ModelDefinition(String id, String alias) {
        this.id = id;
        this.alias = alias;
    }

    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
    public String getAlias() { return alias; }
    public void setAlias(String value) { this.alias = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { this.displayName = value; }
    public String getOwnedBy() { return ownedBy; }
    public void setOwnedBy(String value) { this.ownedBy = value; }
    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer value) { this.contextWindow = value; }
    public Integer getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(Integer value) { this.maxInputTokens = value; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer value) { this.maxOutputTokens = value; }
    public Boolean getSupportsTools() { return supportsTools; }
    public void setSupportsTools(Boolean value) { this.supportsTools = value; }
    public Boolean getSupportsReasoning() { return supportsReasoning; }
    public void setSupportsReasoning(Boolean value) { this.supportsReasoning = value; }
    public Boolean getSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(Boolean value) { this.supportsStreaming = value; }
    public Boolean getSupportsStructuredOutput() { return supportsStructuredOutput; }
    public void setSupportsStructuredOutput(Boolean value) { this.supportsStructuredOutput = value; }
    public Boolean getSupportsEmbeddings() { return supportsEmbeddings; }
    public void setSupportsEmbeddings(Boolean value) { this.supportsEmbeddings = value; }
    public Boolean getSupportsRerank() { return supportsRerank; }
    public void setSupportsRerank(Boolean value) { this.supportsRerank = value; }
    public List<String> getReasoningLevels() { return reasoningLevels; }
    public void setReasoningLevels(List<String> value) {
        this.reasoningLevels = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getSupportedParameters() { return supportedParameters; }
    public void setSupportedParameters(List<String> value) {
        this.supportedParameters = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public Map<String, BigDecimal> getPricing() { return pricing; }
    public void setPricing(Map<String, BigDecimal> value) {
        this.pricing = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public List<String> getInputModalities() { return inputModalities; }
    public void setInputModalities(List<String> value) {
        this.inputModalities = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getOutputModalities() { return outputModalities; }
    public void setOutputModalities(List<String> value) {
        this.outputModalities = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getNativeEndpoints() { return nativeEndpoints; }
    public void setNativeEndpoints(List<String> value) {
        this.nativeEndpoints = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> value) {
        this.extra = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public ChatDefaults getDefaults() { return defaults; }
    public void setDefaults(ChatDefaults value) {
        this.defaults = value == null ? new ChatDefaults() : value;
    }

    public ModelDescriptor toDescriptor(String providerId, ModelDescriptor discovered) {
        ModelDescriptor source = discovered == null
                ? ModelDescriptor.builder(id).build() : discovered;
        Map<String, BigDecimal> mergedPricing = new LinkedHashMap<>(source.pricing());
        mergedPricing.putAll(pricing);
        Map<String, Object> mergedExtra = new LinkedHashMap<>(source.extra());
        mergedExtra.putAll(extra);
        return ModelDescriptor.builder(id)
                .displayName(first(displayName, source.displayName(), id))
                .ownedBy(first(ownedBy, source.ownedBy(), providerId))
                .provider(providerId)
                .contextWindow(first(contextWindow, source.contextWindow()))
                .maxInputTokens(first(maxInputTokens, source.maxInputTokens()))
                .maxOutputTokens(first(maxOutputTokens, source.maxOutputTokens()))
                .supportsTools(bool(supportsTools, source.supportsTools()))
                .supportsReasoning(bool(supportsReasoning, source.supportsReasoning()))
                .supportsStreaming(bool(supportsStreaming, source.supportsStreaming()))
                .supportsStructuredOutput(bool(supportsStructuredOutput,
                        source.supportsStructuredOutput()))
                .supportsEmbeddings(bool(supportsEmbeddings, source.supportsEmbeddings()))
                .supportsRerank(bool(supportsRerank, source.supportsRerank()))
                .reasoningLevels(reasoningLevels.isEmpty()
                        ? source.reasoningLevels() : List.copyOf(reasoningLevels))
                .supportedParameters(supportedParameters.isEmpty()
                        ? source.supportedParameters() : List.copyOf(supportedParameters))
                .pricing(mergedPricing)
                .inputModalities(inputModalities.isEmpty()
                        ? source.inputModalities() : List.copyOf(inputModalities))
                .outputModalities(outputModalities.isEmpty()
                        ? source.outputModalities() : List.copyOf(outputModalities))
                .nativeEndpoints(nativeEndpoints.isEmpty()
                        ? (discovered == null ? List.of() : source.nativeEndpoints())
                        : List.copyOf(nativeEndpoints))
                .createdAt(source.createdAt())
                .extra(mergedExtra)
                .build();
    }

    private static boolean bool(Boolean configured, boolean fallback) {
        return configured == null ? fallback : configured;
    }

    private static <T> T first(T configured, T fallback) {
        return first(configured, fallback, null);
    }

    private static <T> T first(T configured, T fallback, T defaultValue) {
        return configured != null ? configured : fallback != null ? fallback : defaultValue;
    }

    public ModelDefinition copy() {
        ModelDefinition copy = new ModelDefinition(id);
        copy.alias = alias;
        copy.displayName = displayName;
        copy.ownedBy = ownedBy;
        copy.contextWindow = contextWindow;
        copy.maxInputTokens = maxInputTokens;
        copy.maxOutputTokens = maxOutputTokens;
        copy.supportsTools = supportsTools;
        copy.supportsReasoning = supportsReasoning;
        copy.supportsStreaming = supportsStreaming;
        copy.supportsStructuredOutput = supportsStructuredOutput;
        copy.supportsEmbeddings = supportsEmbeddings;
        copy.supportsRerank = supportsRerank;
        copy.reasoningLevels = new ArrayList<>(reasoningLevels);
        copy.supportedParameters = new ArrayList<>(supportedParameters);
        copy.pricing = new LinkedHashMap<>(pricing);
        copy.inputModalities = new ArrayList<>(inputModalities);
        copy.outputModalities = new ArrayList<>(outputModalities);
        copy.nativeEndpoints = new ArrayList<>(nativeEndpoints);
        copy.extra = new LinkedHashMap<>(extra);
        copy.defaults = defaults.copy();
        return copy;
    }
}
