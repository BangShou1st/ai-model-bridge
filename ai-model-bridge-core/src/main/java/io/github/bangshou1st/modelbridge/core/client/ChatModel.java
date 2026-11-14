package io.github.bangshou1st.modelbridge.core.client;

import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;

import java.util.function.Consumer;

/** Bound, provider-neutral chat model instance. */
public interface ChatModel {
    String modelId();

    ProviderConfig provider();

    ChatResponse call(ChatRequest request);

    default String call(String userPrompt) {
        return call(ChatRequest.builder(modelId()).user(userPrompt).build()).content();
    }

    StreamHandle stream(ChatRequest request, Consumer<StreamChunk> consumer);

    ChatModel withOptions(ChatOptions defaults);
}
