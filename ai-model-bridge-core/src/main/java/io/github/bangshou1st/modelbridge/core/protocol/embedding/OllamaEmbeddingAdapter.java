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
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.protocol.EmbeddingProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class OllamaEmbeddingAdapter implements EmbeddingProtocolAdapter {
    private final ObjectMapper mapper;
    private final HttpTransport http;

    public OllamaEmbeddingAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "ollama-embedding";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.OLLAMA);
    }

    @Override
    public EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request) {
        long started = System.nanoTime();
        String endpoint = Endpoints.endpoint(provider, "embeddingsPath", "/api", "/embed");
        ObjectNode body = mapper.createObjectNode().put("model", request.model());
        ArrayNode input = body.putArray("input");
        request.inputs().forEach(input::add);
        try {
            JsonNode response = http.postJson(provider, endpoint,
                    EmbeddingSupport.headers(provider, false), body);
            return parse(provider, request, response, started);
        } catch (ModelBridgeException e) {
            if (e.upstreamStatus() != 404) {
                throw e;
            }
            return legacy(provider, request, started);
        }
    }

    private EmbeddingResult legacy(ProviderConfig provider, EmbeddingRequest request, long started) {
        String endpoint = Endpoints.endpoint(provider, "embeddingsLegacyPath", "/api", "/embeddings");
        List<List<Double>> embeddings = new ArrayList<>();
        int inputTokens = 0;
        boolean hasUsage = false;
        for (String input : request.inputs()) {
            ObjectNode body = mapper.createObjectNode()
                    .put("model", request.model())
                    .put("prompt", input);
            JsonNode response = http.postJson(provider, endpoint,
                    EmbeddingSupport.headers(provider, false), body);
            embeddings.add(EmbeddingSupport.vector(response.path("embedding"), provider.id()));
            Integer tokens = EmbeddingSupport.integer(response.path("prompt_eval_count"));
            if (tokens != null) {
                inputTokens += tokens;
                hasUsage = true;
            }
        }
        TokenUsage usage = hasUsage
                ? new TokenUsage(inputTokens, null, inputTokens, null, null)
                : TokenUsage.empty();
        return new EmbeddingResult(request.model(), provider.id(), embeddings, usage,
                EmbeddingSupport.elapsed(started));
    }

    private EmbeddingResult parse(ProviderConfig provider, EmbeddingRequest request,
                                  JsonNode response, long started) {
        JsonNode values = response.path("embeddings");
        if (!values.isArray() || values.size() != request.inputs().size()) {
            throw ModelBridgeException.schema("Ollama embedding count does not match input count",
                    provider.id());
        }
        List<List<Double>> embeddings = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            embeddings.add(EmbeddingSupport.vector(value, provider.id()));
        }
        Integer inputTokens = EmbeddingSupport.integer(response.path("prompt_eval_count"));
        TokenUsage usage = inputTokens == null
                ? TokenUsage.empty()
                : new TokenUsage(inputTokens, null, inputTokens, null, null);
        return new EmbeddingResult(response.path("model").asText(request.model()), provider.id(),
                embeddings, usage, EmbeddingSupport.elapsed(started));
    }
}
