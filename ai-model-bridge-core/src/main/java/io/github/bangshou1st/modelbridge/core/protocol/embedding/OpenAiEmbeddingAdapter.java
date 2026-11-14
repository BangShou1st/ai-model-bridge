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

public final class OpenAiEmbeddingAdapter implements EmbeddingProtocolAdapter {
    private final ObjectMapper mapper;
    private final HttpTransport http;

    public OpenAiEmbeddingAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "openai-embedding";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.OPENAI_COMPATIBLE);
    }

    @Override
    public EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request) {
        long started = System.nanoTime();
        String endpoint = Endpoints.endpoint(provider, "embeddingsPath", "/v1", "/embeddings");
        ObjectNode body = mapper.createObjectNode().put("model", request.model());
        ArrayNode input = body.putArray("input");
        request.inputs().forEach(input::add);
        if (request.dimensions() != null) {
            body.put("dimensions", request.dimensions());
        }
        JsonNode response = http.postJson(provider, endpoint,
                EmbeddingSupport.headers(provider, true), body);
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
                throw ModelBridgeException.schema(
                        "embedding indexes are missing, duplicated or out of range", provider.id());
            }
            seen[index] = true;
            embeddings.set(index, EmbeddingSupport.vector(item.path("embedding"), provider.id()));
        }
        for (boolean value : seen) {
            if (!value) {
                throw ModelBridgeException.schema("embedding response is incomplete", provider.id());
            }
        }
        TokenUsage usage = EmbeddingSupport.usage(response.path("usage"), null);
        String model = response.path("model").asText(request.model());
        return new EmbeddingResult(model, provider.id(), embeddings, usage,
                EmbeddingSupport.elapsed(started));
    }
}
