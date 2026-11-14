package io.github.bangshou1st.modelbridge.core.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.protocol.openai.OpenAiCompatibleAdapter;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.core.testsupport.LocalFeatureServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterceptorIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void retryInterceptorHonorsRetryAfterAgainstARealServer() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> {
            if (attempts.getAndIncrement() == 0) {
                return new LocalFeatureServer.Response(429, "application/json",
                        "{\"error\":\"busy\"}", Map.of("Retry-After", "1"));
            }
            return new LocalFeatureServer.Response(200, "application/json", """
                    {"model":"retry-model","choices":[{"index":0,
                     "message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """);
        })) {
            ProviderConfig provider = provider(server);
            OpenAiCompatibleAdapter adapter = adapter(server);
            RetryInterceptor retry = new RetryInterceptor(RetryPolicy.builder()
                    .maxAttempts(2)
                    .initialBackoff(Duration.ofMillis(10))
                    .maxBackoff(Duration.ofSeconds(2))
                    .respectRetryAfter(true)
                    .build());
            long started = System.nanoTime();

            ChatResponse response = retry.intercept(
                    ChatRequest.builder("retry-model").user("hello").build(),
                    request -> adapter.complete(provider, request));

            assertThat(response.content()).isEqualTo("ok");
            assertThat(attempts).hasValue(2);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isGreaterThanOrEqualTo(Duration.ofMillis(900));
        }
    }

    @Test
    void truncationInterceptorTurnsLengthIntoUsageAwareFailureOnlyWhenEnabled()
            throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request ->
                new LocalFeatureServer.Response(200, "application/json", """
                        {"model":"length-model","choices":[{"index":0,
                         "message":{"role":"assistant","content":"partial"},
                         "finish_reason":"length"}],
                         "usage":{"prompt_tokens":4,"completion_tokens":9,"total_tokens":13}}
                        """))) {
            ProviderConfig provider = provider(server);
            OpenAiCompatibleAdapter adapter = adapter(server);
            ChatRequest request = ChatRequest.builder("length-model")
                    .user("long")
                    .options(ChatOptions.builder().failOnLength(true).build())
                    .build();

            assertThatThrownBy(() -> new TruncationPolicyInterceptor(true)
                    .intercept(request, current -> adapter.complete(provider, current)))
                    .isInstanceOfSatisfying(ModelBridgeException.class, failure -> {
                        assertThat(failure.category()).isEqualTo(ErrorCategory.LENGTH);
                        assertThat(failure.usage().outputTokens()).isEqualTo(9);
                    });
        }
    }

    @Test
    void truncationInterceptorOnStreamSeesTrailingUsageBeforeFailing() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request ->
                new LocalFeatureServer.Response(200, "text/event-stream; charset=utf-8", """
                        data: {"model":"stream-length","choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":"length"}]}

                        data: {"model":"stream-length","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":9,"total_tokens":14}}

                        data: [DONE]

                        """))) {
            ProviderConfig provider = provider(server);
            OpenAiCompatibleAdapter adapter = adapter(server);
            ChatRequest request = ChatRequest.builder("stream-length")
                    .user("long")
                    .options(ChatOptions.builder().failOnLength(true).build())
                    .build();
            List<StreamChunk> chunks = new ArrayList<>();

            StreamHandle handle = new TruncationPolicyInterceptor(true)
                    .interceptStream(request, chunks::add,
                            (current, consumer) -> adapter.stream(provider, current, consumer));

            assertThatThrownBy(handle::await)
                    .isInstanceOfSatisfying(ModelBridgeException.class, failure -> {
                        assertThat(failure.category()).isEqualTo(ErrorCategory.LENGTH);
                        assertThat(failure.usage().inputTokens()).isEqualTo(5);
                        assertThat(failure.usage().outputTokens()).isEqualTo(9);
                    });
            assertThat(chunks).noneMatch(chunk -> chunk.type() == StreamChunk.Type.DONE);
        }
    }

    @Test
    void cancellingStreamClosesTheUpstreamConnection() throws Exception {
        CountDownLatch firstFrameRead = new CountDownLatch(1);
        AtomicBoolean writeFailed = new AtomicBoolean();
        AtomicInteger frames = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                while (frames.get() < 500) {
                    String event = "data: {\"model\":\"cancel-model\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"tick\"}}]}\n\n";
                    output.write(event.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    frames.incrementAndGet();
                    Thread.sleep(20);
                }
            } catch (IOException error) {
                writeFailed.set(true);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ProviderConfig provider = ProviderConfig.openAiCompatible(
                    "cancel", "http://127.0.0.1:" + port, "key")
                    .withTimeout(Duration.ofSeconds(10));
            OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(Set.of(port));
            OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(mapper,
                    new HttpTransport(mapper, policy));

            StreamHandle handle = adapter.stream(provider,
                    ChatRequest.builder("cancel-model").user("stream forever").build(),
                    chunk -> {
                        if (chunk.type() == StreamChunk.Type.CONTENT) {
                            firstFrameRead.countDown();
                        }
                    });
            assertThat(firstFrameRead.await(3, TimeUnit.SECONDS)).isTrue();
            long cancelledAt = System.nanoTime();
            handle.cancel();
            handle.await();

            assertThat(handle.isCancelled()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - cancelledAt))
                    .isLessThan(Duration.ofSeconds(2));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!writeFailed.get() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(writeFailed).as("server observed the cancelled connection").isTrue();
        } finally {
            server.stop(0);
        }
    }

    private OpenAiCompatibleAdapter adapter(LocalFeatureServer server) {
        OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(
                Set.of(server.port()));
        return new OpenAiCompatibleAdapter(mapper, new HttpTransport(mapper, policy));
    }

    private static ProviderConfig provider(LocalFeatureServer server) {
        return ProviderConfig.openAiCompatible("interceptor", server.baseUrl(), "key")
                .withTimeout(Duration.ofSeconds(5));
    }
}
