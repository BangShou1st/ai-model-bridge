package io.github.bangshou1st.modelbridge.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.config.BridgeConfig;
import io.github.bangshou1st.modelbridge.core.config.DefaultModelRouter;
import io.github.bangshou1st.modelbridge.core.config.ModelDefinition;
import io.github.bangshou1st.modelbridge.core.config.ModelRouter;
import io.github.bangshou1st.modelbridge.core.config.ProviderDefinition;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.starter.ModelBridgeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Merges the declarative core provider model with the legacy gateway upstream syntax. */
@Configuration(proxyBeanMethods = false)
public class GatewayModelRouterConfiguration {
    @Bean
    @Primary
    public ModelRouter gatewayModelRouter(ModelBridgeProperties properties,
                                          GatewayProperties gatewayProperties,
                                          AdapterRegistry registry,
                                          ModelBridgeClient client,
                                          ModelDiscoveryClient discovery,
                                          ObjectMapper mapper,
                                          OutboundEndpointPolicy policy) {
        BridgeConfig merged = properties.copy();
        gatewayProperties.getUpstreams().forEach((id, upstream) -> {
            if (merged.getProviders().containsKey(id)) {
                return;
            }
            ProviderDefinition provider = new ProviderDefinition();
            provider.setProtocol(protocol(upstream.getType()).value());
            provider.setBaseUrl(upstream.getBaseUrl());
            provider.setApiKey(upstream.getApiKey());
            provider.setHeaders(upstream.getHeaders());
            provider.setOptions(upstream.getOptions());
            provider.setModels(upstream.getModels().stream()
                    .map(GatewayModelRouterConfiguration::model)
                    .toList());
            merged.getProviders().put(id, provider);
        });
        gatewayProperties.getAliases().forEach((alias, target) -> {
            if (!merged.getAliases().containsKey(alias)) {
                merged.addAliasTarget(alias, target);
            }
        });
        merged.getProviders().values().forEach(provider ->
                policy.validate(provider.getBaseUrl()));
        return new DefaultModelRouter(merged, registry, client, discovery, mapper);
    }

    private static ModelDefinition model(GatewayProperties.Model source) {
        ModelDefinition model = new ModelDefinition(source.getId());
        model.setAlias(source.getAlias());
        model.setDisplayName(source.getDisplayName());
        model.setContextWindow(source.getContextWindow());
        model.setMaxInputTokens(source.getMaxInputTokens());
        model.setMaxOutputTokens(source.getMaxOutputTokens());
        model.setSupportsTools(source.isSupportsTools());
        model.setSupportsReasoning(source.isSupportsReasoning());
        model.setSupportsStreaming(source.isSupportsStreaming());
        model.setSupportsStructuredOutput(source.isSupportsStructuredOutput());
        model.setSupportsEmbeddings(source.isSupportsEmbeddings());
        model.setSupportsRerank(source.isSupportsRerank());
        model.setReasoningLevels(source.getReasoningLevels());
        model.setSupportedParameters(source.getSupportedParameters());
        model.setPricing(source.getPricing());
        model.setNativeEndpoints(source.getNativeEndpoints());
        model.setInputModalities(source.getInputModalities());
        model.setOutputModalities(source.getOutputModalities());
        return model;
    }

    private static ProtocolId protocol(String value) {
        if (value == null || value.isBlank()) {
            return ProtocolId.OPENAI_COMPATIBLE;
        }
        return switch (value.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "openai", "openai-compatible" -> ProtocolId.OPENAI_COMPATIBLE;
            case "anthropic" -> ProtocolId.ANTHROPIC;
            case "gemini", "google" -> ProtocolId.GEMINI;
            case "ollama" -> ProtocolId.OLLAMA;
            case "llama-cpp", "llamacpp", "llama.cpp" -> ProtocolId.LLAMA_CPP;
            default -> ProtocolId.of(value);
        };
    }
}
