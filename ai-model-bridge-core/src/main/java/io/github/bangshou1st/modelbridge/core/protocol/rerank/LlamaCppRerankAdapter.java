package io.github.bangshou1st.modelbridge.core.protocol.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankMatch;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.protocol.RerankProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LlamaCppRerankAdapter implements RerankProtocolAdapter {
    private final ObjectMapper mapper;
    private final HttpTransport http;

    public LlamaCppRerankAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "llama-cpp-rerank";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.LLAMA_CPP);
    }

    @Override
    public RerankResult rerank(ProviderConfig provider, RerankRequest request) {
        long started = System.nanoTime();
        ObjectNode body = mapper.createObjectNode()
                .put("model", request.model())
                .put("query", request.query());
        ArrayNode documents = body.putArray("documents");
        request.documents().forEach(documents::add);
        body.put("top_n", request.documents().size());

        JsonNode response = http.postJson(provider,
                Endpoints.endpoint(provider, "rerankPath", "/v1", "/rerank"),
                headers(provider), body);
        JsonNode values = response.has("results") ? response.path("results") : response.path("data");
        if (!values.isArray() || values.size() != request.documents().size()) {
            throw ModelBridgeException.schema("rerank response must contain every candidate exactly once",
                    provider.id());
        }
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (JsonNode item : values) {
            int index = item.path("index").asInt(-1);
            JsonNode scoreNode = item.has("relevance_score")
                    ? item.path("relevance_score") : item.path("score");
            if (index < 0 || index >= request.documents().size() || scores.containsKey(index)
                    || !scoreNode.isNumber()) {
                throw ModelBridgeException.schema("rerank response has an invalid or duplicate index/score",
                        provider.id());
            }
            double score = scoreNode.asDouble();
            if (!Double.isFinite(score)) {
                throw ModelBridgeException.schema("rerank response has a non-finite score", provider.id());
            }
            scores.put(index, score);
        }
        if (scores.size() != request.documents().size()) {
            throw ModelBridgeException.schema("rerank response is incomplete", provider.id());
        }
        List<RerankMatch> matches = new ArrayList<>(scores.size());
        scores.forEach((index, score) -> matches.add(new RerankMatch(index, score)));
        matches.sort(Comparator.comparingDouble(RerankMatch::score).reversed()
                .thenComparingInt(RerankMatch::index));
        List<RerankMatch> selected = request.topN() == null
                ? matches : matches.subList(0, request.topN());
        TokenUsage usage = usage(response.path("usage"));
        String model = response.path("model").asText(request.model());
        return new RerankResult(model, provider.id(), List.copyOf(selected), usage,
                Math.max(0, (System.nanoTime() - started) / 1_000_000));
    }

    private static TokenUsage usage(JsonNode node) {
        if (!node.isObject()) {
            return TokenUsage.empty();
        }
        Integer input = node.path("prompt_tokens").isNumber() ? node.path("prompt_tokens").asInt() : null;
        Integer output = node.path("completion_tokens").isNumber() ? node.path("completion_tokens").asInt() : null;
        Integer total = node.path("total_tokens").isNumber() ? node.path("total_tokens").asInt() : null;
        return new TokenUsage(input, output, total, null, null);
    }

    private static Map<String, String> headers(ProviderConfig provider) {
        Map<String, String> headers = new LinkedHashMap<>(provider.headers());
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            headers.putIfAbsent("Authorization", "Bearer " + provider.apiKey());
        }
        return headers;
    }
}
