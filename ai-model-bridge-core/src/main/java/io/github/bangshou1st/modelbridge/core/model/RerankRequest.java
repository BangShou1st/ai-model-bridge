package io.github.bangshou1st.modelbridge.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RerankRequest(
        String model,
        String query,
        List<String> documents,
        Integer topN,
        Map<String, Object> providerOptions
) {
    public RerankRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(query, "query");
        documents = documents == null ? List.of() : List.copyOf(documents);
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("at least one document is required");
        }
        if (topN != null && (topN < 1 || topN > documents.size())) {
            throw new IllegalArgumentException("topN must be between 1 and document count");
        }
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(providerOptions));
    }

    public RerankRequest(String model, String query, List<String> documents) {
        this(model, query, documents, null, Map.of());
    }

    public RerankRequest withModel(String upstreamModel) {
        return new RerankRequest(upstreamModel, query, documents, topN, providerOptions);
    }
}
