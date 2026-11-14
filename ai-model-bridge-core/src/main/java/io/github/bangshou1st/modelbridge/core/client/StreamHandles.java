package io.github.bangshou1st.modelbridge.core.client;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Helpers shared by protocol adapters and the higher-level model facade. */
public final class StreamHandles {
    private static final ExecutorService SHARED = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ai-model-bridge-stream");
        thread.setDaemon(true);
        return thread;
    });

    private StreamHandles() {
    }

    public static Executor sharedExecutor() {
        return SHARED;
    }

    public static StreamHandle start(Executor executor, Consumer<DefaultStreamHandle> task) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");
        DefaultStreamHandle handle = new DefaultStreamHandle();
        executor.execute(() -> {
            try {
                task.accept(handle);
                handle.complete();
            } catch (Throwable error) {
                handle.fail(error);
            }
        });
        return handle;
    }
}
