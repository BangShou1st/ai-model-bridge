package io.github.bangshou1st.modelbridge.core.model;

/**
 * Optional HTTP proxy. Credentials are never included in {@link #toString()}.
 */
public record ProxyConfig(String host, Integer port, String username, String password) {
    public ProxyConfig {
        if (host != null && (host.isBlank() || port == null || port < 1 || port > 65535)) {
            throw new IllegalArgumentException("proxy host and a valid port are required");
        }
    }

    public static ProxyConfig disabled() {
        return new ProxyConfig(null, null, null, null);
    }

    public static ProxyConfig of(String host, int port) {
        return new ProxyConfig(host, port, null, null);
    }

    public boolean enabled() {
        return host != null && !host.isBlank();
    }

    @Override
    public String toString() {
        return "ProxyConfig[enabled=" + enabled() + ", host=" + host + ", port=" + port + "]";
    }
}
