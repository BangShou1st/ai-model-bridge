package io.github.bangshou1st.modelbridge.core.protocol;

import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;

import java.util.Set;

public interface EmbeddingProtocolAdapter {
    String name();

    default Set<ProtocolId> protocols() {
        return providerTypes().stream().map(ProviderType::toProtocolId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Deprecated
    default Set<ProviderType> providerTypes() {
        return Set.of();
    }

    EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request);
}
