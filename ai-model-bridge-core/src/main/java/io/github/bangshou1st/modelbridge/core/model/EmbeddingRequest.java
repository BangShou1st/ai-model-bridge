package io.github.bangshou1st.modelbridge.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EmbeddingRequest(
        String model,
        List<String> inputs,
        Integer dimensions,
        Map<String, Object> providerOptions
) {
    public EmbeddingRequest {
        Objects.requireNonNull(model, "model");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one embedding input is required");
        }
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(providerOptions));
    }

    public EmbeddingRequest(String model, List<String> inputs) {
        this(model, inputs, null, Map.of());
    }

    public EmbeddingRequest withModel(String upstreamModel) {
        return new EmbeddingRequest(upstreamModel, inputs, dimensions, providerOptions);
    }
}
