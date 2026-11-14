package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class ChatInterceptorChain {
    private final List<ChatInterceptor> interceptors;

    public ChatInterceptorChain(List<ChatInterceptor> interceptors) {
        this.interceptors = interceptors == null ? List.of() : interceptors.stream()
                .sorted(Comparator.comparingInt(ChatInterceptor::order))
                .toList();
    }

    public ChatResponse invoke(ChatRequest request, ChatInvocation terminal) {
        ChatInvocation invocation = terminal;
        for (int index = interceptors.size() - 1; index >= 0; index--) {
            ChatInterceptor interceptor = interceptors.get(index);
            ChatInvocation next = invocation;
            invocation = current -> interceptor.intercept(current, next);
        }
        return invocation.invoke(request);
    }

    public StreamHandle invokeStream(ChatRequest request, Consumer<StreamChunk> consumer,
                                     ChatStreamInvocation terminal) {
        ChatStreamInvocation invocation = terminal;
        for (int index = interceptors.size() - 1; index >= 0; index--) {
            ChatInterceptor interceptor = interceptors.get(index);
            ChatStreamInvocation next = invocation;
            invocation = (current, events) -> interceptor.interceptStream(current, events, next);
        }
        return invocation.invoke(request, consumer);
    }

    public List<ChatInterceptor> interceptors() {
        return interceptors;
    }
}
