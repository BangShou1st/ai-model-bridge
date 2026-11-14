package io.github.bangshou1st.modelbridge.core.protocol.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;

import java.util.List;
import java.util.Set;

public final class OllamaModelDiscoveryAdapter extends AbstractModelDiscoveryAdapter {
    public OllamaModelDiscoveryAdapter(ObjectMapper mapper, HttpTransport http) {
        super(mapper, http);
    }

    @Override
    public String name() {
        return "ollama-model-discovery";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.OLLAMA);
    }

    @Override
    public List<ModelDescriptor> discover(ProviderConfig provider) {
        return discoverOllama(provider);
    }
}
