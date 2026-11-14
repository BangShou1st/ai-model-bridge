package io.github.bangshou1st.modelbridge.core.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Registry and SPI boundary. Built-ins can be installed with {@link #withBuiltIns()} and
 * additional protocol providers can be discovered through {@link BridgePlugin}.
 */
public final class AdapterRegistry {
    private final Map<ProviderType, ChatProtocolAdapter> chat = new EnumMap<>(ProviderType.class);
    private final Map<ProviderType, EmbeddingProtocolAdapter> embedding = new EnumMap<>(ProviderType.class);
    private final Map<ProviderType, RerankProtocolAdapter> rerank = new EnumMap<>(ProviderType.class);

    public AdapterRegistry() {
    }

    public AdapterRegistry register(Object adapter) {
        Objects.requireNonNull(adapter, "adapter");
        boolean recognized = false;
        if (adapter instanceof ChatProtocolAdapter chatAdapter) {
            chatAdapter.providerTypes().forEach(type -> chat.put(type, chatAdapter));
            recognized = true;
        }
        if (adapter instanceof EmbeddingProtocolAdapter embeddingAdapter) {
            embeddingAdapter.providerTypes().forEach(type -> embedding.put(type, embeddingAdapter));
            recognized = true;
        }
        if (adapter instanceof RerankProtocolAdapter rerankAdapter) {
            rerankAdapter.providerTypes().forEach(type -> rerank.put(type, rerankAdapter));
            recognized = true;
        }
        if (!recognized) {
            throw new IllegalArgumentException("unsupported adapter type: " + adapter.getClass().getName());
        }
        return this;
    }

    public AdapterRegistry loadPlugins() {
        ServiceLoader.load(BridgePlugin.class).forEach(plugin -> plugin.adapters().forEach(this::register));
        return this;
    }

    public ChatProtocolAdapter chat(ProviderType type) {
        ChatProtocolAdapter adapter = chat.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("no chat adapter registered for " + type);
        }
        return adapter;
    }

    public EmbeddingProtocolAdapter embedding(ProviderType type) {
        EmbeddingProtocolAdapter adapter = embedding.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("no embedding adapter registered for " + type);
        }
        return adapter;
    }

    public RerankProtocolAdapter rerank(ProviderType type) {
        RerankProtocolAdapter adapter = rerank.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("no rerank adapter registered for " + type);
        }
        return adapter;
    }

    public static AdapterRegistry withBuiltIns(ObjectMapper mapper, HttpTransport http) {
        return new AdapterRegistry()
                .register(new io.github.bangshou1st.modelbridge.core.protocol.openai.OpenAiCompatibleAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.anthropic.AnthropicAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.gemini.GeminiAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.embedding.EmbeddingAdapter(mapper, http))
                .register(new io.github.bangshou1st.modelbridge.core.protocol.rerank.LlamaCppRerankAdapter(mapper, http));
    }
}
