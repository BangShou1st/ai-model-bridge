package io.github.bangshou1st.modelbridge.core.protocol;

import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandles;

import java.util.Set;
import java.util.function.Consumer;

/** Protocol plugin contract for completion and native-tool turns. */
public interface ChatProtocolAdapter {
    String name();

    default Set<ProtocolId> protocols() {
        return providerTypes().stream().map(ProviderType::toProtocolId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Deprecated
    default Set<ProviderType> providerTypes() {
        return Set.of();
    }

    ChatResponse complete(ProviderConfig provider, ChatRequest request);

    void completeStream(ProviderConfig provider, ChatRequest request, Consumer<StreamChunk> consumer);

    /**
     * Starts a cancellable stream. Built-in adapters override this to bind transport cancellation;
     * the compatibility implementation keeps third-party adapters working.
     */
    default StreamHandle stream(ProviderConfig provider, ChatRequest request,
                                Consumer<StreamChunk> consumer) {
        return StreamHandles.start(StreamHandles.sharedExecutor(),
                handle -> completeStream(provider, request, consumer));
    }

    @Deprecated
    default TurnResult turn(ProviderConfig provider, TurnRequest request) {
        return complete(provider, request.asChatRequest()).toTurnResult();
    }
}
