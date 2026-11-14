package io.github.bangshou1st.modelbridge.core.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundEndpointPolicyTest {
    @Test
    void acceptsPublicHttpsAndReturnsPinnedAddresses() {
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(false, Set.of(), Set.of(),
                host -> new InetAddress[]{unchecked("93.184.216.34")});
        var endpoint = policy.validate(URI.create("https://api.example.com/v1/models"));

        assertThat(endpoint.pinnedAddress()).isEqualTo(unchecked("93.184.216.34"));
        assertThat(endpoint.addresses()).hasSize(1);
    }

    @Test
    void rejectsHttpForPublicEndpoints() {
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(false, Set.of(), Set.of(),
                host -> new InetAddress[]{unchecked("93.184.216.34")});

        assertThatThrownBy(() -> policy.validate(URI.create("http://api.example.com/v1")))
                .isInstanceOf(ModelBridgeException.class)
                .extracting(e -> ((ModelBridgeException) e).category())
                .isEqualTo(ErrorCategory.NETWORK_POLICY);
    }

    @Test
    void rejectsPrivateMetadataAndMixedAnswers() {
        for (String value : new String[]{"10.1.2.3", "169.254.169.254", "192.168.1.10"}) {
            OutboundEndpointPolicy policy = new OutboundEndpointPolicy(false, Set.of(), Set.of(),
                    host -> new InetAddress[]{unchecked(value)});
            assertThatThrownBy(() -> policy.validate(URI.create("https://internal.example/v1")))
                    .as(value)
                    .isInstanceOf(ModelBridgeException.class);
        }
        OutboundEndpointPolicy mixed = new OutboundEndpointPolicy(false, Set.of(), Set.of(),
                host -> new InetAddress[]{unchecked("93.184.216.34"), unchecked("10.0.0.1")});
        assertThatThrownBy(() -> mixed.validate(URI.create("https://rebind.example/v1")))
                .isInstanceOf(ModelBridgeException.class);
    }

    @Test
    void loopbackRequiresExplicitHostAndPortWhitelist() {
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(true,
                Set.of("127.0.0.1"), Set.of(18080),
                host -> new InetAddress[]{unchecked("127.0.0.1")});

        assertThat(policy.validate(URI.create("http://127.0.0.1:18080/v1/models")).host())
                .isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> policy.validate(URI.create("http://127.0.0.1:18081/v1/models")))
                .isInstanceOf(ModelBridgeException.class);
        assertThatThrownBy(() -> policy.validate(URI.create("https://localhost:18080/v1")))
                .isInstanceOf(ModelBridgeException.class);
    }

    @Test
    void rejectsUserInfoAndMetadataHostNames() {
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(false, Set.of(), Set.of(),
                host -> new InetAddress[]{unchecked("93.184.216.34")});

        assertThatThrownBy(() -> policy.validate(URI.create(
                "https://user:pass@api.example.com/v1")))
                .isInstanceOf(ModelBridgeException.class);
        assertThatThrownBy(() -> policy.validate(URI.create(
                "https://metadata.google.internal/v1")))
                .isInstanceOf(ModelBridgeException.class);
    }

    @Test
    void privateNetworkModeAllowsInternalHttpButNeverMetadata() {
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(
                NetworkPolicyMode.ALLOW_PRIVATE_NETWORK, false, Set.of(), Set.of(),
                host -> new InetAddress[]{unchecked(host.equals("metadata")
                        ? "169.254.169.254" : "10.12.0.8")});

        assertThat(policy.validate(URI.create("http://internal.example:8080/v1")).host())
                .isEqualTo("internal.example");
        assertThatThrownBy(() -> policy.validate(URI.create("http://metadata/v1")))
                .isInstanceOf(ModelBridgeException.class)
                .extracting(error -> ((ModelBridgeException) error).category())
                .isEqualTo(ErrorCategory.NETWORK_POLICY);
    }

    @Test
    void privateNetworkModeStillRequiresLoopbackOptIn() {
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(
                NetworkPolicyMode.ALLOW_PRIVATE_NETWORK, false, Set.of("127.0.0.1"),
                Set.of(11434), host -> new InetAddress[]{unchecked("127.0.0.1")});

        assertThatThrownBy(() -> policy.validate(
                URI.create("http://127.0.0.1:11434/api/tags")))
                .isInstanceOf(ModelBridgeException.class);
    }

    private static InetAddress unchecked(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
