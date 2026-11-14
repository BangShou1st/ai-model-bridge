package io.github.bangshou1st.modelbridge.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Library-only configuration. Credentials and provider routes belong to the application, never to
 * this starter's global properties.
 */
@ConfigurationProperties(prefix = "ai.bridge")
public class ModelBridgeProperties {
    private boolean enabled = true;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofMinutes(2);
    private final Security security = new Security();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Security {
        private boolean allowLoopback;
        private Set<String> loopbackHosts = new LinkedHashSet<>(
                Set.of("127.0.0.1", "::1", "localhost"));
        private Set<Integer> loopbackPorts = new LinkedHashSet<>();

        public boolean isAllowLoopback() {
            return allowLoopback;
        }

        public void setAllowLoopback(boolean allowLoopback) {
            this.allowLoopback = allowLoopback;
        }

        public Set<String> getLoopbackHosts() {
            return loopbackHosts;
        }

        public void setLoopbackHosts(Set<String> loopbackHosts) {
            this.loopbackHosts = loopbackHosts == null ? new LinkedHashSet<>() : loopbackHosts;
        }

        public Set<Integer> getLoopbackPorts() {
            return loopbackPorts;
        }

        public void setLoopbackPorts(Set<Integer> loopbackPorts) {
            this.loopbackPorts = loopbackPorts == null ? new LinkedHashSet<>() : loopbackPorts;
        }
    }
}
