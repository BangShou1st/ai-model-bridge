package io.github.bangshou1st.modelbridge.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway-only configuration. Upstream credentials are intentionally separate from the
 * library-wide starter properties.
 */
@ConfigurationProperties(prefix = "ai.bridge.gateway")
public class GatewayProperties {
    private boolean authEnabled = true;
    private List<String> apiKeys = new ArrayList<>();
    private Map<String, Upstream> upstreams = new LinkedHashMap<>();
    private Map<String, String> aliases = new LinkedHashMap<>();

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys == null ? new ArrayList<>() : apiKeys;
    }

    public Map<String, Upstream> getUpstreams() {
        return upstreams;
    }

    public void setUpstreams(Map<String, Upstream> upstreams) {
        this.upstreams = upstreams == null ? new LinkedHashMap<>() : upstreams;
    }

    public Map<String, String> getAliases() {
        return aliases;
    }

    public void setAliases(Map<String, String> aliases) {
        this.aliases = aliases == null ? new LinkedHashMap<>() : aliases;
    }

    public static class Upstream {
        private String type = "openai-compatible";
        private String baseUrl;
        private String apiKey = "";
        private Map<String, String> headers = new LinkedHashMap<>();
        private Map<String, Object> options = new LinkedHashMap<>();
        private List<Model> models = new ArrayList<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }

        public Map<String, Object> getOptions() {
            return options;
        }

        public void setOptions(Map<String, Object> options) {
            this.options = options == null ? new LinkedHashMap<>() : options;
        }

        public List<Model> getModels() {
            return models;
        }

        public void setModels(List<Model> models) {
            this.models = models == null ? new ArrayList<>() : models;
        }
    }

    public static class Model {
        private String id;
        private String alias;
        private String displayName;
        private Integer contextWindow;
        private Integer maxInputTokens;
        private Integer maxOutputTokens;
        private boolean supportsTools;
        private boolean supportsReasoning;
        private boolean supportsStreaming = true;
        private boolean supportsStructuredOutput = true;
        private List<String> reasoningLevels = new ArrayList<>();
        private List<String> supportedParameters = new ArrayList<>();
        private Map<String, BigDecimal> pricing = new LinkedHashMap<>();
        private List<String> nativeEndpoints = new ArrayList<>();
        private List<String> inputModalities = new ArrayList<>(List.of("text"));
        private List<String> outputModalities = new ArrayList<>(List.of("text"));

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Integer getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(Integer contextWindow) {
            this.contextWindow = contextWindow;
        }

        public Integer getMaxInputTokens() {
            return maxInputTokens;
        }

        public void setMaxInputTokens(Integer maxInputTokens) {
            this.maxInputTokens = maxInputTokens;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public boolean isSupportsTools() {
            return supportsTools;
        }

        public void setSupportsTools(boolean supportsTools) {
            this.supportsTools = supportsTools;
        }

        public boolean isSupportsReasoning() {
            return supportsReasoning;
        }

        public void setSupportsReasoning(boolean supportsReasoning) {
            this.supportsReasoning = supportsReasoning;
        }

        public boolean isSupportsStreaming() {
            return supportsStreaming;
        }

        public void setSupportsStreaming(boolean supportsStreaming) {
            this.supportsStreaming = supportsStreaming;
        }

        public boolean isSupportsStructuredOutput() {
            return supportsStructuredOutput;
        }

        public void setSupportsStructuredOutput(boolean supportsStructuredOutput) {
            this.supportsStructuredOutput = supportsStructuredOutput;
        }

        public List<String> getReasoningLevels() {
            return reasoningLevels;
        }

        public void setReasoningLevels(List<String> reasoningLevels) {
            this.reasoningLevels = reasoningLevels == null ? new ArrayList<>() : reasoningLevels;
        }

        public List<String> getSupportedParameters() {
            return supportedParameters;
        }

        public void setSupportedParameters(List<String> supportedParameters) {
            this.supportedParameters = supportedParameters == null ? new ArrayList<>() : supportedParameters;
        }

        public Map<String, BigDecimal> getPricing() {
            return pricing;
        }

        public void setPricing(Map<String, BigDecimal> pricing) {
            this.pricing = pricing == null ? new LinkedHashMap<>() : pricing;
        }

        public List<String> getNativeEndpoints() {
            return nativeEndpoints;
        }

        public void setNativeEndpoints(List<String> nativeEndpoints) {
            this.nativeEndpoints = nativeEndpoints == null ? new ArrayList<>() : nativeEndpoints;
        }

        public List<String> getInputModalities() {
            return inputModalities;
        }

        public void setInputModalities(List<String> inputModalities) {
            this.inputModalities = inputModalities == null ? new ArrayList<>() : inputModalities;
        }

        public List<String> getOutputModalities() {
            return outputModalities;
        }

        public void setOutputModalities(List<String> outputModalities) {
            this.outputModalities = outputModalities == null ? new ArrayList<>() : outputModalities;
        }
    }
}
