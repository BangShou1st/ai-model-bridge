package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.function.Consumer;

/** Optional opt-in failure for truncated responses. The framework default is to return them. */
public final class TruncationPolicyInterceptor implements ChatInterceptor {
    private final boolean failOnLength;

    public TruncationPolicyInterceptor() {
        this(false);
    }

    public TruncationPolicyInterceptor(boolean failOnLength) {
        this.failOnLength = failOnLength;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public ChatResponse intercept(ChatRequest request, ChatInvocation next) {
        ChatResponse response = next.invoke(request);
        if (shouldFail(request) && response.finishReason() == FinishReason.LENGTH) {
            throw ModelBridgeException.truncated(response.provider(), response.usage());
        }
        return response;
    }

    @Override
    public StreamHandle interceptStream(ChatRequest request, Consumer<StreamChunk> consumer,
                                        ChatStreamInvocation next) {
        Consumer<StreamChunk> wrapped = chunk -> {
            if (shouldFail(request) && chunk.type() == StreamChunk.Type.DONE
                    && chunk.finishReason() == FinishReason.LENGTH) {
                throw ModelBridgeException.truncated(chunk.provider(), chunk.usage());
            }
            consumer.accept(chunk);
        };
        return next.invoke(request, wrapped);
    }

    private boolean shouldFail(ChatRequest request) {
        return failOnLength || request.options().failOnLength();
    }
}
