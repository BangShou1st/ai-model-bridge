package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.security.LogRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/** Safe request/response summaries only; prompt text and credentials are never logged. */
public final class LoggingInterceptor implements ChatInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public int order() {
        return -1000;
    }

    @Override
    public ChatResponse intercept(ChatRequest request, ChatInvocation next) {
        long started = System.nanoTime();
        try {
            ChatResponse response = next.invoke(request);
            log.debug("chat completed: {}, finish={}, latency={}ms",
                    LogRedactor.summarize(request), response.finishReason(), elapsed(started));
            return response;
        } catch (RuntimeException failure) {
            log.warn("chat failed: {}, error={}", LogRedactor.summarize(request),
                    LogRedactor.redact(String.valueOf(failure.getClass().getSimpleName())));
            throw failure;
        }
    }

    @Override
    public StreamHandle interceptStream(ChatRequest request, Consumer<StreamChunk> consumer,
                                        ChatStreamInvocation next) {
        log.debug("chat stream started: {}", LogRedactor.summarize(request));
        return next.invoke(request, consumer);
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
