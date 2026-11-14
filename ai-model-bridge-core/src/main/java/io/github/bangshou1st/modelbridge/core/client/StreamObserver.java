package io.github.bangshou1st.modelbridge.core.client;

import io.github.bangshou1st.modelbridge.core.model.StreamChunk;

import java.util.function.Consumer;

/** Optional event-style observer for streaming model calls. */
public interface StreamObserver {
    void onChunk(StreamChunk chunk);

    default void onError(Throwable error) {
    }

    default void onComplete() {
    }

    static StreamObserver of(Consumer<StreamChunk> consumer) {
        return new StreamObserver() {
            @Override
            public void onChunk(StreamChunk chunk) {
                consumer.accept(chunk);
            }
        };
    }
}
