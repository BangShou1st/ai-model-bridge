package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;

import java.util.function.Consumer;

@FunctionalInterface
public interface ChatStreamInvocation {
    StreamHandle invoke(ChatRequest request, Consumer<StreamChunk> consumer);
}
