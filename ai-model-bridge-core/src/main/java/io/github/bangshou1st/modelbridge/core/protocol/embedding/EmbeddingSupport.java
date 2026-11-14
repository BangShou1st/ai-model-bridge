package io.github.bangshou1st.modelbridge.core.protocol.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EmbeddingSupport {
    private EmbeddingSupport() {
    }

    static List<Double> vector(JsonNode node, String provider) {
        if (!node.isArray() || node.isEmpty()) {
            throw ModelBridgeException.schema("embedding vector is empty or missing", provider);
        }
        List<Double> result = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            if (!value.isNumber()) {
                throw ModelBridgeException.schema("embedding vector contains a non-numeric value", provider);
            }
            double number = value.asDouble();
            if (!Double.isFinite(number)) {
                throw ModelBridgeException.schema("embedding vector contains a non-finite value", provider);
            }
            result.add(number);
        }
        return List.copyOf(result);
    }

    static TokenUsage usage(JsonNode usage, Integer fallbackInput) {
        Integer input = usage.path("prompt_tokens").isNumber()
                ? usage.path("prompt_tokens").asInt() : fallbackInput;
        Integer total = usage.path("total_tokens").isNumber()
                ? usage.path("total_tokens").asInt() : input;
        return input == null && total == null
                ? TokenUsage.empty()
                : new TokenUsage(input, null, total, null, null);
    }

    static Map<String, String> headers(ProviderConfig provider, boolean bearer) {
        Map<String, String> headers = new LinkedHashMap<>(provider.headers());
        if (bearer && !provider.apiKey().isBlank()) {
            headers.putIfAbsent("Authorization", "Bearer " + provider.apiKey());
        }
        if (ProviderType.GEMINI.toProtocolId().equals(provider.protocol())
                && !provider.apiKey().isBlank()) {
            headers.putIfAbsent("x-goog-api-key", provider.apiKey());
        }
        return headers;
    }

    static Integer integer(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
