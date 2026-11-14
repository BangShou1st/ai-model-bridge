package io.github.bangshou1st.modelbridge.core.protocol.openai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.http.Endpoints;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.client.StreamHandles;
import io.github.bangshou1st.modelbridge.core.model.ChatOptions;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ContentPart;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ReasoningOptions;
import io.github.bangshou1st.modelbridge.core.model.Role;
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

/** OpenAI Chat Completions wire protocol, including streamed and native-tool calls. */
public final class OpenAiCompatibleAdapter implements ChatProtocolAdapter {
    protected final ObjectMapper mapper;
    protected final HttpTransport http;

    public OpenAiCompatibleAdapter(ObjectMapper mapper, HttpTransport http) {
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    @Override
    public Set<ProtocolId> protocols() {
        return Set.of(ProtocolId.OPENAI_COMPATIBLE);
    }

    @Override
    public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
        long started = System.nanoTime();
        return completeOnce(provider, request, request.structuredOutput(), started);
    }

    @Override
    public StreamHandle stream(ProviderConfig provider, ChatRequest request,
                               Consumer<StreamChunk> consumer) {
        return StreamHandles.start(StreamHandles.sharedExecutor(),
                handle -> completeStreamInternal(provider, request, consumer, handle));
    }

    @Override
    public void completeStream(ProviderConfig provider, ChatRequest request,
                               Consumer<StreamChunk> consumer) {
        stream(provider, request, consumer).await();
    }

