package io.github.bangshou1st.modelbridge.starter;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import io.github.bangshou1st.modelbridge.core.security.NetworkPolicyMode;

import java.util.Objects;

/**
 * Prevents the loopback exception from becoming an implicit production SSRF bypass. The profile
 * restriction is checked before any HTTP bean is created.
 */
final class LoopbackAllowlistGuard {
    private static final Profiles LOCAL_OR_TEST = Profiles.of("local", "test");

    private LoopbackAllowlistGuard() {
    }

    static void verify(Environment environment, ModelBridgeProperties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        var security = properties.getSecurity();
        boolean loopbackEnabled = security.isAllowLoopback()
                || security.effectiveMode() == NetworkPolicyMode.ALLOW_LOOPBACK;
        if (!loopbackEnabled) {
            return;
        }
        if (!security.isAllowLoopbackInProduction()
                && !environment.acceptsProfiles(LOCAL_OR_TEST)) {
            throw new IllegalStateException(
                    "ai.bridge.security.allow-loopback requires an active local or test profile");
        }
        if (security.getLoopbackHosts().isEmpty()) {
            throw new IllegalStateException(
                    "ai.bridge.security.allow-loopback requires an explicit loopback host allowlist");
        }
        if (security.getLoopbackPorts().isEmpty()) {
            throw new IllegalStateException(
                    "ai.bridge.security.allow-loopback requires an explicit loopback port allowlist");
        }
    }
}
