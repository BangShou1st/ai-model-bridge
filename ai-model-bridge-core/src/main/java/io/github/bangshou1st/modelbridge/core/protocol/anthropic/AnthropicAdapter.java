package io.github.bangshou1st.modelbridge.core.protocol.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandles;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
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
import java.util.TreeMap;
import java.util.function.Consumer;

public final class AnthropicAdapter implements ChatProtocolAdapter {
    protected final ObjectMapper mapper;
    protected final HttpTransport http;

    public AnthropicAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.ANTHROPIC);
    }

    @Override
    public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
        long started = System.nanoTime();
        JsonNode response = http.postJson(provider, endpoint(provider), headers(provider),
                body(request, request.structuredOutput(), false));
        return parseResponse(response, provider, request.model(), started);
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
        JsonNode requestBody = body(request, request.structuredOutput(), true);
        StreamAccumulator accumulator = new StreamAccumulator(request.model());
        http.postSse(provider, endpoint(provider), headers(provider), requestBody, event -> {
            if ("[DONE]".equals(event.data())) {
                return;
            }
            JsonNode data;
            try {
                data = mapper.readTree(event.data());
            } catch (Exception e) {
                throw ModelBridgeException.invalidResponse("invalid Anthropic SSE JSON", provider.id(), null);
            }
            String type = data.path("type").asText(event.event());
            switch (type) {
                case "message_start" -> accumulator.messageStart(data.path("message"));
                case "content_block_start" -> {
                    JsonNode block = data.path("content_block");
                    if ("tool_use".equals(block.path("type").asText())) {
                        int index = data.path("index").asInt();
                        StreamAccumulator.ToolState tool = accumulator.tool(index);
                        tool.id = block.path("id").asText();
                        tool.name = block.path("name").asText();
                        if (block.path("input").isObject() && !block.path("input").isEmpty()) {
                            tool.arguments.append(block.path("input").toString());
                        }
                        consumer.accept(StreamChunk.toolCall(
                                new ToolCallDelta(index, tool.id, tool.name, ""),
                                accumulator.model, provider.id()));
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = data.path("delta");
                    String deltaType = delta.path("type").asText();
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.path("text").asText("");
                        if (!text.isEmpty()) {
                            accumulator.content.append(text);
                            consumer.accept(StreamChunk.content(text, accumulator.model, provider.id()));
                        }
                    } else if ("thinking_delta".equals(deltaType)) {
                        String thinking = delta.path("thinking").asText("");
                        if (!thinking.isEmpty()) {
                            consumer.accept(StreamChunk.reasoning(thinking, accumulator.model, provider.id()));
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        int index = data.path("index").asInt();
                        StreamAccumulator.ToolState tool = accumulator.tool(index);
                        String fragment = delta.path("partial_json").asText("");
                        tool.arguments.append(fragment);
                        consumer.accept(StreamChunk.toolCall(
                                new ToolCallDelta(index, tool.id, tool.name, fragment),
                                accumulator.model, provider.id()));
                    }
                }
                case "message_delta" -> {
                    accumulator.stopReason = data.path("delta").path("stop_reason").asText("");
                    if (data.path("usage").has("output_tokens")) {
                        accumulator.outputTokens = data.path("usage").path("output_tokens").asInt();
                    }
                }
                case "message_stop" -> {
                    // The stream is completed below, after the final frame has been observed.
                }
                case "error" -> throw new ModelBridgeException(
                        io.github.bangshou1st.modelbridge.core.security.ErrorCategory.UNAVAILABLE,
                        "Anthropic stream reported an error", provider.id());
                default -> {
                    // ping and future event types are intentionally ignored.
                }
            }
        }, handle);
        if (handle.isCancelled()) {
            return;
        }
        accumulator.finish(provider, consumer);
    }

    private String endpoint(ProviderConfig provider) {
        return Endpoints.endpoint(provider, "chatPath", "/v1", "/messages");
    }

    private Map<String, String> headers(ProviderConfig provider) {
        Map<String, String> headers = new LinkedHashMap<>(provider.headers());
        headers.put("x-api-key", provider.apiKey() == null ? "" : provider.apiKey());
        headers.put("anthropic-version", Endpoints.option(provider, "anthropicVersion", "2023-06-01"));
        return headers;
    }

    private ObjectNode body(ChatRequest request, StructuredOutput structuredOutput,
                            boolean stream) {
        String model = request.model();
        List<Message> sourceMessages = request.messages();
        List<ToolDefinition> tools = request.tools();
        ToolChoice toolChoice = request.toolChoice();
        ChatOptions options = request.options();
        ReasoningOptions reasoning = request.reasoning();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", options.maxOutputTokens() == null ? 1024 : options.maxOutputTokens());
        body.put("stream", stream);
        if (options.temperature() != null) {
            body.put("temperature", options.temperature());
        }
        if (options.topP() != null) {
            body.put("top_p", options.topP());
        }
        if (!options.stop().isEmpty()) {
            body.set("stop_sequences", mapper.valueToTree(options.stop()));
        }
        addThinking(body, reasoning, options.maxOutputTokens());

        List<String> systemParts = new ArrayList<>();
        ArrayNode messages = body.putArray("messages");
        RoleBuilder roleBuilder = new RoleBuilder(messages);
        for (Message message : sourceMessages) {
            switch (message.role()) {
                case SYSTEM -> {
                    if (!message.content().isBlank()) {
                        systemParts.add(message.content());
                    }
                }
                case USER -> addUserContent(roleBuilder, message);
                case ASSISTANT -> addAssistantContent(roleBuilder, message);
                case TOOL -> {
                    ObjectNode block = mapper.createObjectNode();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", message.toolCallId());
                    if (message.toolResult() != null) {
                        block.set("content", message.toolResult());
                    } else {
                        block.put("content", message.content());
                    }
                    block.put("is_error", false);
                    roleBuilder.add("user", block);
                }
            }
        }
        if (!systemParts.isEmpty()) {
            body.put("system", String.join("\n\n", systemParts));
        }

        if (!tools.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = toolArray.addObject();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", tool.inputSchema());
            }
            ObjectNode choice = body.putObject("tool_choice");
            switch (toolChoice == null ? ToolChoice.AUTO : toolChoice) {
                case REQUIRED -> choice.put("type", "any");
                case NONE -> choice.put("type", "none");
                default -> choice.put("type", "auto");
            }
        }

        if (structuredOutput != null && structuredOutput.enabled()) {
            if (structuredOutput.mode() == StructuredOutput.Mode.JSON_SCHEMA) {
                ObjectNode format = body.putObject("output_config").putObject("format");
                format.put("type", "json_schema");
                format.set("schema", structuredOutput.schema());
            } else {
                systemParts.add(promptInstruction(structuredOutput));
                if (!systemParts.isEmpty()) {
                    body.put("system", String.join("\n\n", systemParts));
                }
            }
        }
        options.extraBody().forEach((key, value) -> {
            if (!body.has(key) && !isCoreField(key)) {
                body.set(key, mapper.valueToTree(value));
            }
        });
        return body;
    }

    private void addUserContent(RoleBuilder builder, Message message) {
        for (ContentPart part : message.parts()) {
            if (part instanceof ContentPart.Text text) {
                if (!text.text().isEmpty()) {
                    builder.add("user", textBlock(text.text()));
                }
            } else if (part instanceof ContentPart.Image image) {
                ObjectNode block = mapper.createObjectNode().put("type", "image");
                ObjectNode source = block.putObject("source");
                if (image.base64Data() != null && !image.base64Data().isBlank()) {
                    source.put("type", "base64");
                    source.put("media_type", image.mediaType() == null
                            ? "image/png" : image.mediaType());
                    source.put("data", image.base64Data());
                } else {
                    source.put("type", "url");
                    source.put("url", image.url());
                }
                builder.add("user", block);
            }
        }
    }

    private void addAssistantContent(RoleBuilder builder, Message message) {
        for (ContentPart part : message.parts()) {
            if (part instanceof ContentPart.Thinking thinking) {
                ObjectNode block = mapper.createObjectNode().put("type", "thinking")
                        .put("thinking", thinking.text());
                if (thinking.signature() != null && !thinking.signature().isBlank()) {
                    block.put("signature", thinking.signature());
                }
                builder.add("assistant", block);
            } else if (part instanceof ContentPart.Text text) {
                if (!text.text().isEmpty()) {
                    builder.add("assistant", textBlock(text.text()));
                }
            }
        }
        for (ToolCall call : message.toolCalls()) {
            ObjectNode block = mapper.createObjectNode();
            block.put("type", "tool_use");
            block.put("id", call.id());
            block.put("name", call.name());
            block.set("input", call.arguments());
            builder.add("assistant", block);
        }
    }

    private static boolean isCoreField(String key) {
        return switch (key == null ? "" : key.toLowerCase()) {
            case "model", "messages", "stream", "tools", "tool_choice", "system",
                    "max_tokens", "temperature", "top_p", "stop_sequences", "thinking",
                    "output_config" -> true;
            default -> false;
        };
    }

    private static void addThinking(ObjectNode body, ReasoningOptions reasoning, Integer maxOutputTokens) {
        if (reasoning == null || Boolean.FALSE.equals(reasoning.enabled())) {
            return;
        }
        Integer budget = reasoning.budgetTokens();
        if (budget == null && Boolean.TRUE.equals(reasoning.enabled())) {
            int outputLimit = maxOutputTokens == null ? 1024 : maxOutputTokens;
            budget = Math.max(1, Math.min(4096, outputLimit / 2));
        }
        if (budget == null || budget <= 0) {
            return;
        }
        ObjectNode thinking = body.putObject("thinking");
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", budget);
    }

    private static String promptInstruction(StructuredOutput structuredOutput) {
        StringBuilder instruction = new StringBuilder(
                "Return only one valid JSON value, with no Markdown fences or explanatory text.");
        if (structuredOutput.schema() != null) {
            instruction.append(" The value must satisfy this JSON Schema:\n")
                    .append(structuredOutput.schema());
        }
        return instruction.toString();
    }

    private ObjectNode textBlock(String text) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private ChatResponse parseResponse(JsonNode response, ProviderConfig provider,
                                       String fallbackModel, long started) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<ContentPart> parts = new ArrayList<>();
        JsonNode blocks = response.path("content");
        if (blocks.isArray()) {
            for (JsonNode block : blocks) {
                switch (block.path("type").asText()) {
                    case "text" -> {
                        String text = block.path("text").asText("");
                        content.append(text);
                        parts.add(new ContentPart.Text(text));
                    }
                    case "thinking" -> {
                        String thinking = block.path("thinking").asText("");
                        String signature = block.hasNonNull("signature")
                                ? block.path("signature").asText() : null;
                        reasoning.append(thinking);
                        parts.add(new ContentPart.Thinking(thinking, signature));
                    }
                    case "tool_use" -> {
                        JsonNode input = block.path("input");
                        if (!input.isObject()) {
                            input = mapper.createObjectNode();
                        }
                        String id = block.path("id").asText();
                        String name = block.path("name").asText();
                        if (id.isBlank() || name.isBlank()) {
                            throw ModelBridgeException.invalidResponse(
                                    "Anthropic tool_use missing id or name", provider.id(), null);
                        }
                        toolCalls.add(new ToolCall(id, name, input));
                    }
                    default -> {
                        // Unknown content blocks are ignored rather than treated as tool calls.
                    }
                }
            }
        }
        TokenUsage usage = usage(response.path("usage"));
        FinishReason finish = finishReason(response.path("stop_reason").asText(""));
        long latency = elapsedMs(started);
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw ModelBridgeException.invalidResponse(
                    "Anthropic returned neither text nor tool calls", provider.id(), usage);
        }
        String responseModel = response.path("model").asText(fallbackModel);
        return new ChatResponse(content.toString(), reasoning.isEmpty() ? null : reasoning.toString(),
                toolCalls, finish, usage, provider.id(), responseModel, latency, Map.of(), parts);
    }

    private TokenUsage usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.empty();
        }
        return new TokenUsage(
                integer(usage, "input_tokens"),
                integer(usage, "output_tokens"),
                null,
                null,
                integer(usage, "cache_read_input_tokens"));
    }

    private static Integer integer(JsonNode node, String field) {
        return node.has(field) && !node.path(field).isNull() ? node.path(field).asInt() : null;
    }

    private static FinishReason finishReason(String reason) {
        return switch (reason) {
            case "end_turn", "stop_sequence", "pause_turn" -> FinishReason.STOP;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.LENGTH;
            case "refusal" -> FinishReason.CONTENT_FILTER;
            default -> {
                yield reason == null || reason.isBlank() ? FinishReason.UNKNOWN : FinishReason.UNKNOWN;
            }
        };
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static final class RoleBuilder {
        private final ArrayNode messages;
        private String currentRole;
        private ObjectNode current;

        private RoleBuilder(ArrayNode messages) {
            this.messages = messages;
        }

        private void add(String role, ObjectNode block) {
            if (!role.equals(currentRole) || current == null) {
                currentRole = role;
                current = messages.addObject();
                current.put("role", role);
                current.putArray("content");
            }
            ((ArrayNode) current.path("content")).add(block);
        }
    }

    private final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolState> tools = new TreeMap<>();
        private String model;
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer cachedTokens;
        private String stopReason = "";

        private StreamAccumulator(String model) {
            this.model = model;
        }

        private void messageStart(JsonNode message) {
            if (message.hasNonNull("model")) {
                model = message.path("model").asText(model);
            }
            JsonNode usage = message.path("usage");
            if (usage.has("input_tokens")) {
                inputTokens = usage.path("input_tokens").asInt();
            }
            if (usage.has("cache_read_input_tokens")) {
                cachedTokens = usage.path("cache_read_input_tokens").asInt();
            }
        }

        private ToolState tool(int index) {
            return tools.computeIfAbsent(index, ignored -> new ToolState());
        }

        private void finish(ProviderConfig provider, Consumer<StreamChunk> consumer) {
            TokenUsage usage = new TokenUsage(inputTokens, outputTokens, null, null, cachedTokens);
            FinishReason finish = finishReason(stopReason);
            if (content.isEmpty() && tools.isEmpty()) {
                throw ModelBridgeException.invalidResponse(
                        "Anthropic stream returned neither text nor tool calls", provider.id(), usage);
            }
            consumer.accept(StreamChunk.done(finish, usage, model, provider.id()));
        }

        private final class ToolState {
            private String id = "";
            private String name = "";
            private final StringBuilder arguments = new StringBuilder();
        }
    }
}
