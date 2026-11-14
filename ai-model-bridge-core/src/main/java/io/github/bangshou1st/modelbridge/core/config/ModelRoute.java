package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;

public record ModelRoute(
        String reference,
        RouteTarget target,
        ProviderConfig provider,
        ModelDefinition model,
        ModelDescriptor descriptor,
        ChatOptions defaults,
        boolean alias
) {
}
