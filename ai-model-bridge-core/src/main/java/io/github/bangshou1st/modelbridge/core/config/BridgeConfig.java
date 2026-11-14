package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.security.NetworkPolicyMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Framework-level declaration shared by plain Java constructors and Spring Boot binding. */
public class BridgeConfig {
    private boolean enabled = true;
    private String defaultChatModel;
    private String defaultEmbeddingModel;
    private String defaultRerankModel;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofMinutes(2);
    private ChatDefaults defaults = new ChatDefaults();
    private RetryDefinition retry = new RetryDefinition();
    private ProxyDefinition proxy = new ProxyDefinition();
    private Security security = new Security();
    private Map<String, ProviderDefinition> providers = new LinkedHashMap<>();
    private Map<String, List<String>> aliases = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { this.enabled = value; }
    public String getDefaultChatModel() { return defaultChatModel; }
    public void setDefaultChatModel(String value) { this.defaultChatModel = value; }
    public String getDefaultEmbeddingModel() { return defaultEmbeddingModel; }
    public void setDefaultEmbeddingModel(String value) { this.defaultEmbeddingModel = value; }
    public String getDefaultRerankModel() { return defaultRerankModel; }
    public void setDefaultRerankModel(String value) { this.defaultRerankModel = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
    public ChatDefaults getDefaults() { return defaults; }
    public void setDefaults(ChatDefaults value) {
        this.defaults = value == null ? new ChatDefaults() : value;
    }
    public RetryDefinition getRetry() { return retry; }
    public void setRetry(RetryDefinition value) {
        this.retry = value == null ? new RetryDefinition() : value;
    }
    public ProxyDefinition getProxy() { return proxy; }
    public void setProxy(ProxyDefinition value) {
        this.proxy = value == null ? new ProxyDefinition() : value;
    }
    public Security getSecurity() { return security; }
    public void setSecurity(Security value) {
        this.security = value == null ? new Security() : value;
    }
    public Map<String, ProviderDefinition> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderDefinition> value) {
        this.providers = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
    public Map<String, List<String>> getAliases() { return aliases; }
    public void setAliases(Map<String, List<String>> value) {
        this.aliases = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    public static class Security {
        private NetworkPolicyMode mode = NetworkPolicyMode.PUBLIC_HTTPS_ONLY;
        private boolean allowLoopback;
        private boolean allowLoopbackInProduction;
        private boolean allowPrivateNetwork;
        private Set<String> loopbackHosts = new LinkedHashSet<>(
                Set.of("127.0.0.1", "::1", "localhost"));
        private Set<Integer> loopbackPorts = new LinkedHashSet<>();

        public NetworkPolicyMode getMode() { return mode; }
        public void setMode(NetworkPolicyMode value) {
            this.mode = value == null ? NetworkPolicyMode.PUBLIC_HTTPS_ONLY : value;
        }
        public boolean isAllowLoopback() { return allowLoopback; }
        public void setAllowLoopback(boolean value) { this.allowLoopback = value; }
        public boolean isAllowLoopbackInProduction() { return allowLoopbackInProduction; }
        public void setAllowLoopbackInProduction(boolean value) {
            this.allowLoopbackInProduction = value;
        }
        public boolean isAllowPrivateNetwork() { return allowPrivateNetwork; }
        public void setAllowPrivateNetwork(boolean value) { this.allowPrivateNetwork = value; }
        public Set<String> getLoopbackHosts() { return loopbackHosts; }
        public void setLoopbackHosts(Set<String> value) {
            this.loopbackHosts = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
        }
        public Set<Integer> getLoopbackPorts() { return loopbackPorts; }
        public void setLoopbackPorts(Set<Integer> value) {
            this.loopbackPorts = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
        }
        public NetworkPolicyMode effectiveMode() {
            if (allowPrivateNetwork || mode == NetworkPolicyMode.ALLOW_PRIVATE_NETWORK) {
                return NetworkPolicyMode.ALLOW_PRIVATE_NETWORK;
            }
            if (allowLoopback || mode == NetworkPolicyMode.ALLOW_LOOPBACK) {
                return NetworkPolicyMode.ALLOW_LOOPBACK;
            }
            return mode;
        }

        public Security copy() {
            Security copy = new Security();
            copy.mode = mode;
            copy.allowLoopback = allowLoopback;
            copy.allowLoopbackInProduction = allowLoopbackInProduction;
            copy.allowPrivateNetwork = allowPrivateNetwork;
            copy.loopbackHosts = new LinkedHashSet<>(loopbackHosts);
            copy.loopbackPorts = new LinkedHashSet<>(loopbackPorts);
            return copy;
        }
    }

    public List<String> aliasTargets(String alias) {
        return aliases.getOrDefault(alias, List.of());
    }

    public void addAliasTarget(String alias, String target) {
        aliases.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(target);
    }

    public BridgeConfig copy() {
        BridgeConfig copy = new BridgeConfig();
        copy.enabled = enabled;
        copy.defaultChatModel = defaultChatModel;
        copy.defaultEmbeddingModel = defaultEmbeddingModel;
        copy.defaultRerankModel = defaultRerankModel;
        copy.connectTimeout = connectTimeout;
        copy.requestTimeout = requestTimeout;
        copy.defaults = defaults.copy();
        copy.retry = retry.copy();
        copy.proxy = proxy.copy();
        copy.security = security.copy();
        copy.providers = providers.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().copy(), (left, right) -> left,
                        LinkedHashMap::new));
        copy.aliases = aliases.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> new ArrayList<>(entry.getValue()), (left, right) -> left,
                        LinkedHashMap::new));
        return copy;
    }
}
