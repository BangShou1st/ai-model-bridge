package io.github.bangshou1st.modelbridge.core.protocol.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.core.testsupport.LocalFeatureServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openAiCompatibleParsesOutOfOrderIndexesAndForwardsAuthAndDimensions() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                """
                {"object":"list","model":"embed-v1","data":[
                  {"object":"embedding","index":1,"embedding":[0.2,0.3]},
                  {"object":"embedding","index":0,"embedding":[0.1,0.2]}
                ],"usage":{"prompt_tokens":4,"total_tokens":4}}
                """))) {
            EmbeddingAdapter adapter = adapter(server);
            ProviderConfig provider = provider(server, ProviderType.OPENAI_COMPATIBLE, "secret");
            EmbeddingResult result = adapter.embed(provider,
                    new EmbeddingRequest("embed-v1", List.of("first", "second"), 2, Map.of()));

            assertThat(result.embeddings()).containsExactly(
                    List.of(0.1, 0.2), List.of(0.2, 0.3));
            assertThat(result.usage().inputTokens()).isEqualTo(4);
            assertThat(result.usage().totalTokens()).isEqualTo(4);
            assertThat(result.provider()).isEqualTo("upstream");
            assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);

            LocalFeatureServer.Request request = server.requests().getFirst();
            assertThat(request.path()).isEqualTo("/v1/embeddings");
            assertThat(request.header("authorization")).isEqualTo("Bearer secret");
            JsonNode body = mapper.readTree(request.body());
            assertThat(body.path("model").asText()).isEqualTo("embed-v1");
            assertThat(body.path("dimensions").asInt()).isEqualTo(2);
            assertThat(body.path("input").isArray()).isTrue();
            assertThat(body.path("input").get(0).asText()).isEqualTo("first");
        }
    }

    @Test
    void openAiCompatibleRejectsIncompleteOrDuplicateEmbeddingIndexes() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                """
                {"model":"embed","data":[{"index":0,"embedding":[0.1]}]}
                """))) {
            EmbeddingAdapter adapter = adapter(server);
            ProviderConfig provider = provider(server, ProviderType.OPENAI_COMPATIBLE, "secret");
            assertThatThrownBy(() -> adapter.embed(provider,
                    new EmbeddingRequest("embed", List.of("a", "b"))))
                    .isInstanceOf(ModelBridgeException.class)
                    .satisfies(error -> assertThat(((ModelBridgeException) error).category())
                            .isEqualTo(io.github.bangshou1st.modelbridge.core.security.ErrorCategory.SCHEMA));
        }
    }

    @Test
    void ollamaEmbedsAllInputsAtApiEmbed() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                """
                {"model":"nomic-embed-text","embeddings":[[1.0,2.0],[3.0,4.0]],
                 "prompt_eval_count":7}
                """))) {
            EmbeddingAdapter adapter = adapter(server);
            ProviderConfig provider = new ProviderConfig("ollama", ProviderType.OLLAMA,
                    server.baseUrl(), "", Map.of(), Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of());
            EmbeddingResult result = adapter.embed(provider,
                    new EmbeddingRequest("nomic-embed-text", List.of("a", "b")));

            assertThat(result.embeddings()).containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
            assertThat(result.usage().inputTokens()).isEqualTo(7);
            assertThat(server.requests().getFirst().path()).isEqualTo("/api/embed");
            assertThat(mapper.readTree(server.requests().getFirst().body()).path("input").size())
                    .isEqualTo(2);
        }
    }

    @Test
    void ollamaFallsBackToLegacyEndpointOn404() throws Exception {
        AtomicInteger legacyCalls = new AtomicInteger();
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> {
            if ("/api/embed".equals(request.path())) {
                return new LocalFeatureServer.Response(404, "text/plain", "not found");
            }
            legacyCalls.incrementAndGet();
            String prompt = mapper.readTree(request.body()).path("prompt").asText();
            String vector = "first".equals(prompt) ? "[0.1,0.2]" : "[0.3,0.4]";
            return new LocalFeatureServer.Response(200, "application/json",
                    "{\"embedding\":" + vector + ",\"prompt_eval_count\":3}");
        })) {
            EmbeddingAdapter adapter = adapter(server);
            ProviderConfig provider = new ProviderConfig("ollama", ProviderType.OLLAMA,
                    server.baseUrl(), "", Map.of(), Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of());
            EmbeddingResult result = adapter.embed(provider,
                    new EmbeddingRequest("legacy", List.of("first", "second")));

            assertThat(result.embeddings()).containsExactly(List.of(0.1, 0.2), List.of(0.3, 0.4));
            assertThat(result.usage().inputTokens()).isEqualTo(6);
            assertThat(legacyCalls.get()).isEqualTo(2);
            assertThat(server.requests().stream().filter(r -> r.path().equals("/api/embeddings")).count())
                    .isEqualTo(2);
        }
    }

    @Test
    void geminiUsesBatchEmbedContentsAndApiKeyHeader() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                """
                {"embeddings":[{"values":[0.1,0.2]},{"values":[0.3,0.4]}]}
                """))) {
            EmbeddingAdapter adapter = adapter(server);
            ProviderConfig provider = provider(server, ProviderType.GEMINI, "gemini-secret");
            EmbeddingResult result = adapter.embed(provider,
                    new EmbeddingRequest("gemini-embedding-001", List.of("a", "b"), 2, Map.of()));

            assertThat(result.embeddings()).containsExactly(List.of(0.1, 0.2), List.of(0.3, 0.4));
            LocalFeatureServer.Request request = server.requests().getFirst();
            assertThat(request.path())
                    .isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
            assertThat(request.header("x-goog-api-key")).isEqualTo("gemini-secret");
            JsonNode body = mapper.readTree(request.body());
            assertThat(body.path("requests")).hasSize(2);
            assertThat(body.path("requests").get(0).path("model").asText())
                    .isEqualTo("models/gemini-embedding-001");
            assertThat(body.path("requests").get(0).path("content").path("parts").get(0)
                    .path("text").asText()).isEqualTo("a");
            assertThat(body.path("requests").get(0).path("outputDimensionality").asInt())
                    .isEqualTo(2);
        }
    }

    private EmbeddingAdapter adapter(LocalFeatureServer server) {
        OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(
                java.util.Set.of(server.port()));
        return new EmbeddingAdapter(mapper, new HttpTransport(mapper, policy));
    }

    private static ProviderConfig provider(LocalFeatureServer server, ProviderType type, String key) {
        return new ProviderConfig("upstream", type, server.baseUrl(), key, Map.of(),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of());
    }
}
