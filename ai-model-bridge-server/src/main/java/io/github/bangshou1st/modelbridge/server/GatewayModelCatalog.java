package io.github.bangshou1st.modelbridge.server;

import io.github.bangshou1st.modelbridge.core.config.ModelRoute;
import io.github.bangshou1st.modelbridge.core.config.ModelRouter;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gateway-facing catalog backed by the unified declarative {@link ModelRouter}. */
@Component
public class GatewayModelCatalog {
    private final ModelRouter router;
    private final Map<String, ModelDescriptor> descriptorsById;
    private final List<ModelDescriptor> descriptors;

    public GatewayModelCatalog(GatewayProperties properties, ModelRouter router) {
        if (properties.isAuthEnabled() && properties.getApiKeys().stream().allMatch(String::isBlank)) {
            throw new IllegalStateException(
                    "ai.bridge.gateway.api-keys must be configured when gateway auth is enabled");
        }
        this.router = router;
        this.descriptors = router.catalog().models();
        Map<String, ModelDescriptor> byId = new LinkedHashMap<>();
        descriptors.forEach(descriptor -> byId.put(descriptor.id(), descriptor));
        this.descriptorsById = Map.copyOf(byId);
    }

    public ResolvedModel resolve(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            throw GatewayException.badRequest("model is required");
        }
        List<ModelRoute> routes;
        try {
            routes = router.resolve(requestedModel);
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("ambiguous ")) {
                throw GatewayException.notFound(error.getMessage());
            }
            throw GatewayException.notFound("model is not configured: " + requestedModel);
        }
        if (routes.isEmpty()) {
            throw GatewayException.notFound("model is not configured: " + requestedModel);
        }
        ModelRoute route = routes.getFirst();
        ModelDescriptor descriptor = descriptorsById.getOrDefault(requestedModel, route.descriptor());
        return new ResolvedModel(route.provider().id(), route.provider(), descriptor,
                requestedModel, requestedModel);
    }

    public List<ModelDescriptor> descriptors() {
        return descriptors;
    }

    public Map<String, Object> modelList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", descriptors);
        if (!descriptors.isEmpty()) {
            result.put("first_id", descriptors.getFirst().id());
            result.put("last_id", descriptors.getLast().id());
        }
        result.put("has_more", false);
        return result;
    }

    public ModelRouter router() {
        return router;
    }

    public record ResolvedModel(String upstreamId, ProviderConfig provider,
                                ModelDescriptor model, String exposedId, String reference) {
    }
}
