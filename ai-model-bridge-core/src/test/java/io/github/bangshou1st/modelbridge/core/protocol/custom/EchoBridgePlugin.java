package io.github.bangshou1st.modelbridge.core.protocol.custom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.protocol.BridgePlugin;
import io.github.bangshou1st.modelbridge.core.protocol.ChatProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.EmbeddingProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ModelDiscoveryAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Test-only third-party protocol that proves the SPI needs no ProviderType enum change. */
public final class EchoBridgePlugin implements BridgePlugin {
    private static final ProtocolId ECHO = ProtocolId.of("echo");

    @Override
    public String id() {
        return "echo";
    }

    @Override
    public List<Object> adapters() {
        return List.of(new EchoChatAdapter(), new EchoDiscoveryAdapter(), new EchoEmbeddingAdapter());
    }

    private abstract static class EchoAdapter {
        protected final ObjectMapper mapper = new ObjectMapper();
        protected final HttpTransport http = new HttpTransport(mapper,
                OutboundEndpointPolicy.localDevelopment(Set.of()));

        protected Map<String, String> headers(ProviderConfig provider) {
            return provider.headers();
        }
    }

    private static final class EchoChatAdapter extends EchoAdapter implements ChatProtocolAdapter {
        @Override
        public String name() {
            return "echo-chat";
        }

        @Override
        public Set<ProtocolId> protocols() {
            return Set.of(ECHO);
        }

        @Override
        public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
            ObjectNode body = mapper.createObjectNode().put("model", request.model());
            body.putArray("messages").addObject()
                    .put("role", "user").put("content", request.messages().getFirst().content());
            JsonNode response = http.postJson(provider,
                    Endpoints.join(provider.baseUrl(), "/custom/chat"), headers(provider), body);
            return new ChatResponse(response.path("content").asText(), null, List.of(),
                    FinishReason.STOP, TokenUsage.empty(), provider.id(), request.model(), 1,
                    Map.of());
        }

        @Override
        public void completeStream(ProviderConfig provider, ChatRequest request,
                                   Consumer<StreamChunk> consumer) {
            ChatResponse response = complete(provider, request);
            consumer.accept(StreamChunk.content(response.content(), request.model(), provider.id()));
            consumer.accept(StreamChunk.done(FinishReason.STOP, TokenUsage.empty(),
                    request.model(), provider.id()));
        }
    }

    private static final class EchoDiscoveryAdapter extends EchoAdapter
            implements ModelDiscoveryAdapter {
        @Override
        public String name() {
            return "echo-discovery";
        }

        @Override
        public Set<ProtocolId> protocols() {
            return Set.of(ECHO);
        }

        @Override
        public List<ModelDescriptor> discover(ProviderConfig provider) {
            JsonNode root = http.getJson(provider,
                    Endpoints.join(provider.baseUrl(), "/custom/models"), headers(provider));
            List<ModelDescriptor> result = new ArrayList<>();
            root.path("models").forEach(model -> result.add(ModelDescriptor.builder(
                            model.path("id").asText())
                    .provider(provider.id())
                    .contextWindow(model.path("context").asInt())
                    .build()));
            return List.copyOf(result);
        }
    }

    private static final class EchoEmbeddingAdapter extends EchoAdapter
            implements EmbeddingProtocolAdapter {
        @Override
        public String name() {
            return "echo-embedding";
        }

        @Override
        public Set<ProtocolId> protocols() {
            return Set.of(ECHO);
        }

        @Override
        public EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request) {
            ObjectNode body = mapper.createObjectNode().put("model", request.model());
            body.putArray("input").add(request.inputs().getFirst());
            JsonNode response = http.postJson(provider,
                    Endpoints.join(provider.baseUrl(), "/custom/embed"), headers(provider), body);
            List<Double> vector = new ArrayList<>();
            response.path("vector").forEach(value -> vector.add(value.asDouble()));
            return new EmbeddingResult(request.model(), provider.id(), List.of(vector),
                    TokenUsage.empty(), 1);
        }
    }
}
