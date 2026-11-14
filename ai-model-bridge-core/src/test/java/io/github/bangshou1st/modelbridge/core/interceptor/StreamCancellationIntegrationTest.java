package io.github.bangshou1st.modelbridge.core.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.config.BridgeConfig;
import io.github.bangshou1st.modelbridge.core.config.DefaultModelRouter;
import io.github.bangshou1st.modelbridge.core.config.ModelDefinition;
import io.github.bangshou1st.modelbridge.core.config.ProviderDefinition;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class StreamCancellationIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cancelBeforeResponseHeadersCancelsTheUnderlyingRequest() throws Exception {
        try (Upstream upstream = Upstream.start(Phase.BEFORE_HEADERS)) {
            DefaultModelRouter router = router(upstream);

            StreamHandle handle = router.chat().stream(
                    ChatRequest.builder("cancel-model").user("wait for headers").build(),
                    chunk -> {
                    });

            assertThat(upstream.requestReceived.await(3, TimeUnit.SECONDS)).isTrue();
            handle.cancel();
            handle.await();

            assertThat(upstream.awaitUnderlyingCancellation())
                    .as("server observed the cancelled request before headers")
                    .isTrue();
        }
    }

    @Test
    void cancelAfterFirstFrameClosesTheUnderlyingResponseBody() throws Exception {
        try (Upstream upstream = Upstream.start(Phase.AFTER_FIRST_FRAME)) {
            DefaultModelRouter router = router(upstream);
            CountDownLatch firstContent = new CountDownLatch(1);

            StreamHandle handle = router.chat().stream(
                    ChatRequest.builder("cancel-model").user("read one frame").build(),
                    chunk -> {
                        if (chunk.type() == StreamChunk.Type.CONTENT) {
                            firstContent.countDown();
                        }
                    });

            assertThat(firstContent.await(3, TimeUnit.SECONDS)).isTrue();
            handle.cancel();
            handle.await();

            assertThat(upstream.awaitUnderlyingCancellation())
                    .as("server observed the closed response body")
                    .isTrue();
        }
    }

    private DefaultModelRouter router(Upstream upstream) {
        BridgeConfig config = new BridgeConfig();
        config.setDefaultChatModel("cancel-model");
        config.getDefaults().getRetry().setMaxAttempts(2);
        config.getDefaults().getRetry().setInitialBackoff(Duration.ofMillis(10));
        ProviderDefinition provider = new ProviderDefinition();
        provider.setProtocol("openai-compatible");
        provider.setBaseUrl(upstream.baseUrl());
        provider.setApiKey("test-key");
        provider.getModels().add(new ModelDefinition("cancel-model"));
        config.getProviders().put("mock", provider);

        OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(
                Set.of(upstream.port()));
        HttpTransport transport = new HttpTransport(mapper, policy);
        AdapterRegistry registry = AdapterRegistry.withBuiltIns(mapper, transport);
        return new DefaultModelRouter(config, registry, new ModelBridgeClient(registry),
                new ModelDiscoveryClient(mapper, transport, registry), mapper);
    }

    private enum Phase {
        BEFORE_HEADERS,
        AFTER_FIRST_FRAME
    }

    private static final class Upstream implements AutoCloseable {
        private final HttpServer server;
        private final CountDownLatch requestReceived = new CountDownLatch(1);
        private final AtomicBoolean underlyingCancelled = new AtomicBoolean();

        private Upstream(HttpServer server, Phase phase) {
            this.server = server;
            server.createContext("/v1/chat/completions", exchange -> {
                requestReceived.countDown();
                try {
                    if (phase == Phase.BEFORE_HEADERS) {
                        Thread.sleep(400);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream output = exchange.getResponseBody()) {
                        writeContent(output, "first");
                        for (int frame = 0; frame < 500; frame++) {
                            writeContent(output, "tick");
                            Thread.sleep(20);
                        }
                    }
                } catch (IOException cancelled) {
                    underlyingCancelled.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
        }

        static Upstream start(Phase phase) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Upstream upstream = new Upstream(server, phase);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return upstream;
        }

        int port() {
            return server.getAddress().getPort();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        boolean awaitUnderlyingCancellation() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!underlyingCancelled.get() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            return underlyingCancelled.get();
        }

        private static void writeContent(OutputStream output, String content) throws IOException {
            String event = "data: {\"model\":\"cancel-model\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"content\":\"" + content + "\"}}]}\n\n";
            output.write(event.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
