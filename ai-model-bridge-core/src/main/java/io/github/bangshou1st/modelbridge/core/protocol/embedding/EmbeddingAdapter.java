package io.github.bangshou1st.modelbridge.core.protocol.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.EmbeddingProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;

import java.util.Map;
import java.util.Set;

/**
 * Backward-compatible facade over the split embedding adapters. New code can register and use the
 * protocol-specific implementations directly.
 */
@Deprecated(since = "0.2.0", forRemoval = false)
public final class EmbeddingAdapter implements EmbeddingProtocolAdapter {
    private final Map<ProtocolId, EmbeddingProtocolAdapter> delegates;

    public EmbeddingAdapter(ObjectMapper mapper, HttpTransport http) {
        this.delegates = Map.of(
                ProtocolId.OPENAI_COMPATIBLE, new OpenAiEmbeddingAdapter(mapper, http),
                ProtocolId.OLLAMA, new OllamaEmbeddingAdapter(mapper, http),
                ProtocolId.GEMINI, new GeminiEmbeddingAdapter(mapper, http));
    }

    @Override
    public String name() {
        return "embedding-compatibility-facade";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return delegates.keySet();
    }

    @Override
    public EmbeddingResult embed(ProviderConfig provider, EmbeddingRequest request) {
        EmbeddingProtocolAdapter adapter = delegates.get(provider.protocol());
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "embedding is not supported for " + provider.protocol());
        }
        return adapter.embed(provider, request);
    }
}
