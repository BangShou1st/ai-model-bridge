package io.github.bangshou1st.modelbridge.core.protocol;

import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;

import java.util.Set;

public interface RerankProtocolAdapter {
    String name();

    Set<ProviderType> providerTypes();

    RerankResult rerank(ProviderConfig provider, RerankRequest request);
}
