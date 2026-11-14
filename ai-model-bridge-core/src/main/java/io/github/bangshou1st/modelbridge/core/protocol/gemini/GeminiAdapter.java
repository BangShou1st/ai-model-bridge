package io.github.bangshou1st.modelbridge.core.protocol.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.ReasoningOptions;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.StructuredOutput;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import io.github.bangshou1st.modelbridge.core.model.ToolCallDelta;
import io.github.bangshou1st.modelbridge.core.model.ToolChoice;
import io.github.bangshou1st.modelbridge.core.model.ToolDefinition;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.protocol.ChatProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class GeminiAdapter implements ChatProtocolAdapter {
    protected final ObjectMapper mapper;
    protected final HttpTransport http;

    public GeminiAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public Set<ProviderType> providerTypes() {
        return Set.of(ProviderType.GEMINI);
    }

    @Override
    public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
        long started = System.nanoTime();
        JsonNode response = http.postJson(provider, endpoint(provider, request.model(), false),
                headers(provider), body(request.model(), request.messages(), request.tools(),
                        request.toolChoice(), request.temperature(), request.maxOutputTokens(),
                        request.structuredOutput(), request.reasoning()));
        return parseResponse(response, provider, request.model(), scope(null), started);
    }

    @Override
    public void completeStream(ProviderConfig provider, ChatRequest request, Consumer<StreamChunk> consumer) {
        String scope = scope(null);
        JsonNode requestBody = body(request.model(), request.messages(), request.tools(),
                request.toolChoice(), request.temperature(), request.maxOutputTokens(),
                request.structuredOutput(), request.reasoning());
        StreamAccumulator accumulator = new StreamAccumulator(request.model(), scope);
        http.postSse(provider, endpoint(provider, request.model(), true), headers(provider), requestBody, event -> {
            if ("[DONE]".equals(event.data())) {
                return;
            }
            JsonNode data;
            try {
                data = mapper.readTree(event.data());
            } catch (Exception e) {
                throw ModelBridgeException.invalidResponse("invalid Gemini SSE JSON", provider.id(), null);
            }
            accumulator.accept(data, provider.id(), consumer);
        });
        accumulator.finish(provider, consumer);
    }

    @Override
    public TurnResult turn(ProviderConfig provider, TurnRequest request) {
        long started = System.nanoTime();
        String scope = scope(request.requestId());
        JsonNode response = http.postJson(provider, endpoint(provider, request.model(), false),
                headers(provider), body(request.model(), request.messages(), request.tools(),
                        request.toolChoice(), request.temperature(), request.maxOutputTokens(),
                        StructuredOutput.none(), request.reasoning()));
        ChatResponse parsed = parseResponse(response, provider, request.model(), scope, started);
        return new TurnResult(parsed.content(), parsed.toolCalls(), parsed.finishReason(), parsed.usage(),
                provider.id(), parsed.model(), parsed.latencyMs());
    }

    private String endpoint(ProviderConfig provider, String model, boolean stream) {
        String base = Endpoints.endpoint(provider, "chatPath", "/v1beta",
                "/models/{model}:generateContent").replace("{model}", model);
        if (!stream) {
            return base;
        }
        String path = base.contains(":generateContent")
                ? base.replace(":generateContent", ":streamGenerateContent")
                : base + ":streamGenerateContent";
        return path + (path.contains("?") ? "&" : "?") + "alt=sse";
    }

    private Map<String, String> headers(ProviderConfig provider) {
        Map<String, String> headers = new LinkedHashMap<>(provider.headers());
        headers.put("x-goog-api-key", provider.apiKey() == null ? "" : provider.apiKey());
        return headers;
    }

    private ObjectNode body(String model, List<Message> sourceMessages, List<ToolDefinition> tools,
                            ToolChoice toolChoice, Double temperature, Integer maxOutputTokens,
                            StructuredOutput structuredOutput, ReasoningOptions reasoning) {
        ObjectNode body = mapper.createObjectNode();
        List<String> systemParts = new ArrayList<>();
        ArrayNode contents = body.putArray("contents");
        PartBuilder partBuilder = new PartBuilder(contents);

        for (Message message : sourceMessages) {
            switch (message.role()) {
                case SYSTEM -> {
                    if (!message.content().isBlank()) {
                        systemParts.add(message.content());
                    }
                }
                case USER -> {
                    if (!message.content().isBlank()) {
                        partBuilder.add("user", textPart(message.content()));
                    }
                }
                case ASSISTANT -> {
                    if (!message.content().isBlank()) {
                        partBuilder.add("model", textPart(message.content()));
                    }
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode part = mapper.createObjectNode();
                        ObjectNode functionCall = part.putObject("functionCall");
                        functionCall.put("name", call.name());
                        functionCall.set("args", call.arguments());
                        if (call.thoughtSignature() != null && !call.thoughtSignature().isBlank()) {
                            part.put("thoughtSignature", call.thoughtSignature());
                        }
                        partBuilder.add("model", part);
                    }
                }
                case TOOL -> {
                    ObjectNode part = mapper.createObjectNode();
                    ObjectNode functionResponse = part.putObject("functionResponse");
                    functionResponse.put("name", message.name() == null ? "" : message.name());
                    ObjectNode response = functionResponse.putObject("response");
                    JsonNode result = message.toolResult();
                    if (result != null && result.isObject()) {
                        response.setAll((ObjectNode) result);
                    } else if (result != null) {
                        response.set("result", result);
                    } else {
                        response.put("result", message.content());
                    }
                    partBuilder.add("user", part);
                }
            }
        }

        if (structuredOutput != null && structuredOutput.mode() == StructuredOutput.Mode.PROMPT_JSON) {
            String instruction = "Return only valid JSON";
            if (structuredOutput.schema() != null) {
                instruction += " matching this JSON schema: " + structuredOutput.schema();
            }
            systemParts.add(instruction);
        }
        if (!systemParts.isEmpty()) {
            body.putObject("systemInstruction").putArray("parts").addObject()
                    .put("text", String.join("\n\n", systemParts));
        }

        ObjectNode generation = body.putObject("generationConfig");
        if (temperature != null) {
            generation.put("temperature", temperature);
        }
        if (maxOutputTokens != null) {
            generation.put("maxOutputTokens", maxOutputTokens);
        }
        if (structuredOutput != null && structuredOutput.mode() != StructuredOutput.Mode.NONE
                && structuredOutput.mode() != StructuredOutput.Mode.PROMPT_JSON) {
            generation.put("responseMimeType", "application/json");
            if (structuredOutput.schema() != null) {
                generation.set("responseJsonSchema", structuredOutput.schema());
            }
        }
        applyThinking(generation, reasoning);

        if (!tools.isEmpty()) {
            ObjectNode toolObject = body.putArray("tools").addObject();
            ArrayNode declarations = toolObject.putArray("functionDeclarations");
            for (ToolDefinition tool : tools) {
                ObjectNode declaration = declarations.addObject();
                declaration.put("name", tool.name());
                declaration.put("description", tool.description());
                declaration.set("parameters", tool.inputSchema());
            }
            String mode = switch (toolChoice == null ? ToolChoice.AUTO : toolChoice) {
                case REQUIRED -> "ANY";
                case NONE -> "NONE";
                default -> "AUTO";
            };
            body.putObject("toolConfig").putObject("functionCallingConfig").put("mode", mode);
        }
        return body;
    }

    private void applyThinking(ObjectNode generation, ReasoningOptions reasoning) {
        if (reasoning == null) {
            return;
        }
        if (reasoning.budgetTokens() != null) {
            generation.putObject("thinkingConfig").put("thinkingBudget", reasoning.budgetTokens());
            return;
        }
        String effort = reasoning.effort() == null ? "" : reasoning.effort().strip().toLowerCase();
        if (effort.equals("low") || effort.equals("medium") || effort.equals("high")) {
            generation.putObject("thinkingConfig").put("thinkingLevel", effort);
        }
    }

    private ObjectNode textPart(String text) {
        ObjectNode part = mapper.createObjectNode();
        part.put("text", text);
        return part;
    }

    private ChatResponse parseResponse(JsonNode response, ProviderConfig provider, String fallbackModel,
                                       String scope, long started) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        int toolIndex = 0;
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.hasNonNull("text")) {
                    if (part.path("thought").asBoolean(false)) {
                        reasoning.append(part.path("text").asText());
                    } else {
                        content.append(part.path("text").asText());
                    }
                }
                if (part.hasNonNull("functionCall")) {
                    JsonNode functionCall = part.path("functionCall");
                    String name = functionCall.path("name").asText();
                    if (name.isBlank()) {
                        throw ModelBridgeException.invalidResponse("Gemini functionCall missing name", provider.id(), null);
                    }
                    JsonNode args = functionCall.path("args");
                    if (!args.isObject()) {
                        args = mapper.createObjectNode();
                    }
                    String signature = part.path("thoughtSignature").asText(
                            functionCall.path("thoughtSignature").asText(null));
                    toolCalls.add(new ToolCall(toolId(scope, toolIndex++), name, args, signature));
                }
            }
        }
        TokenUsage usage = usage(response.path("usageMetadata"));
        FinishReason finish = finishReason(response.path("candidates").path(0).path("finishReason").asText(""));
        long latency = elapsedMs(started);
        if (finish == FinishReason.LENGTH) {
            throw ModelBridgeException.truncated(provider.id(), usage);
        }
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw ModelBridgeException.invalidResponse(
                    "Gemini returned neither text nor function calls", provider.id(), usage);
        }
        String model = response.path("modelVersion").asText(fallbackModel);
        return new ChatResponse(content.toString(), reasoning.isEmpty() ? null : reasoning.toString(),
                toolCalls, finish, usage, provider.id(), model, latency, Map.of());
    }

    private TokenUsage usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.empty();
        }
        return new TokenUsage(
                integer(usage, "promptTokenCount"),
                integer(usage, "candidatesTokenCount"),
                integer(usage, "totalTokenCount"),
                integer(usage, "thoughtsTokenCount"),
                integer(usage, "cachedContentTokenCount"));
    }

    private static Integer integer(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).asInt() : null;
    }

    private static FinishReason finishReason(String reason) {
        return switch (reason) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS" -> FinishReason.LENGTH;
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
                    "SPII", "IMAGE_SAFETY" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    private static String scope(String requestId) {
        String value = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String toolId(String scope, int index) {
        return "gemini:" + scope + ":" + index;
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static final class PartBuilder {
        private final ArrayNode contents;
        private String currentRole;
        private ObjectNode current;

        private PartBuilder(ArrayNode contents) {
            this.contents = contents;
        }

        private void add(String role, ObjectNode part) {
            if (!role.equals(currentRole) || current == null) {
                currentRole = role;
                current = contents.addObject();
                current.put("role", role);
                current.putArray("parts");
            }
            ((ArrayNode) current.path("parts")).add(part);
        }
    }

    private final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final String scope;
        private String model;
        private FinishReason finish = FinishReason.UNKNOWN;
        private TokenUsage usage = TokenUsage.empty();
        private int toolIndex;

        private StreamAccumulator(String model, String scope) {
            this.model = model;
            this.scope = scope;
        }

        private void accept(JsonNode data, String provider, Consumer<StreamChunk> consumer) {
            if (data.hasNonNull("modelVersion")) {
                model = data.path("modelVersion").asText(model);
            }
            JsonNode candidate = data.path("candidates").path(0);
            String reason = candidate.path("finishReason").asText("");
            if (!reason.isBlank()) {
                finish = finishReason(reason);
            }
            JsonNode parts = candidate.path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.hasNonNull("text")) {
                        String text = part.path("text").asText();
                        if (part.path("thought").asBoolean(false)) {
                            reasoning.append(text);
                            consumer.accept(StreamChunk.reasoning(text, model, provider));
                        } else {
                            content.append(text);
                            consumer.accept(StreamChunk.content(text, model, provider));
                        }
                    }
                    if (part.hasNonNull("functionCall")) {
                        JsonNode functionCall = part.path("functionCall");
                        String name = functionCall.path("name").asText();
                        if (name.isBlank()) {
                            throw ModelBridgeException.invalidResponse(
                                    "Gemini stream functionCall missing name", provider, null);
                        }
                        JsonNode args = functionCall.path("args");
                        if (!args.isObject()) {
                            args = mapper.createObjectNode();
                        }
                        int index = toolIndex++;
                        consumer.accept(StreamChunk.toolCall(
                                new ToolCallDelta(index, toolId(scope, index), name, args.toString()),
                                model, provider));
                    }
                }
            }
            if (data.has("usageMetadata")) {
                usage = usage(data.path("usageMetadata"));
            }
        }

        private void finish(ProviderConfig provider, Consumer<StreamChunk> consumer) {
            if (finish == FinishReason.LENGTH) {
                throw ModelBridgeException.truncated(provider.id(), usage);
            }
            if (content.isEmpty() && reasoning.isEmpty() && toolIndex == 0) {
                throw ModelBridgeException.invalidResponse(
                        "Gemini stream returned neither text nor function calls", provider.id(), usage);
            }
            consumer.accept(StreamChunk.done(finish, usage, model, provider.id()));
        }
    }
}
