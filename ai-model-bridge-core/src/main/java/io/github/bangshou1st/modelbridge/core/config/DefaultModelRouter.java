package io.github.bangshou1st.modelbridge.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.client.ChatModel;
import io.github.bangshou1st.modelbridge.core.client.DefaultChatModel;
import io.github.bangshou1st.modelbridge.core.client.DefaultEmbeddingModel;
import io.github.bangshou1st.modelbridge.core.client.DefaultRerankModel;
import io.github.bangshou1st.modelbridge.core.client.DefaultStreamHandle;
import io.github.bangshou1st.modelbridge.core.client.EmbeddingModel;
import io.github.bangshou1st.modelbridge.core.client.RerankModel;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandles;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.interceptor.ChatInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.LoggingInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.RetryInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.StructuredOutputInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.TruncationPolicyInterceptor;
import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProxyConfig;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Default declarative router with option inheritance, aliases and retryable fallback. */
public final class DefaultModelRouter implements ModelRouter {
    private static final Logger log = LoggerFactory.getLogger(DefaultModelRouter.class);

    private final BridgeConfig config;
    private final AdapterRegistry registry;
    private final ModelBridgeClient client;
    private final ModelDiscoveryClient discovery;
    private final ObjectMapper mapper;
    private final Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private final Map<RouteTarget, ModelRoute> modelRoutes = new LinkedHashMap<>();
    private final Map<String, List<ModelRoute>> aliases = new LinkedHashMap<>();
    private final Map<String, List<ModelRoute>> modelIdRoutes = new LinkedHashMap<>();
    private final Map<String, List<ModelRoute>> modelAliasRoutes = new LinkedHashMap<>();
    private final ModelCatalogSnapshot catalog;
    private final List<ChatInterceptor> interceptors;

