package io.github.bangshou1st.modelbridge.core.client;

import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;

import java.util.Objects;

public final class DefaultEmbeddingModel implements EmbeddingModel {
    private final String modelId;
    private final ProviderConfig provider;
    private final ModelBridgeClient client;

    public DefaultEmbeddingModel(String modelId, ProviderConfig provider, ModelBridgeClient client) {
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public ProviderConfig provider() {
        return provider;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("embedding request is required");
        }
        EmbeddingRequest effective = new EmbeddingRequest(modelId, request.inputs(),
                request.dimensions(), request.providerOptions());
        return client.embed(provider, effective);
    }
}
