package io.github.bangshou1st.modelbridge.server;

import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class GatewayModelCatalog {
    private final GatewayProperties properties;
    private final Map<String, ResolvedModel> routes = new LinkedHashMap<>();
    private final Map<String, ResolvedModel> directModels = new LinkedHashMap<>();
    private final Set<String> ambiguousModels = new LinkedHashSet<>();
    private final List<ModelDescriptor> descriptors;

    public GatewayModelCatalog(GatewayProperties properties, OutboundEndpointPolicy policy) {
        this.properties = properties;
        if (properties.isAuthEnabled() && properties.getApiKeys().stream().allMatch(String::isBlank)) {
            throw new IllegalStateException(
                    "ai.bridge.gateway.api-keys must be configured when gateway auth is enabled");
        }
        Map<String, List<ResolvedModel>> byModel = new LinkedHashMap<>();
        for (Map.Entry<String, GatewayProperties.Upstream> entry : properties.getUpstreams().entrySet()) {
            String upstreamId = entry.getKey();
            GatewayProperties.Upstream upstream = entry.getValue();
            if (upstream.getBaseUrl() == null || upstream.getBaseUrl().isBlank()) {
                throw new IllegalStateException("gateway upstream " + upstreamId + " has no base-url");
            }
            ProviderType type = providerType(upstream.getType());
            ProviderConfig provider = new ProviderConfig(upstreamId, type, upstream.getBaseUrl(),
                    upstream.getApiKey(), upstream.getHeaders(), null, null, upstream.getOptions());
            policy.validate(provider.baseUrl());
            for (GatewayProperties.Model model : upstream.getModels()) {
                if (model.getId() == null || model.getId().isBlank()) {
                    throw new IllegalStateException("upstream " + upstreamId + " has a model without id");
                }
                ResolvedModel resolved = new ResolvedModel(upstreamId, provider, model, exposedId(model));
                List<ResolvedModel> existing = byModel.computeIfAbsent(model.getId(), ignored -> new ArrayList<>());
                existing.add(resolved);
                putUnique(routes, exposedId(model), resolved);
                directModels.putIfAbsent(model.getId(), resolved);
            }
        }
        byModel.forEach((model, values) -> {
            if (values.size() > 1) {
                ambiguousModels.add(model);
                directModels.remove(model);
            }
        });
        properties.getAliases().forEach((alias, target) -> {
            ResolvedModel resolved = resolveTarget(target);
            if (resolved == null) {
                throw new IllegalStateException("gateway alias " + alias + " points to unknown target " + target);
            }
            putUnique(routes, alias, resolved);
        });
        List<ModelDescriptor> built = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, ResolvedModel> entry : routes.entrySet()) {
            if (seen.add(entry.getKey())) {
                built.add(descriptor(entry.getKey(), entry.getValue()));
            }
        }
        this.descriptors = List.copyOf(built);
    }

    public ResolvedModel resolve(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            throw GatewayException.badRequest("model is required");
        }
        ResolvedModel route = routes.get(requestedModel);
        if (route != null) {
            return route;
        }
        if (!ambiguousModels.contains(requestedModel)) {
            route = directModels.get(requestedModel);
            if (route != null) {
                return route;
            }
        }
        throw GatewayException.notFound("model is not configured: " + requestedModel);
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

    private ResolvedModel resolveTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        ResolvedModel direct = routes.get(target);
        if (direct != null) {
            return direct;
        }
        int slash = target.indexOf('/');
        if (slash > 0 && slash < target.length() - 1) {
            String upstreamId = target.substring(0, slash);
            String modelId = target.substring(slash + 1);
            for (ResolvedModel candidate : directModels.values()) {
                if (candidate.upstreamId().equals(upstreamId) && candidate.model().getId().equals(modelId)) {
                    return candidate;
                }
            }
        }
        if (!ambiguousModels.contains(target)) {
            return directModels.get(target);
        }
        return null;
    }

    private static void putUnique(Map<String, ResolvedModel> target, String key, ResolvedModel value) {
        ResolvedModel previous = target.putIfAbsent(key, value);
        if (previous != null) {
            throw new IllegalStateException("duplicate gateway model id or alias: " + key);
        }
    }

    private static String exposedId(GatewayProperties.Model model) {
        return model.getAlias() == null || model.getAlias().isBlank() ? model.getId() : model.getAlias();
    }

    private static ProviderType providerType(String value) {
        if (value == null || value.isBlank()) {
            return ProviderType.OPENAI_COMPATIBLE;
        }
        return switch (value.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "openai", "openai-compatible", "openai_compatible" -> ProviderType.OPENAI_COMPATIBLE;
            case "anthropic" -> ProviderType.ANTHROPIC;
            case "gemini", "google" -> ProviderType.GEMINI;
            case "ollama" -> ProviderType.OLLAMA;
            case "llama-cpp", "llamacpp", "llama.cpp" -> ProviderType.LLAMA_CPP;
            default -> throw new IllegalStateException("unsupported gateway upstream type: " + value);
        };
    }

    private static ModelDescriptor descriptor(String exposedId, ResolvedModel resolved) {
        GatewayProperties.Model model = resolved.model();
        List<String> params = model.getSupportedParameters().isEmpty()
                ? (model.isSupportsTools() ? List.of("tools", "tool_choice") : List.of())
                : model.getSupportedParameters();
        return ModelDescriptor.builder(exposedId)
                .displayName(model.getDisplayName() == null || model.getDisplayName().isBlank()
                        ? exposedId : model.getDisplayName())
                .ownedBy(resolved.upstreamId())
                .provider(resolved.provider().type().name())
                .contextWindow(model.getContextWindow())
                .maxInputTokens(model.getMaxInputTokens())
                .maxOutputTokens(model.getMaxOutputTokens())
                .supportsTools(model.isSupportsTools())
                .supportsReasoning(model.isSupportsReasoning())
                .supportsStreaming(model.isSupportsStreaming())
                .supportsStructuredOutput(model.isSupportsStructuredOutput())
                .reasoningLevels(model.getReasoningLevels())
                .supportedParameters(params)
                .pricing(model.getPricing())
                .inputModalities(model.getInputModalities())
                .outputModalities(model.getOutputModalities())
                .nativeEndpoints(model.getNativeEndpoints().isEmpty()
                        ? List.of("/v1/chat/completions") : model.getNativeEndpoints())
                .build();
    }

    public record ResolvedModel(String upstreamId, ProviderConfig provider, GatewayProperties.Model model,
                                String exposedId) {
    }
}
