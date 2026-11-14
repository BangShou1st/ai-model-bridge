package io.github.bangshou1st.modelbridge.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Integer maxOutputTokens,
        List<ToolDefinition> tools,
        ToolChoice toolChoice,
        StructuredOutput structuredOutput,
        ReasoningOptions reasoning,
        Map<String, Object> providerOptions
) {
    public ChatRequest {
        Objects.requireNonNull(model, "model");
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
        structuredOutput = structuredOutput == null ? StructuredOutput.none() : structuredOutput;
        reasoning = reasoning == null ? ReasoningOptions.none() : reasoning;
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(providerOptions));
    }

    public ChatRequest(String model, List<Message> messages) {
        this(model, messages, null, null, List.of(), ToolChoice.AUTO,
                StructuredOutput.none(), ReasoningOptions.none(), Map.of());
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    public ChatRequest withModel(String upstreamModel) {
        return new ChatRequest(upstreamModel, messages, temperature, maxOutputTokens, tools,
                toolChoice, structuredOutput, reasoning, providerOptions);
    }

    public static final class Builder {
        private final String model;
        private List<Message> messages = List.of();
        private Double temperature;
        private Integer maxOutputTokens;
        private List<ToolDefinition> tools = List.of();
        private ToolChoice toolChoice = ToolChoice.AUTO;
        private StructuredOutput structuredOutput = StructuredOutput.none();
        private ReasoningOptions reasoning = ReasoningOptions.none();
        private final Map<String, Object> providerOptions = new LinkedHashMap<>();

        private Builder(String model) {
            this.model = Objects.requireNonNull(model, "model");
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder user(String content) {
            this.messages = List.of(Message.user(content));
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public Builder structuredOutput(StructuredOutput structuredOutput) {
            this.structuredOutput = structuredOutput;
            return this;
        }

        public Builder reasoning(ReasoningOptions reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder providerOption(String key, Object value) {
            this.providerOptions.put(key, value);
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(model, messages, temperature, maxOutputTokens, tools, toolChoice,
                    structuredOutput, reasoning, providerOptions);
        }
    }
}
