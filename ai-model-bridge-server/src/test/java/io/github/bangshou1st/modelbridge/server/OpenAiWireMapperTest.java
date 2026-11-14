package io.github.bangshou1st.modelbridge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiWireMapperTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiWireMapper wire = new OpenAiWireMapper(mapper);

    @Test
    void parsesAllSamplingControlsAndPreservesThoughtSignature() throws Exception {
        JsonNode body = mapper.readTree("""
                {
                  "model":"smart",
                  "messages":[
                    {"role":"assistant","content":"thinking","tool_calls":[
                      {"id":"call-1","type":"function","thought_signature":"sig-1",
                       "function":{"name":"lookup","arguments":"{\\"q\\":\\"x\\"}"}}
                    ]}
                  ],
                  "temperature":0.3,
                  "top_p":0.8,
                  "stop":["END"],
                  "seed":42,
                  "frequency_penalty":0.1,
                  "presence_penalty":0.2,
                  "max_completion_tokens":123
                }
                """);

        OpenAiWireMapper.ParsedChat parsed = wire.parseChat(body);

        assertThat(parsed.request().temperature()).isEqualTo(0.3);
        assertThat(parsed.request().maxOutputTokens()).isEqualTo(123);
        assertThat(parsed.request().options().topP()).isEqualTo(0.8);
        assertThat(parsed.request().options().frequencyPenalty()).isEqualTo(0.1);
        assertThat(parsed.request().options().presencePenalty()).isEqualTo(0.2);
        assertThat(parsed.request().options().seed()).isEqualTo(42);
        assertThat(parsed.request().options().stop()).containsExactly("END");
        assertThat(parsed.request().providerOptions())
                .containsEntry("seed", 42)
                .containsEntry("stop", List.of("END"));
        assertThat(parsed.request().messages().getFirst().toolCalls().getFirst().thoughtSignature())
                .isEqualTo("sig-1");
    }

    @Test
    void preservesTextAlongsideToolCallsAndEmitsThoughtSignature() throws Exception {
        ToolCall call = new ToolCall("call-1", "lookup",
                mapper.readTree("{\"q\":\"x\"}"), "sig-1");
        ChatResponse response = new ChatResponse("I will look it up", null,
                List.of(call), FinishReason.TOOL_CALLS, new TokenUsage(4, 3),
                "gemini", "gemini-test", 12, Map.of());

        JsonNode message = wire.chatCompletion(response, "smart")
                .path("choices").path(0).path("message");

        assertThat(message.path("content").asText()).isEqualTo("I will look it up");
        assertThat(message.path("tool_calls").path(0).path("thought_signature").asText())
                .isEqualTo("sig-1");
    }
}
