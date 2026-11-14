package io.github.bangshou1st.modelbridge.core.protocol.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.protocol.EmbeddingProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EmbeddingAdapter implements EmbeddingProtocolAdapter {
    private final ObjectMapper mapper;
    private final HttpTransport http;

    public EmbeddingAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public Set<ProviderType> providerTypes() {
        return Set.of(ProviderType.OPENAI_COMPATIBLE, ProviderType.OLLAMA);
    }

    @Override
    public EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request) {
        long started = System.nanoTime();
        return switch (provider.type()) {
            case OPENAI_COMPATIBLE -> openAi(provider, request, started);
            case OLLAMA -> ollama(provider, request, started);
            default -> throw new ModelBridgeException(ErrorCategory.UNSUPPORTED,
                    "embedding is not supported for " + provider.type(), provider.id());
        };
    }

    private EmbeddingResult openAi(ProviderConfig provider, EmbeddingRequest request, long started) {
        String endpoint = Endpoints.endpoint(provider, "embeddingsPath", "/v1", "/embeddings");
        ObjectNode body = mapper.createObjectNode().put("model", request.model());
        ArrayNode input = body.putArray("input");
        request.inputs().forEach(input::add);
        if (request.dimensions() != null) {
            body.put("dimensions", request.dimensions());
        }
        JsonNode response = http.postJson(provider, endpoint, headers(provider, true), body);
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty() || data.size() != request.inputs().size()) {
            throw ModelBridgeException.schema("embedding response count does not match input count",
                    provider.id());
        }
        List<List<Double>> embeddings = new ArrayList<>(request.inputs().size());
        for (int i = 0; i < request.inputs().size(); i++) {
            embeddings.add(null);
        }
        boolean[] seen = new boolean[request.inputs().size()];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= seen.length || seen[index]) {
                throw ModelBridgeException.schema("embedding indexes are missing, duplicated or out of range",
                        provider.id());
            }
            seen[index] = true;
            embeddings.set(index, vector(item.path("embedding"), provider.id()));
        }
        for (boolean value : seen) {
            if (!value) {
                throw ModelBridgeException.schema("embedding response is incomplete", provider.id());
            }
        }
        TokenUsage usage = usage(response.path("usage"), null);
        String model = response.path("model").asText(request.model());
        return new EmbeddingResult(model, provider.id(), embeddings, usage, elapsed(started));
    }

    private EmbeddingResult ollama(ProviderConfig provider, EmbeddingRequest request, long started) {
        String endpoint = Endpoints.endpoint(provider, "embeddingsPath", "/api", "/embed");
        ObjectNode body = mapper.createObjectNode().put("model", request.model());
        ArrayNode input = body.putArray("input");
        request.inputs().forEach(input::add);
        try {
            JsonNode response = http.postJson(provider, endpoint, headers(provider, false), body);
            return parseOllama(provider, request, response, started);
        } catch (ModelBridgeException e) {
            if (e.upstreamStatus() != 404) {
                throw e;
            }
            return ollamaLegacy(provider, request, started);
        }
    }

    private EmbeddingResult ollamaLegacy(ProviderConfig provider, EmbeddingRequest request, long started) {
        String endpoint = Endpoints.endpoint(provider, "embeddingsLegacyPath", "/api", "/embeddings");
        List<List<Double>> embeddings = new ArrayList<>();
        int inputTokens = 0;
        boolean hasUsage = false;
        for (String input : request.inputs()) {
            ObjectNode body = mapper.createObjectNode()
                    .put("model", request.model())
                    .put("prompt", input);
            JsonNode response = http.postJson(provider, endpoint, headers(provider, false), body);
            JsonNode vector = response.path("embedding");
            embeddings.add(vector(vector, provider.id()));
            Integer tokens = integer(response.path("prompt_eval_count"));
            if (tokens != null) {
                inputTokens += tokens;
                hasUsage = true;
            }
        }
        TokenUsage usage = hasUsage
                ? new TokenUsage(inputTokens, null, inputTokens, null, null)
                : TokenUsage.empty();
        return new EmbeddingResult(request.model(), provider.id(), embeddings, usage, elapsed(started));
    }

    private EmbeddingResult parseOllama(ProviderConfig provider, EmbeddingRequest request,
                                        JsonNode response, long started) {
        JsonNode values = response.path("embeddings");
        if (!values.isArray() || values.size() != request.inputs().size()) {
            throw ModelBridgeException.schema("Ollama embedding count does not match input count",
                    provider.id());
        }
        List<List<Double>> embeddings = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            embeddings.add(vector(value, provider.id()));
        }
        Integer inputTokens = integer(response.path("prompt_eval_count"));
        TokenUsage usage = inputTokens == null
                ? TokenUsage.empty()
                : new TokenUsage(inputTokens, null, inputTokens, null, null);
        return new EmbeddingResult(response.path("model").asText(request.model()), provider.id(),
                embeddings, usage, elapsed(started));
    }

    private List<Double> vector(JsonNode node, String provider) {
        if (!node.isArray() || node.isEmpty()) {
            throw ModelBridgeException.schema("embedding vector is empty or missing", provider);
        }
        List<Double> result = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            if (!value.isNumber()) {
                throw ModelBridgeException.schema("embedding vector contains a non-numeric value", provider);
            }
            double number = value.asDouble();
            if (!Double.isFinite(number)) {
                throw ModelBridgeException.schema("embedding vector contains a non-finite value", provider);
            }
            result.add(number);
        }
        return List.copyOf(result);
    }

    private static TokenUsage usage(JsonNode usage, Integer fallbackInput) {
        Integer input = usage.path("prompt_tokens").isNumber()
                ? usage.path("prompt_tokens").asInt() : fallbackInput;
        Integer total = usage.path("total_tokens").isNumber()
                ? usage.path("total_tokens").asInt() : input;
        return input == null && total == null
                ? TokenUsage.empty()
                : new TokenUsage(input, null, total, null, null);
    }

    private static Map<String, String> headers(ProviderConfig provider, boolean bearer) {
        Map<String, String> headers = new LinkedHashMap<>(provider.headers());
        if (bearer && provider.apiKey() != null && !provider.apiKey().isBlank()) {
            headers.putIfAbsent("Authorization", "Bearer " + provider.apiKey());
        }
        return headers;
    }

    private static Integer integer(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
