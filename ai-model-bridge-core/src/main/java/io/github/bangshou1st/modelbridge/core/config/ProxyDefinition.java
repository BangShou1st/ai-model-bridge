package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.model.ProxyConfig;

public class ProxyDefinition {
    private boolean enabled;
    private String host = "127.0.0.1";
    private Integer port;
    private String username;
    private String password;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public ProxyConfig toProxyConfig() {
        if (!enabled) {
            return ProxyConfig.disabled();
        }
        return new ProxyConfig(host, port, username, password);
    }

    public ProxyDefinition copy() {
        ProxyDefinition copy = new ProxyDefinition();
        copy.enabled = enabled;
        copy.host = host;
        copy.port = port;
        copy.username = username;
        copy.password = password;
        return copy;
    }
}
