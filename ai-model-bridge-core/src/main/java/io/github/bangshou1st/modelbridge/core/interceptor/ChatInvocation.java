package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;

@FunctionalInterface
public interface ChatInvocation {
    ChatResponse invoke(ChatRequest request);
}
