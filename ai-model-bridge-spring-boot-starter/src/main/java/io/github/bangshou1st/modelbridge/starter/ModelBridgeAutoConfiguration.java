package io.github.bangshou1st.modelbridge.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.bangshou1st.modelbridge.core.client.ChatModel;
import io.github.bangshou1st.modelbridge.core.client.EmbeddingModel;
import io.github.bangshou1st.modelbridge.core.client.RerankModel;
import io.github.bangshou1st.modelbridge.core.config.DefaultModelRouter;
import io.github.bangshou1st.modelbridge.core.config.ModelRouter;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ProxyConfig;
import io.github.bangshou1st.modelbridge.core.probe.CapabilityProber;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.NetworkPolicyMode;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

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
    public OutboundEndpointPolicy outboundEndpointPolicy(ModelBridgeProperties properties) {
        var security = properties.getSecurity();
        NetworkPolicyMode mode = security.effectiveMode();
        boolean allowLoopback = security.isAllowLoopback()
                || mode == NetworkPolicyMode.ALLOW_LOOPBACK;
        return new OutboundEndpointPolicy(mode, allowLoopback,
                security.getLoopbackHosts(), security.getLoopbackPorts());
    }

    @Bean
    @ConditionalOnMissingBean(HttpTransport.class)
    public HttpTransport httpTransport(ObjectMapper mapper, OutboundEndpointPolicy policy,
                                       ModelBridgeProperties properties) {
        return new HttpTransport(mapper, policy, properties.getConnectTimeout(),
                properties.getRequestTimeout(), proxy(properties));
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
    public ModelDiscoveryClient modelDiscoveryClient(ObjectMapper mapper, HttpTransport http,
                                                     AdapterRegistry registry) {
        return new ModelDiscoveryClient(mapper, http, registry);
    }

    @Bean
    @ConditionalOnMissingBean(CapabilityProber.class)
    public CapabilityProber capabilityProber(ModelDiscoveryClient discovery, ModelBridgeClient client) {
        return new CapabilityProber(discovery, client);
    }

    @Bean
    @ConditionalOnMissingBean(ModelRouter.class)
    public ModelRouter modelRouter(ModelBridgeProperties properties, AdapterRegistry registry,
                                   ModelBridgeClient client, ModelDiscoveryClient discovery,
                                   ObjectMapper mapper) {
        return new DefaultModelRouter(properties, registry, client, discovery, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    @ConditionalOnProperty(prefix = "ai.bridge", name = "default-chat-model")
    public ChatModel defaultChatModel(ModelRouter router) {
        return router.chat();
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(prefix = "ai.bridge", name = "default-embedding-model")
    public EmbeddingModel defaultEmbeddingModel(ModelRouter router) {
        return router.embedding();
    }

    @Bean
    @ConditionalOnMissingBean(RerankModel.class)
    @ConditionalOnProperty(prefix = "ai.bridge", name = "default-rerank-model")
    public RerankModel defaultRerankModel(ModelRouter router) {
        return router.rerank();
    }

    private static ProxyConfig proxy(ModelBridgeProperties properties) {
        ProxyConfig nested = properties.getDefaults().getProxy().toProxyConfig();
        return nested.enabled() ? nested : properties.getProxy().toProxyConfig();
    }
}