    private void completeStreamInternal(ProviderConfig provider, ChatRequest request,
                                        Consumer<StreamChunk> consumer, StreamHandle handle) {
        ObjectNode body = streamBody(request);
        StreamState state = new StreamState(request.model());
        http.postSse(provider, endpoint(provider), headers(provider), body, event -> {
            if ("[DONE]".equals(event.data())) {
                state.sawDone = true;
                return;
            }
            JsonNode data = parseEvent(event.data(), provider);
            if (data.hasNonNull("error") || "error".equals(event.event())) {
                throw ModelBridgeException.invalidResponse(
                        "upstream stream error", provider.id(), state.usage);
            }
            if (data.hasNonNull("model")) {
                state.model = data.path("model").asText(state.model);
            }
            if (data.has("usage") && !data.path("usage").isNull()) {
                state.usage = usage(data.path("usage"));
                consumer.accept(new StreamChunk(StreamChunk.Type.USAGE, "", null, null,
                        state.usage, state.model, provider.id(), Map.of()));
            }
            JsonNode choice = data.path("choices").path(0);
            if (choice.isMissingNode() || choice.isNull()) {
                return;
            }
            state.sawChoice = true;
            JsonNode delta = choice.path("delta");
            String content = text(delta.path("content"));
            if (!content.isEmpty()) {
                state.content.append(content);
                consumer.accept(StreamChunk.content(content, state.model, provider.id()));
            }
            String reasoning = text(delta.path("reasoning_content"));
            if (reasoning.isEmpty()) {
                reasoning = text(delta.path("reasoning"));
            }
            if (!reasoning.isEmpty()) {
                state.reasoning.append(reasoning);
                consumer.accept(StreamChunk.reasoning(reasoning, state.model, provider.id()));
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode node : toolCalls) {
                    if (!node.path("index").canConvertToInt() || node.path("index").asInt() < 0) {
                        throw ModelBridgeException.invalidResponse(
                                "stream tool call is missing a valid index", provider.id(), state.usage);
                    }
                    int index = node.path("index").asInt();
                    ToolAccumulator accumulator = state.toolCalls.computeIfAbsent(index,
                            ignored -> new ToolAccumulator());
                    String id = node.path("id").asText("");
                    String toolName = node.path("function").path("name").asText("");
                    String arguments = node.path("function").path("arguments").asText("");
                    mergeFragment(accumulator.id, id);
                    mergeFragment(accumulator.name, toolName);
                    accumulator.arguments.append(arguments);
                    consumer.accept(StreamChunk.toolCall(
                            new ToolCallDelta(index, id, toolName, arguments), state.model, provider.id()));
                }
            }
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty()) {
                state.finishReason = finishReason(finish);
            }
        }, handle);

        if (handle.isCancelled()) {
            return;
        }
        if (!state.sawChoice) {
            throw ModelBridgeException.invalidResponse(
                    "stream returned no choices", provider.id(), state.usage);
        }
        if (state.finishReason == null || state.finishReason == FinishReason.UNKNOWN
                || state.finishReason == FinishReason.ERROR) {
            throw ModelBridgeException.invalidResponse(
                    "stream ended without a valid finish_reason", provider.id(), state.usage);
        }
        if (state.content.isEmpty() && state.toolCalls.isEmpty()) {
            throw ModelBridgeException.invalidResponse(
                    "stream returned neither text nor tool calls", provider.id(), state.usage);
        }
        validateStreamToolCalls(state, provider);
        if (!state.doneEmitted) {
            state.doneEmitted = true;
            consumer.accept(StreamChunk.done(
                    state.finishReason, state.usage, state.model, provider.id()));
        }
    }

    private ChatResponse completeOnce(ProviderConfig provider, ChatRequest request,
                                      StructuredOutput structured, long started) {
        JsonNode response = http.postJson(provider, endpoint(provider), headers(provider),
                chatBody(request, structured));
        return parseResponse(provider, request, response, structured, started);
    }

    private ChatResponse parseResponse(ProviderConfig provider, ChatRequest request, JsonNode response,
                                       StructuredOutput structured, long started) {
        JsonNode choice = response.path("choices").path(0);
        if (!choice.isObject()) {
            throw ModelBridgeException.invalidResponse("missing choices[0]", provider.id(), null);
        }
        JsonNode message = choice.path("message");
        TokenUsage usage = usage(response.path("usage"));
        FinishReason finish = finishReason(choice.path("finish_reason").asText(""));
        String content = text(message.path("content"));
        String reasoning = text(message.path("reasoning_content"));
        if (reasoning.isEmpty()) {
            reasoning = text(message.path("reasoning"));
        }
        List<ToolCall> calls = toolCalls(message.path("tool_calls"), provider, usage);
        if (content.isBlank() && calls.isEmpty()) {
            throw ModelBridgeException.invalidResponse(
                    "model returned neither text nor tool calls", provider.id(), usage);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("structuredOutputFallback", false);
        if (structured.enabled()) {
            metadata.put("structuredOutputMode", structured.mode().name());
        }
        return new ChatResponse(content, reasoning.isBlank() ? null : reasoning, calls, finish, usage,
                provider.id(), response.path("model").asText(request.model()), elapsedMs(started),
                metadata);
    }

    private ObjectNode chatBody(ChatRequest request, StructuredOutput structured) {
        ObjectNode body = baseBody(request, false);
        addMessages(body.putArray("messages"), request.messages(), promptInstruction(structured));
        addTools(body, request.tools(), request.toolChoice());
        addStructuredOutput(body, structured, request.options().strictJsonSchema());
        addExtensions(body, request);
        return body;
    }

    private ObjectNode streamBody(ChatRequest request) {
        ObjectNode body = baseBody(request, true);
        addMessages(body.putArray("messages"), request.messages(),
                promptInstruction(request.structuredOutput()));
        addTools(body, request.tools(), request.toolChoice());
        addStructuredOutput(body, request.structuredOutput(),
                request.options().strictJsonSchema());
        addExtensions(body, request);
        return body;
    }

    private ObjectNode baseBody(ChatRequest request, boolean stream) {
        ChatOptions options = request.options();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        if (options.temperature() != null) {
            body.put("temperature", options.temperature());
        }
        if (options.maxOutputTokens() != null) {
            body.put("max_tokens", options.maxOutputTokens());
        }
        body.put("stream", stream);
        if (stream && options.includeStreamUsage()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        return body;
    }

    private void addMessages(ArrayNode messages, List<Message> source, String instruction) {
        if (instruction != null && !instruction.isBlank()) {
            messages.addObject().put("role", "system").put("content", instruction);
        }
        for (Message message : source) {
            switch (message.role()) {
                case SYSTEM, USER -> {
                    ObjectNode node = messages.addObject()
                            .put("role", message.role() == Role.SYSTEM ? "system" : "user");
                    addContent(node, message, false);
                }
                case ASSISTANT -> {
                    ObjectNode node = messages.addObject().put("role", "assistant");
                    addContent(node, message, true);
                    if (!message.toolCalls().isEmpty()) {
                        ArrayNode calls = node.putArray("tool_calls");
                        for (ToolCall call : message.toolCalls()) {
                            ObjectNode item = calls.addObject()
                                    .put("id", call.id())
                                    .put("type", "function");
                            if (call.thoughtSignature() != null
                                    && !call.thoughtSignature().isBlank()) {
                                item.put("thought_signature", call.thoughtSignature());
                            }
                            ObjectNode function = item.putObject("function");
                            function.put("name", call.name());
                            function.put("arguments", call.arguments().toString());
                        }
                    }
                }
                case TOOL -> {
                    ObjectNode node = messages.addObject()
                            .put("role", "tool")
                            .put("tool_call_id", message.toolCallId() == null
                                    ? "" : message.toolCallId());
                    if (message.name() != null && !message.name().isBlank()) {
                        node.put("name", message.name());
                    }
                    node.put("content", message.toolResult() == null
                            ? message.content() : message.toolResult().toString());
                }
            }
        }
    }

    private void addContent(ObjectNode target, Message message, boolean assistant) {
        List<ContentPart> parts = message.parts();
        List<ContentPart> renderable = parts.stream()
                .filter(part -> part instanceof ContentPart.Image
                        || (part instanceof ContentPart.Text text && !text.text().isEmpty()))
                .toList();
        if (renderable.isEmpty()) {
            if (!assistant && !message.content().isEmpty()) {
                target.put("content", message.content());
            }
        } else if (renderable.size() == 1 && renderable.getFirst() instanceof ContentPart.Text text) {
            target.put("content", text.text());
        } else {
            ArrayNode content = target.putArray("content");
            for (ContentPart part : renderable) {
                if (part instanceof ContentPart.Text text) {
                    content.addObject().put("type", "text").put("text", text.text());
                } else if (part instanceof ContentPart.Image image) {
                    ObjectNode item = content.addObject().put("type", "image_url");
                    String url = image.url();
                    if (url == null || url.isBlank()) {
                        String mediaType = image.mediaType() == null
                                ? "image/png" : image.mediaType();
                        url = "data:" + mediaType + ";base64," + image.base64Data();
                    }
                    item.putObject("image_url").put("url", url);
                }
            }
        }
        if (assistant) {
            String reasoning = message.parts().stream()
                    .filter(ContentPart.Thinking.class::isInstance)
                    .map(ContentPart.Thinking.class::cast)
                    .map(ContentPart.Thinking::text)
                    .collect(java.util.stream.Collectors.joining());
            if (!reasoning.isEmpty()) {
                target.put("reasoning_content", reasoning);
            }
        }
    }

    private void addTools(ObjectNode body, List<ToolDefinition> tools, ToolChoice choice) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        ArrayNode array = body.putArray("tools");
        for (ToolDefinition tool : tools) {
            ObjectNode item = array.addObject().put("type", "function");
            ObjectNode function = item.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.inputSchema());
        }
        body.put("tool_choice", switch (choice == null ? ToolChoice.AUTO : choice) {
            case AUTO -> "auto";
            case REQUIRED -> "required";
            case NONE -> "none";
        });
    }

    private void addStructuredOutput(ObjectNode body, StructuredOutput structured,
                                     boolean strictJsonSchema) {
        if (structured == null || !structured.enabled()) {
            return;
        }
        switch (structured.mode()) {
            case JSON_OBJECT -> body.putObject("response_format").put("type", "json_object");
            case JSON_SCHEMA -> {
                ObjectNode format = body.putObject("response_format");
                format.put("type", "json_schema");
                ObjectNode value = format.putObject("json_schema");
                value.put("name", structured.name() == null || structured.name().isBlank()
                        ? "response" : structured.name());
                value.put("strict", strictJsonSchema);
                value.set("schema", structured.schema());
            }
            case NONE, PROMPT_JSON -> {
                // Prompt-only mode deliberately emits no response_format field.
            }
        }
    }

    private void addExtensions(ObjectNode body, ChatRequest request) {
        ChatOptions options = request.options();
        Map<String, Object> safeOptions = request.providerOptions();
        if (options.topP() != null) body.put("top_p", options.topP());
        if (!options.stop().isEmpty()) body.set("stop", mapper.valueToTree(options.stop()));
        if (options.seed() != null) body.put("seed", options.seed());
        if (options.frequencyPenalty() != null) {
            body.put("frequency_penalty", options.frequencyPenalty());
        }
        if (options.presencePenalty() != null) {
            body.put("presence_penalty", options.presencePenalty());
        }
        for (String key : List.of("top_p", "stop", "seed", "frequency_penalty",
                "presence_penalty", "enable_thinking", "reasoning_effort")) {
            Object value = safeOptions.get(key);
            if (value != null && !body.has(key)) {
                body.set(key, mapper.valueToTree(value));
            }
        }
        options.extraBody().forEach((key, value) -> {
            if (!isReservedBodyKey(key) && !body.has(key)) {
                body.set(key, mapper.valueToTree(value));
            }
        });
        ReasoningOptions reasoning = request.reasoning();
        Object thinkingOption = safeOptions.get("enable_thinking");
        if (thinkingOption instanceof Boolean enabled) {
            body.put("enable_thinking", enabled);
        } else if (reasoning != null && reasoning.enabled() != null) {
            body.put("enable_thinking", reasoning.enabled());
        }
        Object effortOption = safeOptions.get("reasoning_effort");
        if (effortOption instanceof String effort && !effort.isBlank()) {
            body.put("reasoning_effort", effort);
        } else if (reasoning != null && reasoning.effort() != null
                && !reasoning.effort().isBlank()) {
            body.put("reasoning_effort", reasoning.effort());
        }
    }

    private static boolean isReservedBodyKey(String key) {
        return switch (key == null ? "" : key.toLowerCase()) {
            case "model", "messages", "stream", "stream_options", "tools", "tool_choice",
                    "response_format", "max_tokens", "max_completion_tokens", "input" -> true;
            default -> false;
        };
    }

    private static String promptInstruction(StructuredOutput structured) {
        if (structured == null || !structured.enabled()
                || structured.mode() != StructuredOutput.Mode.PROMPT_JSON) {
            return null;
        }
        if (structured.schema() != null) {
            return "Return only one valid JSON value, with no Markdown fences or explanatory text. "
                    + "The value must satisfy this JSON Schema:\n" + structured.schema();
        }
        return "Return only one valid JSON object, with no Markdown fences or explanatory text.";
    }

    private JsonNode parseEvent(String data, ProviderConfig provider) {
        try {
            JsonNode value = mapper.readTree(data);
            if (value == null || !value.isObject()) {
                throw ModelBridgeException.invalidResponse(
                        "stream event is not a JSON object", provider.id(), null);
            }
            return value;
        } catch (ModelBridgeException e) {
            throw e;
        } catch (Exception e) {
            throw ModelBridgeException.invalidResponse(
                    "stream event is not valid JSON", provider.id(), null);
        }
    }

    private List<ToolCall> toolCalls(JsonNode node, ProviderConfig provider, TokenUsage usage) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw ModelBridgeException.invalidResponse(
                    "tool_calls is not an array", provider.id(), usage);
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            String id = item.path("id").asText("");
            String name = item.path("function").path("name").asText("");
            if (id.isBlank() || name.isBlank()) {
                throw ModelBridgeException.invalidResponse(
                        "tool call is missing id or name", provider.id(), usage);
            }
            JsonNode arguments = strictObject(
                    item.path("function").path("arguments"), provider, usage);
            String signature = text(item.path("thought_signature"));
            if (signature.isEmpty()) {
                signature = text(item.path("thoughtSignature"));
            }
            calls.add(new ToolCall(id, name, arguments,
                    signature.isEmpty() ? null : signature));
        }
        return calls;
    }

    private JsonNode strictObject(JsonNode arguments, ProviderConfig provider, TokenUsage usage) {
        if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
            return mapper.createObjectNode();
        }
        if (arguments.isObject()) {
            return arguments.deepCopy();
        }
        if (!arguments.isTextual()) {
            throw ModelBridgeException.invalidResponse(
                    "tool arguments are not JSON", provider.id(), usage);
        }
        try (JsonParser parser = mapper.createParser(arguments.asText())) {
            JsonNode value = mapper.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw new IllegalArgumentException("not a single object");
            }
            return value;
        } catch (Exception e) {
            throw ModelBridgeException.invalidResponse(
                    "tool arguments are not a JSON object", provider.id(), usage);
        }
    }

    private static String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray()) {
            StringBuilder result = new StringBuilder();
            for (JsonNode part : value) {
                if (part.isTextual()) {
                    result.append(part.asText());
                } else if ("text".equals(part.path("type").asText(""))) {
                    result.append(part.path("text").asText(""));
                }
            }
            return result.toString();
        }
        return "";
    }

    private static TokenUsage usage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.empty();
        }
        Integer input = integer(usage.path("prompt_tokens"));
        Integer output = integer(usage.path("completion_tokens"));
        Integer total = integer(usage.path("total_tokens"));
        Integer reasoning = integer(
                usage.path("completion_tokens_details").path("reasoning_tokens"));
        Integer cached = integer(usage.path("prompt_tokens_details").path("cached_tokens"));
        return new TokenUsage(input, output, total, reasoning, cached);
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt()
                ? null : node.asInt();
    }

    private static FinishReason finishReason(String reason) {
        if (reason == null) {
            return FinishReason.UNKNOWN;
        }
        return switch (reason) {
            case "stop" -> FinishReason.STOP;
            case "length", "max_tokens" -> FinishReason.LENGTH;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case "error" -> FinishReason.ERROR;
            default -> FinishReason.UNKNOWN;
        };
    }

    private void validateStreamToolCalls(StreamState state, ProviderConfig provider) {
        for (ToolAccumulator accumulator : state.toolCalls.values()) {
            if (accumulator.id.isEmpty() || accumulator.name.isEmpty()) {
                throw ModelBridgeException.invalidResponse(
                        "stream tool call is missing id or name", provider.id(), state.usage);
            }
            String arguments = accumulator.arguments.isEmpty()
                    ? "{}" : accumulator.arguments.toString();
            strictObject(mapper.getNodeFactory().textNode(arguments), provider, state.usage);
        }
    }

    private static String endpoint(ProviderConfig provider) {
        return Endpoints.endpoint(provider, "chatPath", "/v1", "/chat/completions");
    }

    private Map<String, String> headers(ProviderConfig provider) {
        Map<String, String> headers = new LinkedHashMap<>(provider.headers());
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + provider.apiKey());
        }
        return headers;
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * Tool names and ids arrive either as fragments or as repeated full values, depending on the
     * compatible server. Merge both shapes without duplicating a repeated full value.
     */
    private static void mergeFragment(StringBuilder target, String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return;
        }
        String current = target.toString();
        if (current.equals(fragment) || current.startsWith(fragment)) {
            return;
        }
        if (fragment.startsWith(current)) {
            target.setLength(0);
            target.append(fragment);
            return;
        }
        target.append(fragment);
    }

    private static final class ToolAccumulator {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    private static final class StreamState {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, ToolAccumulator> toolCalls = new TreeMap<>();
        private String model;
        private TokenUsage usage = TokenUsage.empty();
        private FinishReason finishReason;
        private boolean sawChoice;
        private boolean sawDone;
        private boolean doneEmitted;

        private StreamState(String model) {
            this.model = model;
        }
    }

}
