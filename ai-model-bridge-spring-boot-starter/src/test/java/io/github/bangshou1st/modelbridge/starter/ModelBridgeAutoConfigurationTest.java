package io.github.bangshou1st.modelbridge.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.probe.CapabilityProber;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelBridgeAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelBridgeAutoConfiguration.class));

    @Test
    void createsAllSevenCoreBeansByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(OutboundEndpointPolicy.class);
            assertThat(context).hasSingleBean(HttpTransport.class);
            assertThat(context).hasSingleBean(AdapterRegistry.class);
            assertThat(context).hasSingleBean(ModelBridgeClient.class);
            assertThat(context).hasSingleBean(ModelDiscoveryClient.class);
            assertThat(context).hasSingleBean(CapabilityProber.class);
        });
    }

    @Test
    void bindsProperties() {
        runner.withPropertyValues(
                        "ai.bridge.connect-timeout=3s",
                        "ai.bridge.request-timeout=17s",
                        "ai.bridge.security.loopback-hosts=127.0.0.1,localhost",
                        "ai.bridge.security.loopback-ports=3425,8080")
                .run(context -> {
                    ModelBridgeProperties properties = context.getBean(ModelBridgeProperties.class);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(17));
                    assertThat(properties.getSecurity().getLoopbackHosts())
                            .containsExactlyInAnyOrder("127.0.0.1", "localhost");
                    assertThat(properties.getSecurity().getLoopbackPorts())
                            .containsExactlyInAnyOrder(3425, 8080);
                });
    }

    @Test
    void customClientBeanWins() {
        ModelBridgeClient custom = new ModelBridgeClient(new AdapterRegistry());
        runner.withBean(ModelBridgeClient.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(ModelBridgeClient.class);
            assertThat(context.getBean(ModelBridgeClient.class)).isSameAs(custom);
        });
    }

    @Test
    void customObjectMapperBeanWins() {
        ObjectMapper custom = new ObjectMapper();
        runner.withBean(ObjectMapper.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context.getBean(ObjectMapper.class)).isSameAs(custom);
        });
    }

    @Test
    void testProfileAllowsExplicitLoopbackPortAndPolicyAcceptsIt() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        "ai.bridge.security.allow-loopback=true",
                        "ai.bridge.security.loopback-ports=3425")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OutboundEndpointPolicy policy = context.getBean(OutboundEndpointPolicy.class);
                    assertThat(policy.validate(URI.create("http://127.0.0.1:3425/v1/models")).host())
                            .isEqualTo("127.0.0.1");
                    assertThatThrownBy(() -> policy.validate(URI.create("http://127.0.0.1:3426/v1/models")))
                            .isInstanceOf(ModelBridgeException.class);
                });
    }

    @Test
    void localProfileAllowsLoopback() {
        runner.withPropertyValues(
                        "spring.profiles.active=local",
                        "ai.bridge.security.allow-loopback=true",
                        "ai.bridge.security.loopback-ports=11434")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OutboundEndpointPolicy.class)
                            .validate(URI.create("http://localhost:11434/api/tags")).host())
                            .isEqualTo("localhost");
                });
    }

    @Test
    void loopbackOutsideLocalProfilesFails() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "ai.bridge.security.allow-loopback=true",
                        "ai.bridge.security.loopback-ports=3425")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "ai.bridge.security.allow-loopback requires an active local or test profile");
                });
    }

    @Test
    void guardAlsoRunsWhenApplicationSuppliesItsOwnPolicy() {
        runner.withBean(OutboundEndpointPolicy.class, OutboundEndpointPolicy::new)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "ai.bridge.security.allow-loopback=true",
                        "ai.bridge.security.loopback-ports=3425")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void loopbackRequiresPortAllowlist() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        "ai.bridge.security.allow-loopback=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void disabledPropertiesDoNotCreateBeans() {
        runner.withPropertyValues("ai.bridge.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ObjectMapper.class);
            assertThat(context).doesNotHaveBean(OutboundEndpointPolicy.class);
            assertThat(context).doesNotHaveBean(ModelBridgeClient.class);
        });
    }

    @Test
    void loopbackIsRejectedByDefault() {
        runner.run(context -> {
            OutboundEndpointPolicy policy = context.getBean(OutboundEndpointPolicy.class);
            assertThatThrownBy(() -> policy.validate(URI.create("http://127.0.0.1:3425/v1/models")))
                    .isInstanceOf(ModelBridgeException.class);
        });
    }

    @Test
    void autoConfigurationIsRegisteredForBoot3AndBoot4() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip())
                    .isEqualTo(ModelBridgeAutoConfiguration.class.getName());
        }
    }
}
