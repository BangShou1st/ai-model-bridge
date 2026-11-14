package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;

import java.util.function.Consumer;

/** Middleware around non-streaming and streaming chat calls. Lower order values run outermost. */
public interface ChatInterceptor {
    default int order() {
        return 0;
    }

    default ChatResponse intercept(ChatRequest request, ChatInvocation next) {
        return next.invoke(request);
    }

    default StreamHandle interceptStream(ChatRequest request, Consumer<StreamChunk> consumer,
                                         ChatStreamInvocation next) {
        return next.invoke(request, consumer);
    }
}