    public DefaultModelRouter(BridgeConfig config, AdapterRegistry registry,
                              ModelBridgeClient client, ModelDiscoveryClient discovery,
                              ObjectMapper mapper) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.client = Objects.requireNonNull(client, "client");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.interceptors = defaultInterceptors();
        buildProviders();
        buildModelRoutes();
        buildAliases();
        validateAliasModelReferenceConflicts();
        this.catalog = buildCatalog();
    }

    @Override
    public ChatModel chat() {
        return chat(defaultReference(config.getDefaultChatModel(), "chat"));
    }

    @Override
    public ChatModel chat(String reference) {
        return new RoutedChatModel(reference, chatRoutes(reference), ChatOptions.defaults());
    }

    @Override
    public EmbeddingModel embedding() {
        return embedding(defaultReference(config.getDefaultEmbeddingModel(), "embedding"));
    }

    @Override
    public EmbeddingModel embedding(String reference) {
        List<ModelRoute> routes = capabilityRoutes(reference,
                route -> registry.supportsEmbedding(route.provider().protocol()), "embedding");
        return new RoutedEmbeddingModel(reference, routes);
    }

    @Override
    public RerankModel rerank() {
        return rerank(defaultReference(config.getDefaultRerankModel(), "rerank"));
    }

    @Override
    public RerankModel rerank(String reference) {
        List<ModelRoute> routes = capabilityRoutes(reference,
                route -> registry.supportsRerank(route.provider().protocol()), "rerank");
        return new RoutedRerankModel(reference, routes);
    }

    @Override
    public List<ModelRoute> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("model reference must not be blank");
        }
        String normalized = reference.strip();
        List<ModelRoute> explicit = aliases.get(normalized);
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        RouteTarget direct = parseDirect(normalized);
        if (direct != null && modelRoutes.containsKey(direct)) {
            return List.of(modelRoutes.get(direct));
        }
        List<ModelRoute> byAlias = modelAliasRoutes.get(normalized);
        if (byAlias != null && byAlias.size() == 1) {
            return byAlias;
        }
        if (byAlias != null && byAlias.size() > 1) {
            throw ambiguous(normalized, "model alias");
        }
        List<ModelRoute> byId = modelIdRoutes.get(normalized);
        if (byId != null && byId.size() == 1) {
            return byId;
        }
        if (byId != null && byId.size() > 1) {
            throw ambiguous(normalized, "model id");
        }
        throw new IllegalArgumentException("unknown model reference: " + normalized);
    }

    @Override
    public ModelCatalogSnapshot catalog() {
        return catalog;
    }

    @Override
    public BridgeConfig config() {
        return config;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private void buildProviders() {
        ProxyConfig proxy = globalProxy();
        config.getProviders().forEach((id, definition) -> {
            if (definition.getBaseUrl() == null || definition.getBaseUrl().isBlank()) {
                throw new IllegalStateException("provider " + id + " has no base-url");
            }
            providers.put(id, definition.toProviderConfig(id, config.getConnectTimeout(),
                    config.getRequestTimeout(), proxy));
        });
    }

    private void buildModelRoutes() {
        ChatOptions globalDefaults = config.getDefaults().toChatOptions();
        providers.forEach((providerId, provider) -> {
            ProviderDefinition definition = config.getProviders().get(providerId);
            Map<String, ModelDefinition> declared = new LinkedHashMap<>();
            for (ModelDefinition model : definition.getModels()) {
                if (model.getId() == null || model.getId().isBlank()) {
                    throw new IllegalStateException("provider " + providerId + " has a model without id");
                }
                declared.put(model.getId(), model);
            }
            Map<String, ModelDescriptor> discovered = discover(definition, provider);
            Set<String> ids = new LinkedHashSet<>(declared.keySet());
            ids.addAll(discovered.keySet());
            for (String id : ids) {
                ModelDefinition model = declared.computeIfAbsent(id, ModelDefinition::new);
                ModelDescriptor descriptor = model.toDescriptor(providerId, discovered.get(id));
                ChatOptions options = globalDefaults
                        .mergeWith(definition.getDefaults().toChatOptions())
                        .mergeWith(model.getDefaults().toChatOptions());
                RouteTarget target = new RouteTarget(providerId, id);
                ModelRoute route = new ModelRoute(providerId + "/" + id, target, provider, model,
                        descriptor, options, false);
                modelRoutes.put(target, route);
                modelIdRoutes.computeIfAbsent(id, ignored -> new ArrayList<>()).add(route);
                if (model.getAlias() != null && !model.getAlias().isBlank()) {
                    modelAliasRoutes.computeIfAbsent(model.getAlias(), ignored -> new ArrayList<>())
                            .add(new ModelRoute(model.getAlias(), target, provider, model,
                                    descriptor, options, true));
                }
            }
        });
    }

    private Map<String, ModelDescriptor> discover(ProviderDefinition definition,
                                                  ProviderConfig provider) {
        if (!definition.isDiscoverModels()) {
            return Map.of();
        }
        if (!registry.supportsDiscovery(provider.protocol())) {
            log.warn("model discovery requested but no adapter is registered for {}",
                    provider.protocol().value());
            return Map.of();
        }
        try {
            Map<String, ModelDescriptor> result = new LinkedHashMap<>();
            discovery.discover(provider).forEach(model -> result.put(model.id(), model));
            return result;
        } catch (ModelBridgeException failure) {
            log.warn("model discovery failed for provider={}, category={}", provider.id(),
                    failure.category());
            return Map.of();
        }
    }

    private void buildAliases() {
        Map<String, List<ModelRoute>> merged = new LinkedHashMap<>(modelAliasRoutes);
        config.getAliases().forEach((alias, targets) -> {
            List<ModelRoute> resolved = new ArrayList<>();
            for (String target : targets == null ? List.<String>of() : targets) {
                resolved.addAll(resolveDuringBuild(target));
            }
            if (resolved.isEmpty()) {
                throw new IllegalStateException("alias " + alias + " has no resolvable targets");
            }
            List<ModelRoute> aliasRoutes = resolved.stream()
                    .map(route -> new ModelRoute(alias, route.target(), route.provider(),
                            route.model(), route.descriptor(), route.defaults(), true))
                    .toList();
            merged.put(alias, aliasRoutes);
        });
        merged.forEach((alias, routes) -> aliases.put(alias, List.copyOf(routes)));
    }

    private void validateAliasModelReferenceConflicts() {
        Map<String, ModelRoute> routesByReference = new LinkedHashMap<>();
        modelRoutes.values().forEach(route ->
                routesByReference.putIfAbsent(route.reference(), route));
        validateAliasModelReferenceConflicts("model alias", modelAliasRoutes, routesByReference);

        Map<String, List<ModelRoute>> globalAliases = new LinkedHashMap<>();
        config.getAliases().keySet().forEach(alias -> {
            List<ModelRoute> routes = aliases.get(alias);
            if (routes != null && !routes.isEmpty()) {
                globalAliases.put(alias, routes);
            }
        });
        validateAliasModelReferenceConflicts("global alias", globalAliases, routesByReference);
    }

    private static void validateAliasModelReferenceConflicts(
            String source, Map<String, List<ModelRoute>> aliasRoutes,
            Map<String, ModelRoute> routesByReference) {
        aliasRoutes.forEach((alias, routes) -> {
            ModelRoute modelRoute = routesByReference.get(alias);
            if (modelRoute == null) {
                return;
            }
            boolean sameSingleTarget = routes.size() == 1
                    && routes.getFirst().target().equals(modelRoute.target());
            if (!sameSingleTarget) {
                List<String> targets = routes.stream()
                        .map(route -> route.target().id()).toList();
                throw new IllegalStateException(source + " " + alias
                        + " conflicts with model reference " + modelRoute.reference()
                        + ": alias targets " + targets
                        + ", model target " + modelRoute.target().id());
            }
        });
    }

    private List<ModelRoute> resolveDuringBuild(String reference) {
        List<ModelRoute> existing = aliases.get(reference);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        RouteTarget target = parseDirect(reference);
        if (target != null && modelRoutes.containsKey(target)) {
            return List.of(modelRoutes.get(target));
        }
        List<ModelRoute> byAlias = modelAliasRoutes.get(reference);
        if (byAlias != null && byAlias.size() == 1) {
            return byAlias;
        }
        List<ModelRoute> byId = modelIdRoutes.get(reference);
        if (byId != null && byId.size() == 1) {
            return byId;
        }
        if ((byAlias != null && byAlias.size() > 1) || (byId != null && byId.size() > 1)) {
            throw ambiguous(reference, "model target");
        }
        throw new IllegalStateException("alias target is unknown: " + reference);
    }

    private ModelCatalogSnapshot buildCatalog() {
        Map<String, ModelDescriptor> models = new LinkedHashMap<>();
        modelRoutes.values().forEach(route -> {
            String exposedId = catalogModelId(route);
            models.putIfAbsent(exposedId, descriptorWithId(route.descriptor(), exposedId));
        });
        aliases.forEach((alias, routes) -> {
            ModelDescriptor source = routes.getFirst().descriptor();
            models.putIfAbsent(alias, descriptorWithId(source, alias));
        });
        Map<String, List<RouteTarget>> aliasTargets = new LinkedHashMap<>();
        aliases.forEach((alias, routes) -> aliasTargets.put(alias,
                routes.stream().map(ModelRoute::target).toList()));
        return new ModelCatalogSnapshot(List.copyOf(models.values()), Map.copyOf(aliasTargets));
    }

    private String catalogModelId(ModelRoute route) {
        String id = route.target().modelId();
        if (id.contains("/")) {
            return route.reference();
        }
        List<ModelRoute> sameId = modelIdRoutes.get(id);
        if (sameId != null && sameId.size() > 1) {
            return route.reference();
        }
        List<ModelRoute> aliasRoutes = aliases.get(id);
        if (aliasRoutes != null && !aliasRoutes.isEmpty()
                && (aliasRoutes.size() > 1
                || !aliasRoutes.getFirst().target().equals(route.target()))) {
            return route.reference();
        }
        return id;
    }

    private static ModelDescriptor descriptorWithId(ModelDescriptor source, String id) {
        return ModelDescriptor.builder(id)
                .displayName(source.displayName())
                .ownedBy(source.ownedBy())
                .provider(source.provider())
                .contextWindow(source.contextWindow())
                .maxInputTokens(source.maxInputTokens())
                .maxOutputTokens(source.maxOutputTokens())
                .supportsTools(source.supportsTools())
                .supportsReasoning(source.supportsReasoning())
                .supportsStreaming(source.supportsStreaming())
                .supportsStructuredOutput(source.supportsStructuredOutput())
                .supportsEmbeddings(source.supportsEmbeddings())
                .supportsRerank(source.supportsRerank())
                .reasoningLevels(source.reasoningLevels())
                .supportedParameters(source.supportedParameters())
                .pricing(source.pricing())
                .inputModalities(source.inputModalities())
                .outputModalities(source.outputModalities())
                .nativeEndpoints(source.nativeEndpoints())
                .createdAt(source.createdAt())
                .extra(source.extra())
                .build();
    }

    private List<ModelRoute> chatRoutes(String reference) {
        return capabilityRoutes(reference,
                route -> registry.supportsChat(route.provider().protocol()), "chat");
    }

    private List<ModelRoute> capabilityRoutes(String reference,
                                              java.util.function.Predicate<ModelRoute> supported,
                                              String capability) {
        List<ModelRoute> routes = resolve(reference).stream().filter(supported).toList();
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("no " + capability
                    + " route is available for " + reference);
        }
        return routes;
    }

    private String defaultReference(String configured, String capability) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        List<String> candidates = new ArrayList<>();
        if ("chat".equals(capability)) {
            modelRoutes.values().stream()
                    .filter(route -> registry.supportsChat(route.provider().protocol()))
                    .map(route -> route.target().id()).distinct().forEach(candidates::add);
        } else if ("embedding".equals(capability)) {
            modelRoutes.values().stream()
                    .filter(route -> registry.supportsEmbedding(route.provider().protocol()))
                    .map(route -> route.target().id()).distinct().forEach(candidates::add);
        } else {
            modelRoutes.values().stream()
                    .filter(route -> registry.supportsRerank(route.provider().protocol()))
                    .map(route -> route.target().id()).distinct().forEach(candidates::add);
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        throw new IllegalStateException("ai.bridge.default-" + capability
                + "-model is required when zero or multiple routes are configured");
    }

    private ProxyConfig globalProxy() {
        ProxyConfig nested = config.getDefaults().getProxy().toProxyConfig();
        return nested.enabled() ? nested : config.getProxy().toProxyConfig();
    }

    private List<ChatInterceptor> defaultInterceptors() {
        RetryDefinition retryDefinition = config.getDefaults().getRetry();
        if (retryDefinition.getMaxAttempts() <= 1 && config.getRetry().getMaxAttempts() > 1) {
            retryDefinition = config.getRetry();
        }
        return List.of(new LoggingInterceptor(),
                new RetryInterceptor(retryDefinition.toRetryPolicy()),
                new StructuredOutputInterceptor(mapper),
                new TruncationPolicyInterceptor());
    }

    private static RouteTarget parseDirect(String reference) {
        int slash = reference.indexOf('/');
        if (slash <= 0 || slash >= reference.length() - 1) {
            return null;
        }
        return new RouteTarget(reference.substring(0, slash), reference.substring(slash + 1));
    }

    private static IllegalArgumentException ambiguous(String reference, String kind) {
        return new IllegalArgumentException("ambiguous " + kind + " reference " + reference
                + "; use provider/model");
    }

    private ChatModel chatModel(ModelRoute route, ChatOptions extra) {
        return new DefaultChatModel(route.model().getId(), route.provider(), client,
                interceptors, route.defaults().mergeWith(extra));
    }

    private final class RoutedChatModel implements ChatModel {
        private final String reference;
        private final List<ModelRoute> routes;
        private final ChatOptions overrides;

        private RoutedChatModel(String reference, List<ModelRoute> routes, ChatOptions overrides) {
            this.reference = reference;
            this.routes = routes;
            this.overrides = overrides == null ? ChatOptions.defaults() : overrides;
        }

        @Override
        public String modelId() {
            return routes.getFirst().model().getId();
        }

        @Override
        public ProviderConfig provider() {
            return routes.getFirst().provider();
        }

        @Override
        public ChatResponse call(ChatRequest request) {
            ModelBridgeException last = null;
            for (ModelRoute route : routes) {
                try {
                    return chatModel(route, overrides).call(request);
                } catch (ModelBridgeException failure) {
                    last = failure;
                    if (!failure.retryable()) {
                        throw failure;
                    }
                }
            }
            throw last == null ? new IllegalStateException("no chat route for " + reference) : last;
        }

        @Override
        public StreamHandle stream(ChatRequest request, Consumer<StreamChunk> consumer) {
            return StreamHandles.start(StreamHandles.sharedExecutor(), outer -> {
                AtomicReference<StreamHandle> active = new AtomicReference<>();
                outer.onCancel(() -> {
                    StreamHandle handle = active.get();
                    if (handle != null) {
                        handle.cancel();
                    }
                });
                ModelBridgeException last = null;
                for (ModelRoute route : routes) {
                    if (outer.isCancelled()) {
                        return;
                    }
                    AtomicBoolean emitted = new AtomicBoolean();
                    Consumer<StreamChunk> tracked = chunk -> {
                        if (chunk.type() == StreamChunk.Type.CONTENT
                                || chunk.type() == StreamChunk.Type.REASONING
                                || chunk.type() == StreamChunk.Type.TOOL_CALL_DELTA) {
                            emitted.set(true);
                        }
                        consumer.accept(chunk);
                    };
                    StreamHandle handle = chatModel(route, overrides).stream(request, tracked);
                    active.set(handle);
                    if (outer.isCancelled()) {
                        handle.cancel();
                    }
                    try {
                        handle.await();
                        return;
                    } catch (ModelBridgeException failure) {
                        last = failure;
                        if (outer.isCancelled() || emitted.get() || !failure.retryable()) {
                            throw failure;
                        }
                    } finally {
                        active.compareAndSet(handle, null);
                    }
                }
                if (last != null) {
                    throw last;
                }
            });
        }

        @Override
        public ChatModel withOptions(ChatOptions defaults) {
            return new RoutedChatModel(reference, routes,
                    overrides.mergeWith(defaults));
        }
    }

    private final class RoutedEmbeddingModel implements EmbeddingModel {
        private final String reference;
        private final List<ModelRoute> routes;

        private RoutedEmbeddingModel(String reference, List<ModelRoute> routes) {
            this.reference = reference;
            this.routes = routes;
        }

        @Override
        public String modelId() {
            return routes.getFirst().model().getId();
        }

        @Override
        public ProviderConfig provider() {
            return routes.getFirst().provider();
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
            ModelBridgeException last = null;
            for (ModelRoute route : routes) {
                try {
                    return new DefaultEmbeddingModel(route.model().getId(), route.provider(), client)
                            .embed(request);
                } catch (ModelBridgeException failure) {
                    last = failure;
                    if (!failure.retryable()) {
                        throw failure;
                    }
                }
            }
            throw last == null
                    ? new IllegalStateException("no embedding route for " + reference) : last;
        }
    }

    private final class RoutedRerankModel implements RerankModel {
        private final String reference;
        private final List<ModelRoute> routes;

        private RoutedRerankModel(String reference, List<ModelRoute> routes) {
            this.reference = reference;
            this.routes = routes;
        }

        @Override
        public String modelId() {
            return routes.getFirst().model().getId();
        }

        @Override
        public ProviderConfig provider() {
            return routes.getFirst().provider();
        }

        @Override
        public RerankResult rerank(RerankRequest request) {
            ModelBridgeException last = null;
            for (ModelRoute route : routes) {
                try {
                    return new DefaultRerankModel(route.model().getId(), route.provider(), client)
                            .rerank(request);
                } catch (ModelBridgeException failure) {
                    last = failure;
                    if (!failure.retryable()) {
                        throw failure;
                    }
                }
            }
            throw last == null
                    ? new IllegalStateException("no rerank route for " + reference) : last;
        }
    }
}
