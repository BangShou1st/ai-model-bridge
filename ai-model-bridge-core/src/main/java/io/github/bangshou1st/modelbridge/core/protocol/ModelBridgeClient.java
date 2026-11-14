package io.github.bangshou1st.modelbridge.core.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;

import java.util.Objects;
import java.util.function.Consumer;

/** Facade shared by direct library users and the optional gateway. */
public final class ModelBridgeClient {
    private final AdapterRegistry registry;

    public ModelBridgeClient(AdapterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public static ModelBridgeClient create(ObjectMapper mapper, OutboundEndpointPolicy policy) {
        HttpTransport transport = new HttpTransport(mapper, policy);
        return new ModelBridgeClient(AdapterRegistry.withBuiltIns(mapper, transport));
    }

    public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
        return registry.chat(provider.type()).complete(provider, request);
    }

    public void completeStream(ProviderConfig provider, ChatRequest request,
                               Consumer<StreamChunk> consumer) {
        registry.chat(provider.type()).completeStream(provider, request, consumer);
    }

    public TurnResult turn(ProviderConfig provider, TurnRequest request) {
        return registry.chat(provider.type()).turn(provider, request);
    }

    public EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request) {
        return registry.embedding(provider.type()).embed(provider, request);
    }

    public RerankResult rerank(ProviderConfig provider, RerankRequest request) {
        return registry.rerank(provider.type()).rerank(provider, request);
    }

    public AdapterRegistry registry() {
        return registry;
    }
}
