package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.client.DefaultStreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandles;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Retries retryable failures, honoring Retry-After when requested by the upstream. */
public final class RetryInterceptor implements ChatInterceptor {
    private final RetryPolicy policy;

    public RetryInterceptor(RetryPolicy policy) {
        this.policy = policy == null ? RetryPolicy.disabled() : policy;
    }

    public RetryInterceptor(int maxAttempts) {
        this(RetryPolicy.of(maxAttempts));
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public ChatResponse intercept(ChatRequest request, ChatInvocation next) {
        for (int attempt = 1; ; attempt++) {
            try {
                return next.invoke(request);
            } catch (ModelBridgeException failure) {
                if (attempt >= policy.maxAttempts() || !policy.allows(failure.category())) {
                    throw failure;
                }
                sleep(backoff(failure, attempt));
            }
        }
    }

    @Override
    public StreamHandle interceptStream(ChatRequest request, Consumer<StreamChunk> consumer,
                                        ChatStreamInvocation next) {
        if (policy.maxAttempts() <= 1) {
            return next.invoke(request, consumer);
        }
        return StreamHandles.start(StreamHandles.sharedExecutor(), outer -> {
            AtomicReference<StreamHandle> active = new AtomicReference<>();
            outer.onCancel(() -> {
                StreamHandle current = active.get();
                if (current != null) {
                    current.cancel();
                }
            });
            for (int attempt = 1; ; attempt++) {
                if (outer.isCancelled()) {
                    return;
                }
                AtomicBoolean emitted = new AtomicBoolean();
                Consumer<StreamChunk> tracked = chunk -> {
                    if (chunk.type() == StreamChunk.Type.CONTENT
                            || chunk.type() == StreamChunk.Type.REASONING
                            || chunk.type() == StreamChunk.Type.TOOL_CALL_DELTA) {
                        emitted.set(true);
                    }
                    consumer.accept(chunk);
                };
                StreamHandle inner = next.invoke(request, tracked);
                active.set(inner);
                if (outer.isCancelled()) {
                    inner.cancel();
                }
                try {
                    inner.await();
                    return;
                } catch (ModelBridgeException failure) {
                    if (outer.isCancelled() || emitted.get()
                            || attempt >= policy.maxAttempts()
                            || !policy.allows(failure.category())) {
                        throw failure;
                    }
                    sleep(backoff(failure, attempt));
                } finally {
                    active.compareAndSet(inner, null);
                }
            }
        });
    }

    RetryPolicy policy() {
        return policy;
    }

    private Duration backoff(ModelBridgeException failure, int attempt) {
        if (policy.respectRetryAfter() && failure.retryAfter() != null
                && !failure.retryAfter().isNegative()
                && failure.retryAfter().compareTo(policy.maxBackoff()) <= 0) {
            return failure.retryAfter();
        }
        double factor = Math.pow(policy.multiplier(), Math.max(0, attempt - 1));
        Duration calculated;
        try {
            calculated = Duration.ofMillis(
                    Math.round(policy.initialBackoff().toMillis() * factor));
        } catch (ArithmeticException error) {
            calculated = policy.maxBackoff();
        }
        return calculated.compareTo(policy.maxBackoff()) > 0 ? policy.maxBackoff() : calculated;
    }

    private static void sleep(Duration duration) {
        if (duration == null || duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBridgeException(ErrorCategory.TIMEOUT, "retry interrupted",
                    null, 0, null, null, e);
        }
    }
}
