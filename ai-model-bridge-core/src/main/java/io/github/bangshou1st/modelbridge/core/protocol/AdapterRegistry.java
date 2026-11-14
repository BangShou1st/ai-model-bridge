package io.github.bangshou1st.modelbridge.core.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;

import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open registry for protocol adapters. Third-party protocols are first-class and do not require a
 * new enum value.
 */
public final class AdapterRegistry {
    private final Map<ProtocolId, ChatProtocolAdapter> chat = new ConcurrentHashMap<>();
    private final Map<ProtocolId, EmbeddingProtocolAdapter> embedding = new ConcurrentHashMap<>();
    private final Map<ProtocolId, RerankProtocolAdapter> rerank = new ConcurrentHashMap<>();
    private final Map<ProtocolId, ModelDiscoveryAdapter> discovery = new ConcurrentHashMap<>();

    public AdapterRegistry() {
    }

    public AdapterRegistry register(Object adapter) {
        Objects.requireNonNull(adapter, "adapter");
        boolean recognized = false;
        if (adapter instanceof ChatProtocolAdapter chatAdapter) {
            protocols(chatAdapter.name(), chatAdapter.protocols())
                    .forEach(protocol -> chat.put(protocol, chatAdapter));
            recognized = true;
        }
        if (adapter instanceof EmbeddingProtocolAdapter embeddingAdapter) {
            protocols(embeddingAdapter.name(), embeddingAdapter.protocols())
                    .forEach(protocol -> embedding.put(protocol, embeddingAdapter));
            recognized = true;
        }
        if (adapter instanceof RerankProtocolAdapter rerankAdapter) {
            protocols(rerankAdapter.name(), rerankAdapter.protocols())
                    .forEach(protocol -> rerank.put(protocol, rerankAdapter));
            recognized = true;
        }
        if (adapter instanceof ModelDiscoveryAdapter discoveryAdapter) {
            protocols(discoveryAdapter.name(), discoveryAdapter.protocols())
                    .forEach(protocol -> discovery.put(protocol, discoveryAdapter));
            recognized = true;
        }
        if (!recognized) {
            throw new IllegalArgumentException("unsupported adapter type: " + adapter.getClass().getName());
        }
        return this;
    }

    public AdapterRegistry registerAll(Iterable<?> adapters) {
        adapters.forEach(this::register);
        return this;
    }

    public AdapterRegistry loadPlugins() {
        ServiceLoader.load(BridgePlugin.class)
                .forEach(plugin -> registerAll(plugin.adapters()));
        return this;
    }

    public ChatProtocolAdapter chat(ProtocolId protocol) {
        ChatProtocolAdapter adapter = protocol == null ? null : chat.get(protocol);
        if (adapter == null) {
            throw new IllegalArgumentException("no chat adapter registered for " + protocol);
        }
        return adapter;
    }

    public EmbeddingProtocolAdapter embedding(ProtocolId protocol) {
        EmbeddingProtocolAdapter adapter = protocol == null ? null : embedding.get(protocol);
        if (adapter == null) {
            throw new IllegalArgumentException("no embedding adapter registered for " + protocol);
        }
        return adapter;
    }

    public RerankProtocolAdapter rerank(ProtocolId protocol) {
        RerankProtocolAdapter adapter = protocol == null ? null : rerank.get(protocol);
        if (adapter == null) {
            throw new IllegalArgumentException("no rerank adapter registered for " + protocol);
        }
        return adapter;
    }

    public ModelDiscoveryAdapter discovery(ProtocolId protocol) {
        ModelDiscoveryAdapter adapter = protocol == null ? null : discovery.get(protocol);
        if (adapter == null) {
            throw new IllegalArgumentException("no discovery adapter registered for " + protocol);
        }
        return adapter;
    }

    public boolean supportsChat(ProtocolId protocol) {
        return protocol != null && chat.containsKey(protocol);
    }

    public boolean supportsEmbedding(ProtocolId protocol) {
        return protocol != null && embedding.containsKey(protocol);
    }

    public boolean supportsRerank(ProtocolId protocol) {
        return protocol != null && rerank.containsKey(protocol);
    }

    public boolean supportsDiscovery(ProtocolId protocol) {
        return protocol != null && discovery.containsKey(protocol);
    }

    public Set<ProtocolId> chatProtocols() {
        return Set.copyOf(chat.keySet());
    }

    public Set<ProtocolId> embeddingProtocols() {
        return Set.copyOf(embedding.keySet());
    }

    public Set<ProtocolId> rerankProtocols() {
        return Set.copyOf(rerank.keySet());
    }

    public Set<ProtocolId> discoveryProtocols() {
        return Set.copyOf(discovery.keySet());
    }

    @Deprecated
    public ChatProtocolAdapter chat(ProviderType type) {
        return chat(type.toProtocolId());
    }

    @Deprecated
    public EmbeddingProtocolAdapter embedding(ProviderType type) {
        return embedding(type.toProtocolId());
    }

    @Deprecated
    public RerankProtocolAdapter rerank(ProviderType type) {
        return rerank(type.toProtocolId());
    }

    @Deprecated
    public boolean supportsChat(ProviderType type) {
        return type != null && supportsChat(type.toProtocolId());
    }

    @Deprecated
    public boolean supportsEmbedding(ProviderType type) {
        return type != null && supportsEmbedding(type.toProtocolId());
    }

    @Deprecated
    public boolean supportsRerank(ProviderType type) {
        return type != null && supportsRerank(type.toProtocolId());
    }

    public static AdapterRegistry withBuiltIns(ObjectMapper mapper, HttpTransport http) {
        return new AdapterRegistry()
                .register(new io.github.bangshou1st.modelbridge.core.protocol.openai.OpenAiCompatibleAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.anthropic.AnthropicAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.gemini.GeminiAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.embedding.OpenAiEmbeddingAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.embedding.OllamaEmbeddingAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.embedding.GeminiEmbeddingAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.rerank.LlamaCppRerankAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.discovery.OpenAiModelDiscoveryAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.discovery.AnthropicModelDiscoveryAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.discovery.GeminiModelDiscoveryAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.discovery.OllamaModelDiscoveryAdapter(mapper, http));
    }

    private static Set<ProtocolId> protocols(String name, Set<ProtocolId> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("adapter " + name + " declares no protocols");
        }
        return values;
    }
}
