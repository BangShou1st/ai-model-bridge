package io.github.bangshou1st.modelbridge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ContentPart;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingRequest;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.ReasoningOptions;
import io.github.bangshou1st.modelbridge.core.model.RerankRequest;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.StructuredOutput;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import io.github.bangshou1st.modelbridge.core.model.ToolDefinition;
import io.github.bangshou1st.modelbridge.core.model.ToolChoice;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiWireMapper {
    private final ObjectMapper mapper;

    public OpenAiWireMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParsedChat parseChat(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw GatewayException.badRequest("request body must be a JSON object");
        }
        String model = text(body.path("model"));
        if (model.isBlank()) {
            throw GatewayException.badRequest("model is required");
        }
        JsonNode messagesNode = body.path("messages");
        if (!messagesNode.isArray() || messagesNode.isEmpty()) {
            throw GatewayException.badRequest("messages must be a non-empty array");
        }
        List<Message> messages = new ArrayList<>();
        for (JsonNode item : messagesNode) {
            messages.add(parseMessage(item));
        }
        List<ToolDefinition> tools = parseTools(body.path("tools"));
        ToolChoice toolChoice = parseToolChoice(body.path("tool_choice"));
        StructuredOutput structured = parseStructured(body.path("response_format"));
        ReasoningOptions reasoning = new ReasoningOptions(
                blankToNull(text(body.path("reasoning_effort"))), null, null);
        Integer maxTokens = integer(body.path("max_completion_tokens"));
        if (maxTokens == null) {
            maxTokens = integer(body.path("max_tokens"));
        }
        Double temperature = decimal(body.path("temperature"));
        ChatRequest.Builder requestBuilder = ChatRequest.builder(model)
                .messages(messages)
                .tools(tools)
                .toolChoice(toolChoice)
                .structuredOutput(structured)
                .reasoning(reasoning)
                .temperature(temperature)
                .maxOutputTokens(maxTokens)
                .topP(decimal(body.path("top_p")))
                .frequencyPenalty(decimal(body.path("frequency_penalty")))
                .presencePenalty(decimal(body.path("presence_penalty")));
        Integer seed = integer(body.path("seed"));
        if (seed != null) {
            requestBuilder.seed(seed.longValue());
            requestBuilder.providerOption("seed", seed);
        }
        JsonNode stop = body.path("stop");
        if (stop.isTextual() && !stop.asText().isBlank()) {
            requestBuilder.stop(stop.asText());
            requestBuilder.providerOption("stop", stop.asText());
        } else if (stop.isArray() && !stop.isEmpty()) {
            List<String> values = new ArrayList<>();
            stop.forEach(value -> {
                if (value.isTextual()) {
                    values.add(value.asText());
                }
            });
            if (!values.isEmpty()) {
                requestBuilder.stop(values);
                requestBuilder.providerOption("stop", List.copyOf(values));
            }
        }
        ChatRequest request = requestBuilder.build();
        return new ParsedChat(request, body.path("stream").asBoolean(false), model);
    }

    public EmbeddingRequest parseEmbedding(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw GatewayException.badRequest("request body must be a JSON object");
        }
        String model = text(body.path("model"));
        if (model.isBlank()) {
            throw GatewayException.badRequest("model is required");
        }
        JsonNode input = body.path("input");
        List<String> values = new ArrayList<>();
        if (input.isTextual()) {
            values.add(input.asText());
        } else if (input.isArray()) {
            for (JsonNode item : input) {
                if (!item.isTextual()) {
                    throw GatewayException.badRequest("embedding input array must contain strings");
                }
                values.add(item.asText());
            }
        } else {
            throw GatewayException.badRequest("input must be a string or array of strings");
        }
        try {
            return new EmbeddingRequest(model, values, integer(body.path("dimensions")), Map.of());
        } catch (IllegalArgumentException e) {
            throw GatewayException.badRequest(e.getMessage());
        }
    }

    public RerankRequest parseRerank(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw GatewayException.badRequest("request body must be a JSON object");
        }
        String model = text(body.path("model"));
        String query = text(body.path("query"));
        if (model.isBlank() || query.isBlank()) {
            throw GatewayException.badRequest("model and query are required");
        }
        JsonNode documents = body.path("documents");
        if (!documents.isArray() || documents.isEmpty()) {
            throw GatewayException.badRequest("documents must be a non-empty array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode document : documents) {
            if (document.isTextual()) {
                values.add(document.asText());
            } else {
                values.add(document.toString());
            }
        }
        try {
            return new RerankRequest(model, query, values, integer(body.path("top_n")), Map.of());
        } catch (IllegalArgumentException e) {
            throw GatewayException.badRequest(e.getMessage());
        }
    }

    public ObjectNode chatCompletion(ChatResponse response, String requestedModel) {
        ObjectNode root = completionRoot(response, requestedModel);
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (response.hasToolCalls()) {
            if (response.content().isBlank()) {
                message.putNull("content");
            } else {
                message.put("content", response.content());
            }
        } else {
            message.put("content", response.content());
        }
        if (response.reasoningContent() != null && !response.reasoningContent().isBlank()) {
            message.put("reasoning_content", response.reasoningContent());
        }
        if (response.hasToolCalls()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCall call : response.toolCalls()) {
                ObjectNode item = calls.addObject();
                item.put("id", call.id());
                item.put("type", "function");
                if (call.thoughtSignature() != null && !call.thoughtSignature().isBlank()) {
                    item.put("thought_signature", call.thoughtSignature());
                }
                ObjectNode function = item.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.arguments().toString());
            }
        }
        choice.put("finish_reason", finishReason(response.finishReason()));
        addUsage(root, response.usage());
        return root;
    }

    public ObjectNode streamInitial(String id, long created, String requestedModel) {
        ObjectNode root = streamRoot(id, created, requestedModel);
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta").put("role", "assistant").put("content", "");
        choice.putNull("finish_reason");
        return root;
    }

    public ObjectNode streamChunk(StreamChunk chunk, String id, long created,
                                  String requestedModel) {
        ObjectNode root = streamRoot(id, created, requestedModel);
        if (chunk.type() == StreamChunk.Type.USAGE) {
            root.putArray("choices");
            addUsage(root, chunk.usage());
            return root;
        }
        if (chunk.type() == StreamChunk.Type.DONE) {
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.putObject("delta");
            choice.put("finish_reason", finishReason(chunk.finishReason()));
            addUsage(root, chunk.usage());
            return root;
        }
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        if (chunk.type() == StreamChunk.Type.CONTENT) {
            delta.put("content", chunk.delta());
        } else if (chunk.type() == StreamChunk.Type.REASONING) {
            delta.put("reasoning_content", chunk.delta());
        } else if (chunk.type() == StreamChunk.Type.TOOL_CALL_DELTA && chunk.toolCall() != null) {
            ArrayNode calls = delta.putArray("tool_calls");
            ObjectNode item = calls.addObject();
            item.put("index", chunk.toolCall().index());
            if (!chunk.toolCall().id().isBlank()) {
                item.put("id", chunk.toolCall().id());
            }
            item.put("type", "function");
            ObjectNode function = item.putObject("function");
            if (!chunk.toolCall().name().isBlank()) {
                function.put("name", chunk.toolCall().name());
            }
            function.put("arguments", chunk.toolCall().argumentsDelta());
            Object thoughtSignature = chunk.metadata().get("thought_signature");
            if (thoughtSignature != null && !String.valueOf(thoughtSignature).isBlank()) {
                item.put("thought_signature", String.valueOf(thoughtSignature));
            }
        }
        choice.putNull("finish_reason");
        return root;
    }

    public ObjectNode embeddingResponse(EmbeddingResult result, String requestedModel) {
        ObjectNode root = mapper.createObjectNode();
        root.put("object", "list");
        root.put("model", requestedModel);
        ArrayNode data = root.putArray("data");
        for (int index = 0; index < result.embeddings().size(); index++) {
            ObjectNode item = data.addObject();
            item.put("object", "embedding");
            item.put("index", index);
            ArrayNode vector = item.putArray("embedding");
            result.embeddings().get(index).forEach(vector::add);
        }
        addUsage(root, result.usage());
        root.put("latency_ms", result.latencyMs());
        return root;
    }

    public ObjectNode rerankResponse(RerankResult result, String requestedModel) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "rerank-" + java.util.UUID.randomUUID());
        root.put("object", "rerank");
        root.put("model", requestedModel);
        ArrayNode items = root.putArray("results");
        result.matches().forEach(match -> {
            ObjectNode item = items.addObject();
            item.put("index", match.index());
            item.put("relevance_score", match.score());
        });
        addUsage(root, result.usage());
        root.put("latency_ms", result.latencyMs());
        return root;
    }

    public ObjectNode error(String message, String type, String code, boolean retryable) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("message", message == null ? "gateway request failed" : message);
        error.put("type", type == null ? "api_error" : type);
        error.put("code", code == null ? "gateway_error" : code);
        error.put("retryable", retryable);
        return root;
    }

    public String stringify(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize gateway response", e);
        }
    }

    private Message parseMessage(JsonNode item) {
        String role = text(item.path("role")).toLowerCase();
        String content = contentText(item.path("content"));
        return switch (role) {
            case "system" -> Message.system(content);
            case "user" -> {
                List<ContentPart> parts = parseParts(item.path("content"));
                yield parts.stream().anyMatch(ContentPart.Image.class::isInstance)
                        ? Message.user(parts) : Message.user(content);
            }
            case "assistant" -> {
                List<ContentPart> parts = new ArrayList<>(parseParts(item.path("content")));
                String reasoning = text(item.path("reasoning_content"));
                if (!reasoning.isBlank()) {
                    parts.add(new ContentPart.Thinking(reasoning, null));
                }
                yield parts.stream().anyMatch(part -> part instanceof ContentPart.Image
                                || part instanceof ContentPart.Thinking)
                        ? Message.assistant(parts, parseToolCalls(item.path("tool_calls")))
                        : Message.assistant(content, parseToolCalls(item.path("tool_calls")));
            }
            case "tool" -> Message.tool(text(item.path("tool_call_id")),
                    text(item.path("name")), content);
            default -> throw GatewayException.badRequest("unsupported message role: " + role);
        };
    }

    private List<ToolCall> parseToolCalls(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw GatewayException.badRequest("tool_calls must be an array");
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            String id = text(item.path("id"));
            String name = text(item.path("function").path("name"));
            if (id.isBlank() || name.isBlank()) {
                throw GatewayException.badRequest("tool call is missing id or function.name");
            }
            String thoughtSignature = blankToNull(text(item.path("thought_signature")));
            if (thoughtSignature == null) {
                thoughtSignature = blankToNull(text(item.path("thoughtSignature")));
            }
            if (thoughtSignature == null) {
                thoughtSignature = blankToNull(text(item.path("function").path("thought_signature")));
            }
            calls.add(new ToolCall(id, name, parseArguments(item.path("function").path("arguments")),
                    thoughtSignature));
        }
        return calls;
    }

    private List<ToolDefinition> parseTools(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw GatewayException.badRequest("tools must be an array");
        }
        List<ToolDefinition> tools = new ArrayList<>();
        for (JsonNode item : node) {
            JsonNode function = item.path("function");
            String name = text(function.path("name"));
            if (name.isBlank()) {
                throw GatewayException.badRequest("tool is missing function.name");
            }
            tools.add(new ToolDefinition(name, text(function.path("description")),
                    function.path("parameters")));
        }
        return tools;
    }

    private ToolChoice parseToolChoice(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return ToolChoice.AUTO;
        }
        if (node.isObject()) {
            return ToolChoice.REQUIRED;
        }
        return switch (text(node)) {
            case "required" -> ToolChoice.REQUIRED;
            case "none" -> ToolChoice.NONE;
            default -> ToolChoice.AUTO;
        };
    }

    private StructuredOutput parseStructured(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {
            return StructuredOutput.none();
        }
        String type = text(node.path("type"));
        if ("json_object".equals(type)) {
            return StructuredOutput.jsonObject("response", true);
        }
        if ("json_schema".equals(type)) {
            JsonNode definition = node.path("json_schema");
            JsonNode schema = definition.path("schema");
            if (schema.isMissingNode() || schema.isNull()) {
                throw GatewayException.badRequest("json_schema response_format requires a schema");
            }
            return StructuredOutput.jsonSchema(text(definition.path("name")), schema, true);
        }
        return StructuredOutput.none();
    }

    private JsonNode parseArguments(JsonNode node) {
        if (node.isObject()) {
            return node;
        }
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode value = mapper.readTree(node.asText());
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("not an object");
            }
            return value;
        } catch (Exception e) {
            throw GatewayException.badRequest("tool arguments must be a JSON object");
        }
    }

    private static String contentText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    text.append(part.asText());
                } else if ("text".equals(text(part.path("type")))) {
                    text.append(text(part.path("text")));
                }
            }
            return text.toString();
        }
        return "";
    }

    private List<ContentPart> parseParts(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return List.of();
        }
        if (content.isTextual()) {
            return List.of(new ContentPart.Text(content.asText()));
        }
        if (!content.isArray()) {
            return List.of();
        }
        List<ContentPart> parts = new ArrayList<>();
        for (JsonNode part : content) {
            if (part.isTextual()) {
                parts.add(new ContentPart.Text(part.asText()));
                continue;
            }
            String type = text(part.path("type"));
            if ("text".equals(type)) {
                parts.add(new ContentPart.Text(text(part.path("text"))));
            } else if ("image_url".equals(type)) {
                JsonNode image = part.path("image_url");
                String url = image.isObject() ? text(image.path("url")) : text(image);
                if (!url.isBlank()) {
                    parts.add(ContentPart.Image.ofUrl(url, null));
                }
            } else if ("input_image".equals(type)) {
                String url = text(part.path("image_url"));
                if (url.isBlank()) {
                    url = text(part.path("url"));
                }
                if (!url.isBlank()) {
                    parts.add(ContentPart.Image.ofUrl(url, null));
                }
            }
        }
        return List.copyOf(parts);
    }

    private ObjectNode completionRoot(ChatResponse response, String requestedModel) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "chatcmpl-" + java.util.UUID.randomUUID());
        root.put("object", "chat.completion");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", requestedModel);
        root.put("provider", response.provider());
        root.put("latency_ms", response.latencyMs());
        if (!response.metadata().isEmpty()) {
            root.set("metadata", mapper.valueToTree(response.metadata()));
        }
        return root;
    }

    private ObjectNode streamRoot(String id, long created, String requestedModel) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", created);
        root.put("model", requestedModel);
        return root;
    }

    private void addUsage(ObjectNode root, TokenUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        ObjectNode node = root.putObject("usage");
        if (usage.inputTokens() != null) node.put("prompt_tokens", usage.inputTokens());
        if (usage.outputTokens() != null) node.put("completion_tokens", usage.outputTokens());
        if (usage.totalTokens() != null) node.put("total_tokens", usage.totalTokens());
        if (usage.reasoningTokens() != null) {
            node.putObject("completion_tokens_details").put("reasoning_tokens", usage.reasoningTokens());
        }
        if (usage.cachedInputTokens() != null) {
            node.putObject("prompt_tokens_details").put("cached_tokens", usage.cachedInputTokens());
        }
    }

    private static String finishReason(FinishReason reason) {
        if (reason == null) {
            return "stop";
        }
        return switch (reason) {
            case LENGTH -> "length";
            case TOOL_CALLS -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            case ERROR, UNKNOWN -> "stop";
            case STOP -> "stop";
        };
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt()
                ? null : node.asInt();
    }

    private static Double decimal(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !node.isNumber()
                ? null : node.asDouble();
    }

    public record ParsedChat(ChatRequest request, boolean stream, String requestedModel) {
    }
}
