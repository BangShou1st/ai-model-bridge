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

public final class GeminiEmbeddingAdapter implements EmbeddingProtocolAdapter {
    private final ObjectMapper mapper;
    private final HttpTransport http;

    public GeminiEmbeddingAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "gemini-embedding";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.GEMINI);
    }

    @Override
    public EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request) {
        long started = System.nanoTime();
        String model = request.model().startsWith("models/")
                ? request.model().substring("models/".length()) : request.model();
        String endpoint = Endpoints.endpoint(provider, "embeddingsPath", "/v1beta",
                "/models/" + model + ":batchEmbedContents");
        ObjectNode body = mapper.createObjectNode();
        ArrayNode requests = body.putArray("requests");
        for (String input : request.inputs()) {
            ObjectNode item = requests.addObject();
            item.put("model", "models/" + model);
            item.putObject("content").putArray("parts").addObject().put("text", input);
            item.put("taskType", Endpoints.option(provider, "embeddingTaskType",
                    "RETRIEVAL_DOCUMENT"));
            if (request.dimensions() != null) {
                item.put("outputDimensionality", request.dimensions());
            }
        }
        JsonNode values = http.postJson(provider, endpoint,
                EmbeddingSupport.headers(provider, false), body).path("embeddings");
        if (!values.isArray() || values.size() != request.inputs().size()) {
            throw ModelBridgeException.schema(
                    "Gemini embedding count does not match input count", provider.id());
        }
        List<List<Double>> embeddings = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            embeddings.add(EmbeddingSupport.vector(value.path("values"), provider.id()));
        }
        return new EmbeddingResult(request.model(), provider.id(), embeddings,
                TokenUsage.empty(), EmbeddingSupport.elapsed(started));
    }
}
