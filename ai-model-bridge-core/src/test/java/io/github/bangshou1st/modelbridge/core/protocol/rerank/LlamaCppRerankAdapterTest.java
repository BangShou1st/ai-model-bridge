package io.github.bangshou1st.modelbridge.core.protocol.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.core.testsupport.LocalFeatureServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlamaCppRerankAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestsAllCandidatesAndSortsReturnedMatches() throws Exception {
        AtomicReference<JsonNode> seenBody = new AtomicReference<>();
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> {
            seenBody.set(mapper.readTree(request.body()));
            Thread.sleep(20);
            return new LocalFeatureServer.Response(200, "application/json",
                    """
                    {"model":"bge-reranker","results":[
                      {"index":2,"relevance_score":0.9},
                      {"index":0,"relevance_score":0.2},
                      {"index":1,"relevance_score":0.5}
                    ],"usage":{"prompt_tokens":9,"total_tokens":9}}
                    """);
        })) {
            LlamaCppRerankAdapter adapter = adapter(server);
            RerankResult result = adapter.rerank(provider(server),
                    new RerankRequest("bge-reranker", "query", List.of("a", "b", "c"), 1, Map.of()));

            assertThat(seenBody.get().path("top_n").asInt()).isEqualTo(3);
            assertThat(seenBody.get().path("documents")).hasSize(3);
            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().getFirst().index()).isEqualTo(2);
            assertThat(result.matches().getFirst().score()).isEqualTo(0.9);
            assertThat(result.usage().inputTokens()).isEqualTo(9);
            assertThat(result.latencyMs()).isGreaterThanOrEqualTo(10);
            assertThat(server.requests().getFirst().path()).isEqualTo("/v1/rerank");
            assertThat(server.requests().getFirst().header("authorization")).isEqualTo("Bearer secret");
        }
    }

    @Test
    void rejectsIncompleteCandidateSet() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                "{\"results\":[{\"index\":0,\"score\":0.1},{\"index\":1,\"score\":0.2}]}"))) {
            LlamaCppRerankAdapter adapter = adapter(server);
            assertThatThrownBy(() -> adapter.rerank(provider(server),
                    new RerankRequest("m", "query", List.of("a", "b", "c"))))
                    .isInstanceOf(ModelBridgeException.class)
                    .satisfies(error -> assertThat(((ModelBridgeException) error).category())
                            .isEqualTo(ErrorCategory.SCHEMA));
        }
    }

    @Test
    void rejectsDuplicateIndexesAndNonFiniteScores() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                "{\"results\":[{\"index\":0,\"score\":\"NaN\"},{\"index\":0,\"score\":0.2},"
                        + "{\"index\":1,\"score\":0.3}]}"))) {
            LlamaCppRerankAdapter adapter = adapter(server);
            assertThatThrownBy(() -> adapter.rerank(provider(server),
                    new RerankRequest("m", "query", List.of("a", "b", "c"))))
                    .isInstanceOf(ModelBridgeException.class)
                    .satisfies(error -> assertThat(((ModelBridgeException) error).category())
                            .isEqualTo(ErrorCategory.SCHEMA));
        }
    }

    private LlamaCppRerankAdapter adapter(LocalFeatureServer server) {
        OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(
                java.util.Set.of(server.port()));
        return new LlamaCppRerankAdapter(mapper, new HttpTransport(mapper, policy));
    }

    private static ProviderConfig provider(LocalFeatureServer server) {
        return new ProviderConfig("llama", ProviderType.LLAMA_CPP, server.baseUrl(), "secret",
                Map.of(), Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of());
    }
}
