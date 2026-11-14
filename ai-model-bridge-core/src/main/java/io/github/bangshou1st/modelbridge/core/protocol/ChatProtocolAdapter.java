package io.github.bangshou1st.modelbridge.core.protocol;

import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;

import java.util.Set;
import java.util.function.Consumer;

/** Protocol plugin contract for completion and native-tool turns. */
public interface ChatProtocolAdapter {
    String name();

    Set<ProviderType> providerTypes();

    ChatResponse complete(ProviderConfig provider, ChatRequest request);

    void completeStream(ProviderConfig provider, ChatRequest request, Consumer<StreamChunk> consumer);

    TurnResult turn(ProviderConfig provider, TurnRequest request);
}
