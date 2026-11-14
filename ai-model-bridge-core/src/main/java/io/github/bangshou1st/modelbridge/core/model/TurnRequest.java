package io.github.bangshou1st.modelbridge.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Request shape for native tool calling. */
@Deprecated(since = "0.2.0", forRemoval = false)
public record TurnRequest(
        String model,
        List<Message> messages,
        List<ToolDefinition> tools,
        ToolChoice toolChoice,
        Double temperature,
        Integer maxOutputTokens,
        ReasoningOptions reasoning,
        Map<String, Object> providerOptions,
        String requestId
) {
    public TurnRequest {
        Objects.requireNonNull(model, "model");
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
        reasoning = reasoning == null ? ReasoningOptions.none() : reasoning;
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(providerOptions));
    }

    public TurnRequest(String model, List<Message> messages, List<ToolDefinition> tools) {
        this(model, messages, tools, ToolChoice.AUTO, null, null, ReasoningOptions.none(), Map.of(), null);
    }

    public ChatRequest asChatRequest() {
        return new ChatRequest(model, messages, temperature, maxOutputTokens, tools, toolChoice,
                StructuredOutput.none(), reasoning, providerOptions, requestId, null);
    }
}
