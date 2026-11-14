package io.github.bangshou1st.modelbridge.core.client;

import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe stream handle with a single cancellation hook. */
public final class DefaultStreamHandle implements StreamHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch completion = new CountDownLatch(1);

    public static DefaultStreamHandle completed() {
        DefaultStreamHandle handle = new DefaultStreamHandle();
        handle.complete();
        return handle;
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            Runnable action = cancelAction.getAndSet(null);
            if (action != null) {
                action.run();
            }
            done.set(true);
            completion.countDown();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public boolean isDone() {
        return done.get();
    }

    @Override
    public void await() {
        try {
            completion.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBridgeException(ErrorCategory.TIMEOUT, "interrupted while waiting for stream",
                    null, 0, null, null, e);
        }
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ModelBridgeException(ErrorCategory.INTERNAL, "stream failed", null,
                    0, null, null, error);
        }
    }

    @Override
    public void onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (cancelled.get()) {
            action.run();
            return;
        }
        cancelAction.set(action);
        if (cancelled.get() && cancelAction.getAndSet(null) != null) {
            action.run();
        }
    }

    public void complete() {
        if (!cancelled.get()) {
            done.set(true);
            completion.countDown();
        }
    }

    public void fail(Throwable error) {
        if (cancelled.get()) {
            complete();
            return;
        }
        failure.compareAndSet(null, Objects.requireNonNull(error, "error"));
        done.set(true);
        completion.countDown();
    }
}
