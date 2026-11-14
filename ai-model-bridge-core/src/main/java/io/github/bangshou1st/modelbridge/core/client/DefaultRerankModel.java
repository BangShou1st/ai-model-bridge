package io.github.bangshou1st.modelbridge.core.client;

import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;

import java.util.Objects;

public final class DefaultRerankModel implements RerankModel {
    private final String modelId;
    private final ProviderConfig provider;
    private final ModelBridgeClient client;

    public DefaultRerankModel(String modelId, ProviderConfig provider, ModelBridgeClient client) {
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
    public RerankResult rerank(RerankRequest request) {
        RerankRequest effective = new RerankRequest(modelId, request.query(),
                request.documents(), request.topN(), request.providerOptions());
        return client.rerank(provider, effective);
    }
}
