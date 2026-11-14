package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.client.DefaultStreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RetryInterceptorCancellationTest {
    @Test
    void forwardsCancellationToAnInnerHandleCreatedAfterTheOuterHandleWasCancelled()
            throws Exception {
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch releaseInvocation = new CountDownLatch(1);
        DefaultStreamHandle inner = new DefaultStreamHandle();
        RetryInterceptor retry = new RetryInterceptor(3);

        StreamHandle outer = retry.interceptStream(
                ChatRequest.builder("model").user("cancel while opening").build(),
                chunk -> {
                },
                (request, consumer) -> {
                    invocationStarted.countDown();
                    await(releaseInvocation);
                    return inner;
                });

        try {
            assertThat(invocationStarted.await(3, TimeUnit.SECONDS)).isTrue();
            outer.cancel();
            releaseInvocation.countDown();

            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!inner.isCancelled() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(inner.isCancelled())
                    .as("the retry layer forwards cancellation to a late-created inner handle")
                    .isTrue();
        } finally {
            releaseInvocation.countDown();
            inner.cancel();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
