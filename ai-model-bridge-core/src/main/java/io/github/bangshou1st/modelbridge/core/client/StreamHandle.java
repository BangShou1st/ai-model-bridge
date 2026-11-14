package io.github.bangshou1st.modelbridge.core.client;

/** Handle for an in-flight stream. Closing the handle cancels the upstream request. */
public interface StreamHandle extends AutoCloseable {
    void cancel();

    boolean isCancelled();

    boolean isDone();

    /**
     * Waits for completion and rethrows a transport/runtime failure observed by the stream task.
     */
    void await();

    /**
     * Registers an action that releases the upstream connection. The action may run immediately
     * when the handle is already cancelled.
     */
    default void onCancel(Runnable action) {
    }

    @Override
    default void close() {
        cancel();
    }
}
