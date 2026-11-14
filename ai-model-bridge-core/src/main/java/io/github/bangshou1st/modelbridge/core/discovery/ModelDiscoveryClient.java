package io.github.bangshou1st.modelbridge.core.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModelDiscoveryClient {
    private static final int MAX_PAGES = 10;
    private static final Set<String> STANDARD_KEYS = Set.of(
            "id", "display_name", "displayName", "owned_by", "ownedBy", "provider", "object",
            "created", "created_at", "context_window", "context_length", "max_input_tokens",
            "max_output_tokens", "tool_call", "supports_tools", "reasoning", "supports_reasoning",
            "reasoning_levels", "supported_reasoning_levels", "supported_parameters",
            "pricing", "modalities", "input_modalities", "output_modalities", "native_endpoints",
            "supports_streaming", "supported_streaming", "supports_structured_output",
            "structured_output", "supports_embeddings", "embeddings", "type", "description",
            "version", "inputTokenLimit", "outputTokenLimit", "supportedGenerationMethods",
            "capabilities", "max_tokens", "context_length_tokens", "name", "model",
            "modified_at", "size", "digest", "details"
    );

    private final ObjectMapper mapper;
    private final HttpTransport http;

    public ModelDiscoveryClient(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    public List<ModelDescriptor> discover(ProviderConfig provider) {
        return switch (provider.type()) {
            case OPENAI_COMPATIBLE, LLAMA_CPP -> discoverOpenAi(provider);
            case ANTHROPIC -> discoverAnthropic(provider);
            case GEMINI -> discoverGemini(provider);
            case OLLAMA -> discoverOllama(provider);
        };
    }

    private List<ModelDescriptor> discoverOpenAi(ProviderConfig provider) {
        String endpoint = Endpoints.endpoint(provider, "modelsPath", "/v1", "/models");
        JsonNode root = http.getJson(provider, endpoint, headers(provider));
        JsonNode data = array(root, "data", provider);
        List<ModelDescriptor> result = new ArrayList<>();
        for (JsonNode item : data) {
            result.add(parseOpenAiModel(provider, item));
        }
        return List.copyOf(result);
    }

    private List<ModelDescriptor> discoverAnthropic(ProviderConfig provider) {
        String base = Endpoints.endpoint(provider, "modelsPath", "/v1", "/models");
        List<ModelDescriptor> result = new ArrayList<>();
        String next = base;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode root = http.getJson(provider, next, headers(provider));
            JsonNode data = array(root, "data", provider);
            for (JsonNode item : data) {
                result.add(parseAnthropicModel(provider, item));
            }
            if (!root.path("has_more").asBoolean(false)) {
                break;
            }
            String lastId = root.path("last_id").asText("");
            if (lastId.isBlank()) {
                break;
            }
            next = query(base, Map.of("after", lastId, "limit", "1000"));
        }
        return List.copyOf(result);
    }

    private List<ModelDescriptor> discoverGemini(ProviderConfig provider) {
        String base = Endpoints.endpoint(provider, "modelsPath", "/v1beta", "/models");
        List<ModelDescriptor> result = new ArrayList<>();
        String next = base;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode root = http.getJson(provider, next, headers(provider));
            JsonNode models = array(root, "models", provider);
            for (JsonNode item : models) {
                result.add(parseGeminiModel(provider, item));
            }
            String token = root.path("nextPageToken").asText("");
            if (token.isBlank()) {
                break;
            }
            next = query(base, Map.of("pageToken", token));
        }
        return List.copyOf(result);
    }

    private List<ModelDescriptor> discoverOllama(ProviderConfig provider) {
        String endpoint = Endpoints.endpoint(provider, "modelsPath", "/api", "/tags");
        JsonNode root = http.getJson(provider, endpoint, headers(provider));
        JsonNode models = array(root, "models", provider);
        List<ModelDescriptor> result = new ArrayList<>();
        for (JsonNode item : models) {
            result.add(parseOllamaModel(provider, item));
        }
        return List.copyOf(result);
    }

    private ModelDescriptor parseOpenAiModel(ProviderConfig provider, JsonNode item) {
        String id = requiredText(item, "id", provider);
        String displayName = text(item, "display_name", text(item, "displayName", id));
        String ownedBy = text(item, "owned_by", text(item, "ownedBy", provider.id()));
        Integer contextWindow = integer(item, "context_window", "context_length");
        Integer maxInput = integer(item, "max_input_tokens");
        if (maxInput == null) {
            maxInput = contextWindow;
        }
        Integer maxOutput = integer(item, "max_output_tokens");
        List<String> supportedParameters = strings(item.path("supported_parameters"));
        boolean supportsTools = item.path("tool_call").asBoolean(false)
                || item.path("supports_tools").asBoolean(false)
                || supportedParameters.contains("tools");
        boolean supportsReasoning = item.path("reasoning").asBoolean(false)
                || item.path("supports_reasoning").asBoolean(false)
                || supportedParameters.contains("reasoning");
        List<String> levels = reasoningLevels(item);
        boolean supportsStreaming = optionalBoolean(item, "supports_streaming",
                optionalBoolean(item, "supported_streaming", true));
        boolean supportsStructured = optionalBoolean(item, "supports_structured_output",
                optionalBoolean(item, "structured_output", true));
        boolean supportsEmbeddings = optionalBoolean(item, "supports_embeddings",
                optionalBoolean(item, "embeddings", false));
        Map<String, BigDecimal> pricing = pricing(item.path("pricing"));
        List<String> nativeEndpoints = strings(item.path("native_endpoints"));
        List<String> inputModalities = modalities(item, "input");
        List<String> outputModalities = modalities(item, "output");
        Instant createdAt = timestamp(item);
        return ModelDescriptor.builder(id)
                .displayName(displayName)
                .ownedBy(ownedBy)
                .provider(provider.id())
                .contextWindow(contextWindow)
                .maxInputTokens(maxInput)
                .maxOutputTokens(maxOutput)
                .supportsTools(supportsTools)
                .supportsReasoning(supportsReasoning)
                .supportsStreaming(supportsStreaming)
                .supportsStructuredOutput(supportsStructured)
                .supportsEmbeddings(supportsEmbeddings)
                .reasoningLevels(levels)
                .supportedParameters(supportedParameters)
                .pricing(pricing)
                .inputModalities(inputModalities)
                .outputModalities(outputModalities)
                .nativeEndpoints(nativeEndpoints)
                .createdAt(createdAt)
                .extra(extra(item))
                .build();
    }

    private ModelDescriptor parseAnthropicModel(ProviderConfig provider, JsonNode item) {
        String id = requiredText(item, "id", provider);
        String displayName = text(item, "display_name", id);
        JsonNode capabilities = item.path("capabilities");
        boolean supportsTools = capabilities.path("tools").asBoolean(false)
                || capabilities.path("tool_use").asBoolean(false)
                || capabilities.path("tools").isObject();
        boolean supportsReasoning = capabilities.path("thinking").asBoolean(false)
                || capabilities.path("reasoning").asBoolean(false);
        Integer contextWindow = integer(item, "context_window", "max_input_tokens");
        return ModelDescriptor.builder(id)
                .displayName(displayName)
                .ownedBy("Anthropic")
                .provider(provider.id())
                .contextWindow(contextWindow)
                .maxInputTokens(integer(item, "max_input_tokens"))
                .maxOutputTokens(integer(item, "max_output_tokens"))
                .supportsTools(supportsTools)
                .supportsReasoning(supportsReasoning)
                .supportsStreaming(true)
                .supportsStructuredOutput(false)
                .reasoningLevels(reasoningLevels(item))
                .supportedParameters(strings(item.path("supported_parameters")))
                .inputModalities(List.of("text"))
                .outputModalities(List.of("text"))
                .nativeEndpoints(List.of("/v1/messages"))
                .createdAt(timestamp(item))
                .extra(extra(item))
                .build();
    }

    private ModelDescriptor parseGeminiModel(ProviderConfig provider, JsonNode item) {
        String rawName = requiredText(item, "name", provider);
        String id = rawName.startsWith("models/") ? rawName.substring("models/".length()) : rawName;
        String displayName = text(item, "displayName", id);
        List<String> methods = strings(item.path("supportedGenerationMethods"));
        boolean generate = methods.contains("generateContent") || methods.contains("streamGenerateContent");
        boolean stream = methods.contains("streamGenerateContent");
        boolean embed = methods.contains("embedContent") || methods.contains("batchEmbedContents");
        Integer inputLimit = integer(item, "inputTokenLimit");
        Integer outputLimit = integer(item, "outputTokenLimit");
        boolean reasoning = item.path("thinking").asBoolean(false)
                || item.path("supports_reasoning").asBoolean(false);
        return ModelDescriptor.builder(id)
                .displayName(displayName)
                .ownedBy("Google")
                .provider(provider.id())
                .contextWindow(inputLimit)
                .maxInputTokens(inputLimit)
                .maxOutputTokens(outputLimit)
                .supportsTools(generate)
                .supportsReasoning(reasoning)
                .supportsStreaming(stream || generate)
                .supportsStructuredOutput(generate)
                .supportsEmbeddings(embed)
                .reasoningLevels(reasoningLevels(item))
                .supportedParameters(strings(item.path("supported_parameters")))
                .inputModalities(List.of("text"))
                .outputModalities(List.of("text"))
                .nativeEndpoints(methods)
                .createdAt(timestamp(item))
                .extra(extra(item))
                .build();
    }

    private ModelDescriptor parseOllamaModel(ProviderConfig provider, JsonNode item) {
        String id = text(item, "model", text(item, "name", null));
        if (id == null || id.isBlank()) {
            throw ModelBridgeException.invalidResponse("Ollama model entry has no model or name",
                    provider.id(), null);
        }
        String displayName = text(item, "name", id);
        JsonNode details = item.path("details");
        String family = details.path("family").asText("ollama");
        List<String> capabilities = strings(item.path("capabilities"));
        boolean supportsTools = capabilities.contains("tools");
        Integer contextWindow = integer(item, "context_window", "context_length");
        Integer maxOutput = integer(item, "max_output_tokens");
        return ModelDescriptor.builder(id)
                .displayName(displayName)
                .ownedBy(family)
                .provider(provider.id())
                .contextWindow(contextWindow)
                .maxInputTokens(integer(item, "max_input_tokens"))
                .maxOutputTokens(maxOutput)
                .supportsTools(supportsTools)
                .supportsReasoning(capabilities.contains("thinking") || capabilities.contains("reasoning"))
                .supportsStreaming(true)
                .supportsStructuredOutput(capabilities.contains("completion"))
                .supportsEmbeddings(capabilities.contains("embedding"))
                .inputModalities(List.of("text"))
                .outputModalities(List.of("text"))
                .createdAt(timestamp(item))
                .extra(extra(item))
                .build();
    }

    private static JsonNode array(JsonNode root, String field, ProviderConfig provider) {
        if (root == null) {
            throw ModelBridgeException.invalidResponse("model discovery returned no JSON", provider.id(), null);
        }
        JsonNode value = root.isArray() ? root : root.path(field);
        if (!value.isArray()) {
            throw ModelBridgeException.invalidResponse("model discovery response has no array", provider.id(), null);
        }
        return value;
    }

    private static String requiredText(JsonNode item, String field, ProviderConfig provider) {
        String value = text(item, field, null);
        if (value == null || value.isBlank()) {
            throw ModelBridgeException.invalidResponse("model discovery entry has no " + field,
                    provider.id(), null);
        }
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static Integer integer(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
        }
        return null;
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private static List<String> reasoningLevels(JsonNode node) {
        LinkedHashSet<String> levels = new LinkedHashSet<>(strings(node.path("reasoning_levels")));
        for (JsonNode item : node.path("supported_reasoning_levels")) {
            if (item.isTextual()) {
                levels.add(item.asText());
            } else if (item.isObject() && item.path("effort").isTextual()) {
                levels.add(item.path("effort").asText());
            }
        }
        return List.copyOf(levels);
    }

    private static Map<String, BigDecimal> pricing(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNumber() || value.isTextual()) {
                try {
                    values.put(entry.getKey(), new BigDecimal(value.asText()));
                } catch (NumberFormatException ignored) {
                    // Unknown non-numeric pricing extensions are kept out of the normalized map.
                }
            }
        });
        return Map.copyOf(values);
    }

    private static List<String> modalities(JsonNode item, String field) {
        JsonNode modalities = item.path("modalities");
        JsonNode value = modalities.path(field);
        if (!value.isArray()) {
            value = item.path(field + "_modalities");
        }
        List<String> result = strings(value);
        return result.isEmpty() ? List.of("text") : result;
    }

    private static Instant timestamp(JsonNode item) {
        JsonNode createdAt = item.path("created_at");
        if (createdAt.isTextual() && !createdAt.asText().isBlank()) {
            try {
                return Instant.parse(createdAt.asText());
            } catch (RuntimeException ignored) {
                // Numeric fallback below.
            }
        }
        JsonNode modifiedAt = item.path("modified_at");
        if (modifiedAt.isTextual() && !modifiedAt.asText().isBlank()) {
            try {
                return Instant.parse(modifiedAt.asText());
            } catch (RuntimeException ignored) {
                // Numeric fallback below.
            }
        }
        JsonNode created = item.path("created");
        if (created.isNumber() && created.asLong() > 0) {
            return Instant.ofEpochSecond(created.asLong());
        }
        return null;
    }

    private Map<String, Object> extra(JsonNode item) {
        Map<String, Object> extra = new LinkedHashMap<>();
        item.fields().forEachRemaining(entry -> {
            if (!STANDARD_KEYS.contains(entry.getKey()) && !entry.getValue().isNull()) {
                extra.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
            }
        });
        return Map.copyOf(extra);
    }

    private static Map<String, String> headers(ProviderConfig provider) {
        Map<String, String> headers = new LinkedHashMap<>(provider.headers());
        switch (provider.type()) {
            case OPENAI_COMPATIBLE, LLAMA_CPP -> {
                if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                    headers.putIfAbsent("Authorization", "Bearer " + provider.apiKey());
                }
            }
            case ANTHROPIC -> {
                if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                    headers.putIfAbsent("x-api-key", provider.apiKey());
                }
                headers.putIfAbsent("anthropic-version",
                        Endpoints.option(provider, "anthropicVersion", "2023-06-01"));
            }
            case GEMINI -> {
                if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                    headers.putIfAbsent("x-goog-api-key", provider.apiKey());
                }
            }
            default -> {
            }
        }
        return headers;
    }

    private static String query(String url, Map<String, String> parameters) {
        StringBuilder result = new StringBuilder(url);
        char separator = url.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            result.append(separator)
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return result.toString();
    }
}
