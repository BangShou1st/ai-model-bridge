package io.github.bangshou1st.modelbridge.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.probe.CapabilityProber;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.http.HttpClient;

/**
 * Auto-configuration for the Spring-free core. No data source, persistence, user, tenant or
 * business bean is created here.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")
@EnableConfigurationProperties(ModelBridgeProperties.class)
@ConditionalOnProperty(prefix = "ai.bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModelBridgeAutoConfiguration {
    public ModelBridgeAutoConfiguration(ModelBridgeProperties properties, Environment environment) {
        LoopbackAllowlistGuard.verify(environment, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper modelBridgeObjectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    @Bean
    @ConditionalOnMissingBean(OutboundEndpointPolicy.class)
    public OutboundEndpointPolicy outboundEndpointPolicy(ModelBridgeProperties properties,
                                                          Environment environment) {
        ModelBridgeProperties.Security security = properties.getSecurity();
        return new OutboundEndpointPolicy(security.isAllowLoopback(), security.getLoopbackHosts(),
                security.getLoopbackPorts());
    }

    @Bean
    @ConditionalOnMissingBean(HttpTransport.class)
    public HttpTransport httpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy,
                                       ModelBridgeProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new HttpTransport(mapper, policy, properties.getConnectTimeout(),
                properties.getRequestTimeout(), client);
    }

    @Bean
    @ConditionalOnMissingBean(AdapterRegistry.class)
    public AdapterRegistry adapterRegistry(ObjectMapper mapper, HttpTransport http) {
        return AdapterRegistry.withBuiltIns(mapper, http).loadPlugins();
    }

    @Bean
    @ConditionalOnMissingBean(ModelBridgeClient.class)
    public ModelBridgeClient modelBridgeClient(AdapterRegistry registry) {
        return new ModelBridgeClient(registry);
    }

    @Bean
    @ConditionalOnMissingBean(ModelDiscoveryClient.class)
    public ModelDiscoveryClient modelDiscoveryClient(ObjectMapper mapper, HttpTransport http) {
        return new ModelDiscoveryClient(mapper, http);
    }

    @Bean
    @ConditionalOnMissingBean(CapabilityProber.class)
    public CapabilityProber capabilityProber(ModelDiscoveryClient discovery, ModelBridgeClient client) {
        return new CapabilityProber(discovery, client);
    }
}
