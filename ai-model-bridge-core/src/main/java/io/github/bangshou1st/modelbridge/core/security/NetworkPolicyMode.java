package io.github.bangshou1st.modelbridge.core.security;

/** Outbound SSRF policy presets. Cloud metadata destinations are blocked in every mode. */
public enum NetworkPolicyMode {
    PUBLIC_HTTPS_ONLY,
    ALLOW_LOOPBACK,
    ALLOW_PRIVATE_NETWORK
}
