package io.github.bangshou1st.modelbridge.core.protocol.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;

import java.util.List;
import java.util.Set;

public final class GeminiModelDiscoveryAdapter extends AbstractModelDiscoveryAdapter {
    public GeminiModelDiscoveryAdapter(ObjectMapper mapper, HttpTransport http) {
        super(mapper, http);
    }

    @Override
    public String name() {
        return "gemini-model-discovery";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.GEMINI);
    }

    @Override
    public List<ModelDescriptor> discover(ProviderConfig provider) {
        return discoverGemini(provider);
    }
}
