package io.github.bangshou1st.modelbridge.core.client;

import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;

public interface RerankModel {
    String modelId();

    ProviderConfig provider();

    RerankResult rerank(RerankRequest request);
}
