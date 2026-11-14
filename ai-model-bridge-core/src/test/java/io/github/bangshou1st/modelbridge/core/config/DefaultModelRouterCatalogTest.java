package io.github.bangshou1st.modelbridge.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultModelRouterCatalogTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpTransport transport = new HttpTransport(
            mapper, OutboundEndpointPolicy.httpsOnly());
    private final AdapterRegistry registry = AdapterRegistry.withBuiltIns(mapper, transport);
    private final ModelDiscoveryClient discovery = new ModelDiscoveryClient(
            mapper, transport, registry);

    @Test
    void exposesProviderQualifiedIdsForDuplicateModelIdsAndKeepsUniqueBareIds() {
        BridgeConfig config = new BridgeConfig();
        config.setDefaultChatModel("alpha/shared-model");
        provider(config, "alpha", "shared-model", "alpha-shared");
        provider(config, "beta", "shared-model", "beta-shared");
        provider(config, "gamma", "unique-model", null);
        provider(config, "delta", "nested/model", null);
        DefaultModelRouter router = router(config);

        List<String> ids = router.catalog().models().stream()
                .map(ModelDescriptor::id).toList();
        assertThat(ids).contains("alpha/shared-model", "beta/shared-model", "unique-model",
                        "delta/nested/model")
                .doesNotContain("shared-model");

        assertRoute(router, "alpha/shared-model", "alpha", "shared-model");
        assertRoute(router, "beta/shared-model", "beta", "shared-model");
        assertRoute(router, "unique-model", "gamma", "unique-model");
        assertRoute(router, "delta/nested/model", "delta", "nested/model");
        assertCatalogIdsRoute(router);
        assertThatThrownBy(() -> router.resolve("shared-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous model id")
                .hasMessageContaining("provider/model");
    }

    @Test
    void exposesQualifiedIdsWhenAliasesShadowModelIdsAndPreservesAliasPriority() {
        BridgeConfig config = new BridgeConfig();
        config.setDefaultChatModel("fast");
        provider(config, "alpha", "shared-model", "fast");
        provider(config, "beta", "fast-model", null);
        config.addAliasTarget("fast-model", "alpha/shared-model");
        DefaultModelRouter router = router(config);

        List<String> ids = router.catalog().models().stream()
                .map(ModelDescriptor::id).toList();
        assertThat(ids).contains("shared-model", "beta/fast-model", "fast", "fast-model")
                .doesNotContain("alpha/shared-model");

        assertRoute(router, "fast", "alpha", "shared-model");
        assertRoute(router, "fast-model", "alpha", "shared-model");
        assertRoute(router, "shared-model", "alpha", "shared-model");
        assertRoute(router, "alpha/shared-model", "alpha", "shared-model");
        assertRoute(router, "beta/fast-model", "beta", "fast-model");

        assertThat(router.catalog().models().stream()
                .filter(model -> model.id().equals("fast"))
                .findFirst().orElseThrow().provider()).isEqualTo("alpha");
        assertThat(router.catalog().models().stream()
                .filter(model -> model.id().equals("fast-model"))
                .findFirst().orElseThrow().provider()).isEqualTo("alpha");
        assertThat(router.catalog().models().stream()
                .filter(model -> model.id().equals("beta/fast-model"))
                .findFirst().orElseThrow().provider()).isEqualTo("beta");
        assertCatalogIdsRoute(router);
    }

    @Test
    void rejectsGlobalAliasThatShadowsAnotherModelsQualifiedReference() {
        BridgeConfig config = duplicateSharedModels();
        config.addAliasTarget("alpha/shared", "beta/shared");

        assertThatThrownBy(() -> router(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("global alias")
                .hasMessageContaining("alpha/shared")
                .hasMessageContaining("conflicts with model reference")
                .hasMessageContaining("beta/shared");
    }

    @Test
    void rejectsModelAliasThatShadowsAnotherModelsQualifiedReference() {
        BridgeConfig config = new BridgeConfig();
        provider(config, "alpha", "shared", null);
        provider(config, "beta", "shared", "alpha/shared");

        assertThatThrownBy(() -> router(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model alias")
                .hasMessageContaining("alpha/shared")
                .hasMessageContaining("conflicts with model reference")
                .hasMessageContaining("beta/shared");
    }

    @Test
    void allowsSingleSameTargetQualifiedAliasesAndNormalFallbackAliases() {
        BridgeConfig config = new BridgeConfig();
        config.setDefaultChatModel("alpha/shared");
        provider(config, "alpha", "shared", "alpha/shared");
        provider(config, "beta", "shared", null);
        config.addAliasTarget("beta/shared", "beta/shared");
        config.addAliasTarget("smart", "alpha/shared");
        config.addAliasTarget("smart", "beta/shared");
        DefaultModelRouter router = router(config);

        assertRoute(router, "alpha/shared", "alpha", "shared");
        assertRoute(router, "beta/shared", "beta", "shared");
        assertThat(router.resolve("smart"))
                .extracting(route -> route.target().id())
                .containsExactly("alpha/shared", "beta/shared");
        assertThat(router.catalog().models().stream().map(ModelDescriptor::id).toList())
                .contains("alpha/shared", "beta/shared", "smart");
        assertCatalogIdsRoute(router);
    }

    @Test
    void validatesQualifiedReferencesWhenModelIdsContainSlashes() {
        BridgeConfig conflict = new BridgeConfig();
        provider(conflict, "alpha", "nested/shared", null);
        provider(conflict, "beta", "nested/shared", null);
        conflict.addAliasTarget("alpha/nested/shared", "beta/nested/shared");

        assertThatThrownBy(() -> router(conflict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("global alias")
                .hasMessageContaining("alpha/nested/shared")
                .hasMessageContaining("beta/nested/shared");

        BridgeConfig allowed = new BridgeConfig();
        provider(allowed, "alpha", "nested/shared", null);
        provider(allowed, "beta", "nested/shared", null);
        allowed.addAliasTarget("alpha/nested/shared", "alpha/nested/shared");
        DefaultModelRouter router = router(allowed);

        assertThat(router.catalog().models().stream().map(ModelDescriptor::id).toList())
                .contains("alpha/nested/shared", "beta/nested/shared");
        assertRoute(router, "alpha/nested/shared", "alpha", "nested/shared");
        assertRoute(router, "beta/nested/shared", "beta", "nested/shared");
        assertCatalogIdsRoute(router);
    }

    private DefaultModelRouter router(BridgeConfig config) {
        return new DefaultModelRouter(config, registry, new ModelBridgeClient(registry),
                discovery, mapper);
    }

    private static BridgeConfig duplicateSharedModels() {
        BridgeConfig config = new BridgeConfig();
        config.setDefaultChatModel("alpha/shared");
        provider(config, "alpha", "shared", null);
        provider(config, "beta", "shared", null);
        return config;
    }

    private static void provider(BridgeConfig config, String providerId, String modelId,
                                 String alias) {
        ProviderDefinition provider = new ProviderDefinition();
        provider.setProtocol("openai-compatible");
        provider.setBaseUrl("https://" + providerId + ".example.invalid/v1");
        provider.setApiKey("test-key");
        ModelDefinition model = new ModelDefinition(modelId);
        model.setAlias(alias);
        provider.getModels().add(model);
        config.getProviders().put(providerId, provider);
    }

    private static void assertRoute(DefaultModelRouter router, String reference,
                                    String providerId, String modelId) {
        List<ModelRoute> routes = router.resolve(reference);
        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().target())
                .isEqualTo(new RouteTarget(providerId, modelId));
    }

    private static void assertCatalogIdsRoute(DefaultModelRouter router) {
        router.catalog().models().forEach(descriptor -> {
            List<ModelRoute> routes = router.resolve(descriptor.id());
            assertThat(routes).as("catalog id %s", descriptor.id()).isNotEmpty();
            assertThat(routes.getFirst().provider().id())
                    .as("catalog id %s provider", descriptor.id())
                    .isEqualTo(descriptor.provider());
        });
    }
}
