package io.github.bangshou1st.modelbridge.core.protocol.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.core.testsupport.LocalFeatureServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PluginSpiTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serviceLoaderPluginProvidesChatDiscoveryAndEmbeddingWithoutEnumChanges()
            throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> switch (request.path()) {
            case "/custom/chat" -> new LocalFeatureServer.Response(200, "application/json",
                    "{\"content\":\"echo answer\"}");
            case "/custom/models" -> new LocalFeatureServer.Response(200, "application/json",
                    "{\"models\":[{\"id\":\"echo-1\",\"context\":4096}]}");
            case "/custom/embed" -> new LocalFeatureServer.Response(200, "application/json",
                    "{\"vector\":[0.25,0.75]}");
            default -> new LocalFeatureServer.Response(404, "application/json", "{}");
        })) {
            OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(
                    Set.of(server.port()));
            HttpTransport transport = new HttpTransport(mapper, policy);
            AdapterRegistry registry = new AdapterRegistry().loadPlugins();
            ModelBridgeClient client = new ModelBridgeClient(registry);
            ModelDiscoveryClient discovery = new ModelDiscoveryClient(mapper, transport, registry);
            ProviderConfig provider = ProviderConfig.builder("echo-provider", ProtocolId.of("echo"))
                    .baseUrl(server.baseUrl())
                    .requestTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            ChatResponse chat = client.complete(provider,
                    ChatRequest.builder("echo-1").user("hello").build());
            EmbeddingResult embedding = client.embed(provider,
                    new EmbeddingRequest("echo-1", List.of("hello")));

            assertThat(registry.supportsChat(ProtocolId.of("echo"))).isTrue();
            assertThat(registry.supportsDiscovery(ProtocolId.of("echo"))).isTrue();
            assertThat(registry.supportsEmbedding(ProtocolId.of("echo"))).isTrue();
            assertThat(chat.content()).isEqualTo("echo answer");
            assertThat(discovery.discover(provider)).singleElement()
                    .satisfies(model -> {
                        assertThat(model.id()).isEqualTo("echo-1");
                        assertThat(model.contextWindow()).isEqualTo(4096);
                    });
            assertThat(embedding.embeddings().getFirst()).containsExactly(0.25, 0.75);
        }
    }
}
