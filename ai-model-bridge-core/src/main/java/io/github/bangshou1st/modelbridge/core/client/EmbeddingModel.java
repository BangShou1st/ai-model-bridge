package io.github.bangshou1st.modelbridge.core.client;

import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;

import java.util.List;

public interface EmbeddingModel {
    String modelId();

    ProviderConfig provider();

    EmbeddingResult embed(EmbeddingRequest request);

    default List<Double> embed(String text) {
        EmbeddingResult result = embed(new EmbeddingRequest(modelId(), List.of(text)));
        return result.embeddings().isEmpty() ? List.of() : result.embeddings().getFirst();
    }
}
