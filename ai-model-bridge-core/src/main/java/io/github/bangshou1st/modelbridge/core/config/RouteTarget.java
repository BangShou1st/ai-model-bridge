package io.github.bangshou1st.modelbridge.core.config;

import java.util.Objects;

public record RouteTarget(String providerId, String modelId) {
    public RouteTarget {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
    }

    public static RouteTarget parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("route target must not be blank");
        }
        int slash = value.indexOf('/');
        if (slash <= 0 || slash >= value.length() - 1) {
            throw new IllegalArgumentException("route target must use provider/model: " + value);
        }
        return new RouteTarget(value.substring(0, slash), value.substring(slash + 1));
    }

    public String id() {
        return providerId + "/" + modelId;
    }
}
