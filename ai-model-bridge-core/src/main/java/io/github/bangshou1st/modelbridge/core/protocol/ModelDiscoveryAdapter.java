package io.github.bangshou1st.modelbridge.core.protocol;

import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;

import java.util.List;
import java.util.Set;

/** Open SPI for provider model-catalog discovery. */
public interface ModelDiscoveryAdapter {
    String name();

    Set<ProtocolId> protocols();

    List<ModelDescriptor> discover(ProviderConfig provider);
}
