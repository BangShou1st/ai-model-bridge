package io.github.bangshou1st.modelbridge.core.protocol;

import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;

import java.util.Set;

public interface EmbeddingProtocolAdapter {
    String name();

    Set<ProviderType> providerTypes();

    EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request);
}
