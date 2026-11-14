package io.github.bangshou1st.modelbridge.core.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.interceptor.ChatInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.ChatInterceptorChain;
import io.github.bangshou1st.modelbridge.core.interceptor.LoggingInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.RetryInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.RetryPolicy;
import io.github.bangshou1st.modelbridge.core.interceptor.StructuredOutputInterceptor;
import io.github.bangshou1st.modelbridge.core.interceptor.TruncationPolicyInterceptor;
import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Default model instance with an ordered interceptor pipeline. */
public final class DefaultChatModel implements ChatModel {
    private final String modelId;
    private final ProviderConfig provider;
    private final ModelBridgeClient client;
    private final ChatOptions defaults;
    private final ChatInterceptorChain chain;

    public DefaultChatModel(String modelId, ProviderConfig provider, ModelBridgeClient client) {
        this(modelId, provider, client, defaultInterceptors(), ChatOptions.defaults());
    }

    public DefaultChatModel(String modelId, ProviderConfig provider, ModelBridgeClient client,
                            List<ChatInterceptor> interceptors, ChatOptions defaults) {
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.client = Objects.requireNonNull(client, "client");
        this.defaults = defaults == null ? ChatOptions.defaults() : defaults;
        this.chain = new ChatInterceptorChain(interceptors);
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public ProviderConfig provider() {
        return provider;
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        ChatRequest effective = effective(request);
        return chain.invoke(effective, current -> client.complete(provider, current));
    }

    @Override
    public StreamHandle stream(ChatRequest request, Consumer<StreamChunk> consumer) {
        ChatRequest effective = effective(request);
        return chain.invokeStream(effective, consumer,
                (current, events) -> client.openStream(provider, current, events));
    }

    @Override
    public ChatModel withOptions(ChatOptions overrides) {
        return new DefaultChatModel(modelId, provider, client, chain.interceptors(),
                defaults.mergeWith(overrides));
    }

    public ChatOptions defaults() {
        return defaults;
    }

    private ChatRequest effective(ChatRequest request) {
        ChatOptions requestOptions = request == null
                ? ChatOptions.defaults() : request.options();
        ChatOptions merged = defaults.mergeWith(requestOptions);
        ChatRequest source = request == null
                ? ChatRequest.builder(modelId).build() : request;
        return new ChatRequest(modelId, source.messages(), merged.temperature(),
                merged.maxOutputTokens(), source.tools(), source.toolChoice(),
                source.structuredOutput(), source.reasoning(), merged.extraBody(),
                source.requestId(), merged);
    }

    private static List<ChatInterceptor> defaultInterceptors() {
        return List.of(new LoggingInterceptor(), new RetryInterceptor(RetryPolicy.disabled()),
                new StructuredOutputInterceptor(new ObjectMapper()),
                new TruncationPolicyInterceptor());
    }
}
