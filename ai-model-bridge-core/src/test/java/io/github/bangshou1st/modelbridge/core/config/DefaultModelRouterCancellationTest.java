package io.github.bangshou1st.modelbridge.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.client.DefaultStreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ChatProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultModelRouterCancellationTest {
    @Test
    void forwardsCancellationToAnAdapterHandleCreatedAfterTheOuterHandleWasCancelled()
            throws Exception {
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch releaseInvocation = new CountDownLatch(1);
        DefaultStreamHandle inner = new DefaultStreamHandle();
        ProtocolId protocol = ProtocolId.of("blocking-test");
        ChatProtocolAdapter adapter = new ChatProtocolAdapter() {
            @Override
            public String name() {
                return protocol.value();
            }

            @Override
            public Set<ProtocolId> protocols() {
                return Set.of(protocol);
            }

            @Override
            public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void completeStream(ProviderConfig provider, ChatRequest request,
                                       Consumer<StreamChunk> consumer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamHandle stream(ProviderConfig provider, ChatRequest request,
                                       Consumer<StreamChunk> consumer) {
                invocationStarted.countDown();
                await(releaseInvocation);
                return inner;
            }
        };

        ObjectMapper mapper = new ObjectMapper();
        AdapterRegistry registry = new AdapterRegistry().register(adapter);
        HttpTransport transport = new HttpTransport(mapper, OutboundEndpointPolicy.httpsOnly());
        ModelDiscoveryClient discovery = new ModelDiscoveryClient(mapper, transport, registry);
        BridgeConfig config = new BridgeConfig();
        config.setDefaultChatModel("late-model");
        ProviderDefinition provider = new ProviderDefinition();
        provider.setProtocol(protocol.value());
        provider.setBaseUrl("https://unused.example/v1");
        provider.getModels().add(new ModelDefinition("late-model"));
        config.getProviders().put("blocking", provider);
        DefaultModelRouter router = new DefaultModelRouter(config, registry,
                new ModelBridgeClient(registry), discovery, mapper);

        StreamHandle outer = router.chat().stream(
                ChatRequest.builder("late-model").user("cancel while opening").build(),
                chunk -> {
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
                    .as("the router forwards cancellation to a late-created adapter handle")
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
