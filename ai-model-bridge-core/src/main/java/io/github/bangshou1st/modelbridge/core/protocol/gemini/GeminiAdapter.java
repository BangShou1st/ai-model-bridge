package io.github.bangshou1st.modelbridge.core.protocol.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandles;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ContentPart;
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
import io.github.bangshou1st.modelbridge.core.protocol.ChatProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
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
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.GEMINI);
    }

    @Override
    public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
        long started = System.nanoTime();
        JsonNode response = http.postJson(provider, endpoint(provider, request.model(), false),
                headers(provider), body(request));
        return parseResponse(response, provider, request.model(), scope(request.requestId()), started);
    }

    @Override
    public void completeStream(ProviderConfig provider, ChatRequest request, Consumer<StreamChunk> consumer) {
        stream(provider, request, consumer).await();
    }

    @Override
    public StreamHandle stream(ProviderConfig provider, ChatRequest request,
                               Consumer<StreamChunk> consumer) {
        return StreamHandles.start(StreamHandles.sharedExecutor(),
                handle -> completeStreamInternal(provider, request, consumer, handle));
    }

    private void completeStreamInternal(ProviderConfig provider, ChatRequest request,
                                        Consumer<StreamChunk> consumer, StreamHandle handle) {
        String scope = scope(null);
        JsonNode requestBody = body(request);
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
        }, handle);
        if (handle.isCancelled()) {
            return;
        }
        accumulator.finish(provider, consumer);
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

    private ObjectNode body(ChatRequest request) {
        String model = request.model();
        List<Message> sourceMessages = request.messages();
        List<ToolDefinition> tools = request.tools();
        ToolChoice toolChoice = request.toolChoice();
        StructuredOutput structuredOutput = request.structuredOutput();
        ReasoningOptions reasoning = request.reasoning();
        ChatOptions options = request.options();
        ObjectNode body = mapper.createObjectNode();
        List<String> systemParts = new ArrayList<>();
        ArrayNode contents = body.putArray("contents");
        PartBuilder partBuilder = new PartBuilder(contents);
        Map<String, String> toolNamesById = new LinkedHashMap<>();

        for (Message message : sourceMessages) {
            switch (message.role()) {
                case SYSTEM -> {
                    if (!message.content().isBlank()) {
                        systemParts.add(message.content());
                    }
                }
                case USER -> addUserParts(partBuilder, message);
                case ASSISTANT -> addAssistantParts(partBuilder, message, toolNamesById);
                case TOOL -> {
                    ObjectNode part = mapper.createObjectNode();
                    ObjectNode functionResponse = part.putObject("functionResponse");
                    String toolName = message.name();
                    if (toolName == null || toolName.isBlank()) {
                        toolName = toolNamesById.get(message.toolCallId());
                    }
                    if (toolName == null || toolName.isBlank()) {
                        throw ModelBridgeException.invalidResponse(
                                "Gemini tool response is missing the function name", null, null);
                    }
                    functionResponse.put("name", toolName);
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
        if (options.temperature() != null) {
            generation.put("temperature", options.temperature());
        }
        if (options.topP() != null) {
            generation.put("topP", options.topP());
        }
        if (options.maxOutputTokens() != null) {
            generation.put("maxOutputTokens", options.maxOutputTokens());
        }
        if (options.seed() != null) {
            generation.put("seed", options.seed());
        }
        if (options.frequencyPenalty() != null) {
            generation.put("frequencyPenalty", options.frequencyPenalty());
        }
        if (options.presencePenalty() != null) {
            generation.put("presencePenalty", options.presencePenalty());
        }
        if (!options.stop().isEmpty()) {
            generation.set("stopSequences", mapper.valueToTree(options.stop()));
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
        options.extraBody().forEach((key, value) -> {
            if (!body.has(key) && !isCoreField(key)) {
                body.set(key, mapper.valueToTree(value));
            }
        });
        return body;
    }

    private void addUserParts(PartBuilder builder, Message message) {
        for (ContentPart part : message.parts()) {
            if (part instanceof ContentPart.Text text) {
                if (!text.text().isEmpty()) {
                    builder.add("user", textPart(text.text()));
                }
            } else if (part instanceof ContentPart.Image image) {
                ObjectNode node = mapper.createObjectNode();
                if (image.base64Data() != null && !image.base64Data().isBlank()) {
                    ObjectNode inline = node.putObject("inlineData");
                    inline.put("mimeType", image.mediaType() == null
                            ? "image/png" : image.mediaType());
                    inline.put("data", image.base64Data());
                } else {
                    ObjectNode file = node.putObject("fileData");
                    file.put("fileUri", image.url());
                    file.put("mimeType", image.mediaType() == null
                            ? "image/png" : image.mediaType());
                }
                builder.add("user", node);
            }
        }
    }

    private void addAssistantParts(PartBuilder builder, Message message,
                                   Map<String, String> toolNamesById) {
        for (ContentPart part : message.parts()) {
            if (part instanceof ContentPart.Text text && !text.text().isEmpty()) {
                builder.add("model", textPart(text.text()));
            } else if (part instanceof ContentPart.Thinking thinking && !thinking.text().isEmpty()) {
                ObjectNode node = textPart(thinking.text());
                node.put("thought", true);
                if (thinking.signature() != null && !thinking.signature().isBlank()) {
                    node.put("thoughtSignature", thinking.signature());
                }
                builder.add("model", node);
            }
        }
        for (ToolCall call : message.toolCalls()) {
            toolNamesById.put(call.id(), call.name());
            ObjectNode part = mapper.createObjectNode();
            ObjectNode functionCall = part.putObject("functionCall");
            functionCall.put("name", call.name());
            functionCall.set("args", call.arguments());
            if (call.thoughtSignature() != null && !call.thoughtSignature().isBlank()) {
                part.put("thoughtSignature", call.thoughtSignature());
            }
            builder.add("model", part);
        }
    }

    private static boolean isCoreField(String key) {
        return switch (key == null ? "" : key.toLowerCase()) {
            case "contents", "systeminstruction", "generationconfig", "tools", "toolconfig",
                    "model" -> true;
            default -> false;
        };
    }

    private void applyThinking(ObjectNode generation, ReasoningOptions reasoning) {
        if (reasoning == null) {
            return;
        }
        if (Boolean.FALSE.equals(reasoning.enabled())) {
            generation.putObject("thinkingConfig").put("thinkingBudget", 0);
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
        List<ContentPart> partsList = new ArrayList<>();
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        int toolIndex = 0;
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.hasNonNull("text")) {
                    String text = part.path("text").asText();
                    String signature = part.path("thoughtSignature").asText(null);
                    if (part.path("thought").asBoolean(false)) {
                        reasoning.append(text);
                        partsList.add(new ContentPart.Thinking(text, signature));
                    } else {
                        content.append(text);
                        partsList.add(new ContentPart.Text(text));
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
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw ModelBridgeException.invalidResponse(
                    "Gemini returned neither text nor function calls", provider.id(), usage);
        }
        String model = response.path("modelVersion").asText(fallbackModel);
        return new ChatResponse(content.toString(), reasoning.isEmpty() ? null : reasoning.toString(),
                toolCalls, finish, usage, provider.id(), model, latency, Map.of(), partsList);
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
                        String signature = part.path("thoughtSignature").asText("");
                        java.util.Map<String, Object> metadata = signature.isBlank()
                                ? Map.of() : Map.of("thought_signature", signature);
                        consumer.accept(new StreamChunk(StreamChunk.Type.TOOL_CALL_DELTA, "",
                                new ToolCallDelta(index, toolId(scope, index), name, args.toString()),
                                null, null, model, provider, metadata));
                    }
                }
            }
            if (data.has("usageMetadata")) {
                usage = usage(data.path("usageMetadata"));
            }
        }

        private void finish(ProviderConfig provider, Consumer<StreamChunk> consumer) {
            if (content.isEmpty() && reasoning.isEmpty() && toolIndex == 0) {
                throw ModelBridgeException.invalidResponse(
                        "Gemini stream returned neither text nor function calls", provider.id(), usage);
            }
            consumer.accept(StreamChunk.done(finish, usage, model, provider.id()));
        }
    }
}
