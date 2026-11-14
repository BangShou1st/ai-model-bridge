package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.model.ChatOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable configuration layer that resolves into an immutable {@link ChatOptions}. */
public class ChatDefaults {
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;
    private List<String> stop = new ArrayList<>();
    private Double frequencyPenalty;
    private Double presencePenalty;
    private Long seed;
    private Boolean failOnLength;
    private Boolean includeStreamUsage;
    private Boolean strictJsonSchema;
    private Map<String, Object> extraBody = new LinkedHashMap<>();
    private RetryDefinition retry = new RetryDefinition();
    private ProxyDefinition proxy = new ProxyDefinition();

    public ChatOptions toChatOptions() {
        return new ChatOptions(temperature, topP, maxOutputTokens,
                stop == null || stop.isEmpty() ? null : List.copyOf(stop),
                frequencyPenalty, presencePenalty, seed, failOnLength, includeStreamUsage,
                strictJsonSchema, extraBody);
    }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double value) { this.temperature = value; }
    public Double getTopP() { return topP; }
    public void setTopP(Double value) { this.topP = value; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer value) { this.maxOutputTokens = value; }
    public List<String> getStop() { return stop; }
    public void setStop(List<String> value) {
        this.stop = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(Double value) { this.frequencyPenalty = value; }
    public Double getPresencePenalty() { return presencePenalty; }
    public void setPresencePenalty(Double value) { this.presencePenalty = value; }
    public Long getSeed() { return seed; }
    public void setSeed(Long value) { this.seed = value; }
    public Boolean getFailOnLength() { return failOnLength; }
    public void setFailOnLength(Boolean value) { this.failOnLength = value; }
    public Boolean getIncludeStreamUsage() { return includeStreamUsage; }
    public void setIncludeStreamUsage(Boolean value) { this.includeStreamUsage = value; }
    public Boolean getStrictJsonSchema() { return strictJsonSchema; }
    public void setStrictJsonSchema(Boolean value) { this.strictJsonSchema = value; }
    public Map<String, Object> getExtraBody() { return extraBody; }
    public void setExtraBody(Map<String, Object> value) {
        this.extraBody = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public RetryDefinition getRetry() { return retry; }
    public void setRetry(RetryDefinition value) {
        this.retry = value == null ? new RetryDefinition() : value;
    }
    public ProxyDefinition getProxy() { return proxy; }
    public void setProxy(ProxyDefinition value) {
        this.proxy = value == null ? new ProxyDefinition() : value;
    }

    public ChatDefaults copy() {
        ChatDefaults copy = new ChatDefaults();
        copy.temperature = temperature;
        copy.topP = topP;
        copy.maxOutputTokens = maxOutputTokens;
        copy.stop = stop == null ? new ArrayList<>() : new ArrayList<>(stop);
        copy.frequencyPenalty = frequencyPenalty;
        copy.presencePenalty = presencePenalty;
        copy.seed = seed;
        copy.failOnLength = failOnLength;
        copy.includeStreamUsage = includeStreamUsage;
        copy.strictJsonSchema = strictJsonSchema;
        copy.extraBody = new LinkedHashMap<>(extraBody);
        copy.retry = retry.copy();
        copy.proxy = proxy.copy();
        return copy;
    }
}
