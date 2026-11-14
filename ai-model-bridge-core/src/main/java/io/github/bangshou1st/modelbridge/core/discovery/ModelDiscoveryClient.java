package io.github.bangshou1st.modelbridge.core.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;

import java.util.List;
import java.util.Objects;

/** Discovery facade backed exclusively by the open {@code ModelDiscoveryAdapter} SPI. */
public final class ModelDiscoveryClient {
    private final AdapterRegistry registry;

    public ModelDiscoveryClient(ObjectMapper mapper, HttpTransport http) {
        this(mapper, http, AdapterRegistry.withBuiltIns(mapper, http).loadPlugins());
    }

    public ModelDiscoveryClient(ObjectMapper mapper, HttpTransport http, AdapterRegistry registry) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(http, "http");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public List<ModelDescriptor> discover(ProviderConfig provider) {
        return registry.discovery(provider.protocol()).discover(provider);
    }

    public AdapterRegistry registry() {
        return registry;
    }
}
