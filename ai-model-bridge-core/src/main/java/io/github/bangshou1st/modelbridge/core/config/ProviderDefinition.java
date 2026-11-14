package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.model.EndpointConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProxyConfig;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProviderDefinition {
    private String protocol = "openai-compatible";
    private String baseUrl;
    private String apiKey = "";
    private Map<String, String> headers = new LinkedHashMap<>();
    private Map<String, Object> options = new LinkedHashMap<>();
    private Duration connectTimeout;
    private Duration requestTimeout;
    private boolean discoverModels;
    private EndpointConfig endpoints = EndpointConfig.empty();
    private ProxyDefinition proxy = new ProxyDefinition();
    private ChatDefaults defaults = new ChatDefaults();
    private List<ModelDefinition> models = new ArrayList<>();

    public String getProtocol() { return protocol; }
    public void setProtocol(String value) {
        this.protocol = value == null || value.isBlank() ? "openai-compatible" : value;
    }
    public ProtocolId getProtocolId() { return ProtocolId.of(protocol); }
    public void setProtocolId(ProtocolId value) {
        this.protocol = value == null ? "openai-compatible" : value.value();
    }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { this.baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { this.apiKey = value == null ? "" : value; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> value) {
        this.headers = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> value) {
        this.options = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
    public boolean isDiscoverModels() { return discoverModels; }
    public void setDiscoverModels(boolean value) { this.discoverModels = value; }
    public EndpointConfig getEndpoints() { return endpoints; }
    public void setEndpoints(EndpointConfig value) {
        this.endpoints = value == null ? EndpointConfig.empty() : value;
    }
    public EndpointConfig getEndpointConfig() { return endpoints; }
    public ProxyDefinition getProxy() { return proxy; }
    public void setProxy(ProxyDefinition value) {
        this.proxy = value == null ? new ProxyDefinition() : value;
    }
    public ChatDefaults getDefaults() { return defaults; }
    public void setDefaults(ChatDefaults value) {
        this.defaults = value == null ? new ChatDefaults() : value;
    }
    public List<ModelDefinition> getModels() { return models; }
    public void setModels(List<ModelDefinition> value) {
        this.models = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public ProviderConfig toProviderConfig(String id, Duration globalConnectTimeout,
                                           Duration globalRequestTimeout, ProxyConfig globalProxy) {
        ProxyConfig proxyConfig = proxy != null && proxy.isEnabled()
                ? proxy.toProxyConfig() : globalProxy;
        return new ProviderConfig(id, getProtocolId(), baseUrl, apiKey, headers,
                connectTimeout == null ? globalConnectTimeout : connectTimeout,
                requestTimeout == null ? globalRequestTimeout : requestTimeout,
                options, endpoints, proxyConfig);
    }

    public ProviderDefinition copy() {
        ProviderDefinition copy = new ProviderDefinition();
        copy.protocol = protocol;
        copy.baseUrl = baseUrl;
        copy.apiKey = apiKey;
        copy.headers = new LinkedHashMap<>(headers);
        copy.options = new LinkedHashMap<>(options);
        copy.connectTimeout = connectTimeout;
        copy.requestTimeout = requestTimeout;
        copy.discoverModels = discoverModels;
        copy.endpoints = endpoints;
        copy.proxy = proxy.copy();
        copy.defaults = defaults.copy();
        copy.models = models.stream().map(ModelDefinition::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return copy;
    }
}
