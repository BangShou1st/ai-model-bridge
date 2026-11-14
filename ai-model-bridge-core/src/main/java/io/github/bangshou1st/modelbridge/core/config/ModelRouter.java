package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.client.ChatModel;
import io.github.bangshou1st.modelbridge.core.client.EmbeddingModel;
import io.github.bangshou1st.modelbridge.core.client.RerankModel;

import java.util.List;

/** Declarative model lookup, option inheritance, aliases and fallback routing. */
public interface ModelRouter {
    ChatModel chat();

    ChatModel chat(String reference);

    EmbeddingModel embedding();

    EmbeddingModel embedding(String reference);

    RerankModel rerank();

    RerankModel rerank(String reference);

    List<ModelRoute> resolve(String reference);

    ModelCatalogSnapshot catalog();

    BridgeConfig config();
}
