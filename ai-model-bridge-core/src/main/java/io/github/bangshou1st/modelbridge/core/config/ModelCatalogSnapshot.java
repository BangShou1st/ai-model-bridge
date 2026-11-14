package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;

import java.util.List;
import java.util.Map;

public record ModelCatalogSnapshot(
        List<ModelDescriptor> models,
        Map<String, List<RouteTarget>> aliases
) {
    public ModelCatalogSnapshot {
        models = models == null ? List.of() : List.copyOf(models);
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
    }
}
