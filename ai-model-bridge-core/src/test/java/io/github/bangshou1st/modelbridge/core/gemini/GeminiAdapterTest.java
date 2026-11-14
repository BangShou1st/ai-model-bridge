package io.github.bangshou1st.modelbridge.core.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import io.github.bangshou1st.modelbridge.core.model.ToolChoice;
import io.github.bangshou1st.modelbridge.core.model.ToolDefinition;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.protocol.gemini.GeminiAdapter;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private CapturedRequest captured;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeMapsGeminiContentToolsThinkingAndUsage() throws Exception {
        start(exchange -> sendJson(exchange, 200, """
                {
                  "candidates":[{
                    "content":{"role":"model","parts":[
                      {"text":"hello"},
                      {"functionCall":{"name":"lookup","args":{"q":"x"}}}
                    ]},
                    "finishReason":"STOP"
                  }],
                  "usageMetadata":{
                    "promptTokenCount":9,
                    "candidatesTokenCount":4,
                    "totalTokenCount":13,
                    "thoughtsTokenCount":2
                  },
                  "modelVersion":"gemini-test"
                }
                """));

        JsonNode schema = mapper.readTree("""
                {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}
                """);
        ChatRequest request = ChatRequest.builder("gemini-test")
                .messages(List.of(
                        Message.system("be concise"),
                        Message.user("first"),
                        Message.user("second"),
                        Message.assistant("", List.of(new ToolCall(
                                "old-tool", "lookup", mapper.readTree("{\"q\":\"old\"}"), "sig-1"))),
                        Message.tool("old-tool", "lookup", mapper.readTree("{\"ok\":true}")),
                        Message.user("third")))
                .temperature(0.1)
                .maxOutputTokens(77)
                .tools(List.of(new ToolDefinition("lookup", "lookup value",
                        mapper.readTree("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"))))
                .toolChoice(ToolChoice.REQUIRED)
                .structuredOutput(StructuredOutput.jsonSchema("answer", schema, false))
                .reasoning(new ReasoningOptions("high", 512, null))
                .build();

        ChatResponse response = adapter().complete(provider(), request);

        assertThat(captured.path()).isEqualTo("/v1beta/models/gemini-test:generateContent");
        assertThat(captured.headers().get("x-goog-api-key")).containsExactly("gemini-secret");
        JsonNode body = mapper.readTree(captured.body());
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asText())
                .isEqualTo("be concise");
        assertThat(body.path("contents").size()).isEqualTo(3);
        assertThat(body.path("contents").path(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("contents").path(0).path("parts").size()).isEqualTo(2);
        assertThat(body.path("contents").path(1).path("role").asText()).isEqualTo("model");
        assertThat(body.path("contents").path(1).path("parts").path(0).path("functionCall")
                .path("name").asText()).isEqualTo("lookup");
        assertThat(body.path("contents").path(1).path("parts").path(0).path("thoughtSignature").asText())
                .isEqualTo("sig-1");
        assertThat(body.path("contents").path(2).path("role").asText()).isEqualTo("user");
        assertThat(body.path("contents").path(2).path("parts").path(0).path("functionResponse")
                .path("response").path("ok").asBoolean()).isTrue();
        assertThat(body.path("contents").path(2).path("parts").path(1).path("text").asText())
                .isEqualTo("third");
        assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt())
                .isEqualTo(512);
        assertThat(body.path("generationConfig").path("thinkingConfig").has("thinkingLevel")).isFalse();
        assertThat(body.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(body.path("generationConfig").path("responseJsonSchema").path("required").path(0).asText())
                .isEqualTo("answer");
        assertThat(body.path("tools").path(0).path("functionDeclarations").path(0).path("name").asText())
                .isEqualTo("lookup");
        assertThat(body.path("toolConfig").path("functionCallingConfig").path("mode").asText())
                .isEqualTo("ANY");

        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.reasoningContent()).isNull();
        assertThat(response.toolCalls()).singleElement().satisfies(tool -> {
            assertThat(tool.id()).startsWith("gemini:");
            assertThat(tool.id()).endsWith(":0");
            assertThat(tool.name()).isEqualTo("lookup");
            assertThat(tool.arguments().path("q").asText()).isEqualTo("x");
        });
        assertThat(response.usage().inputTokens()).isEqualTo(9);
        assertThat(response.usage().outputTokens()).isEqualTo(4);
        assertThat(response.usage().reasoningTokens()).isEqualTo(2);
        assertThat(response.model()).isEqualTo("gemini-test");
    }

    @Test
    void thinkingLevelIsWhitelistedAndPromptJsonAddsInstruction() throws Exception {
        start(exchange -> sendJson(exchange, 200, """
                {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":3}}
                """));

        JsonNode schema = mapper.readTree("{\"type\":\"object\"}");
        adapter().complete(provider(), ChatRequest.builder("gemini-test")
                .user("json please")
                .reasoning(new ReasoningOptions("HIGH", null, null))
                .structuredOutput(StructuredOutput.promptJson("answer", schema))
                .build());

        JsonNode body = mapper.readTree(captured.body());
        assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText())
                .isEqualTo("high");
        assertThat(body.path("generationConfig").path("responseMimeType").isMissingNode()).isTrue();
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asText())
                .contains("Return only valid JSON");
    }

    @Test
    void streamEmitsTextFunctionCallsAndDone() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String frames = String.join("\n\n",
                    "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hel\"}]}}]}",
                    "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"lo\"}]}}]}",
                    "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"lookup\",\"args\":{\"q\":\"x\"}}}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":4},\"modelVersion\":\"gemini-stream\"}")
                    + "\n\n";
            writeFragmented(exchange.getResponseBody(), frames);
        });

        List<StreamChunk> chunks = new ArrayList<>();
        adapter().completeStream(provider(), ChatRequest.builder("gemini-stream").user("stream").build(),
                chunks::add);

        assertThat(captured.path()).isEqualTo("/v1beta/models/gemini-stream:streamGenerateContent");
        assertThat(captured.query()).isEqualTo("alt=sse");
        assertThat(chunks.stream().filter(chunk -> chunk.type() == StreamChunk.Type.CONTENT)
                .map(StreamChunk::delta).reduce("", String::concat)).isEqualTo("Hello");
        assertThat(chunks.stream().filter(chunk -> chunk.type() == StreamChunk.Type.TOOL_CALL_DELTA)
                .map(chunk -> chunk.toolCall().name()).toList()).containsExactly("lookup");
        assertThat(chunks.getLast().type()).isEqualTo(StreamChunk.Type.DONE);
        assertThat(chunks.getLast().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(chunks.getLast().usage().inputTokens()).isEqualTo(3);
    }

    @Test
    void lengthCarriesUsageAndTurnIdsAreScoped() throws Exception {
        start(exchange -> sendJson(exchange, 200, """
                {"candidates":[{"content":{"parts":[{"text":"partial"}]},"finishReason":"MAX_TOKENS"}],
                 "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":9}}
                """));
        assertThatThrownBy(() -> adapter().complete(provider(),
                ChatRequest.builder("gemini-test").user("long").build()))
                .isInstanceOfSatisfying(ModelBridgeException.class, error -> {
                    assertThat(error.category()).isEqualTo(ErrorCategory.LENGTH);
                    assertThat(error.usage().outputTokens()).isEqualTo(9);
                });

        stopServer();
        start(exchange -> sendJson(exchange, 200, """
                {"candidates":[{"content":{"parts":[
                  {"functionCall":{"name":"first","args":{}}},
                  {"functionCall":{"name":"second","args":{}}}
                ]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2}}
                """));
        TurnResult result = adapter().turn(provider(), new TurnRequest(
                "gemini-test", List.of(Message.user("turn")),
                List.of(new ToolDefinition("first", "first", mapper.readTree("{\"type\":\"object\"}"))),
                ToolChoice.AUTO, 0.0, 20, ReasoningOptions.none(), Map.of(), "turn-42"));
        assertThat(result.toolCalls()).extracting(ToolCall::id)
                .containsExactly("gemini:turn-42:0", "gemini:turn-42:1");
    }

    private GeminiAdapter adapter() {
        return new GeminiAdapter(mapper, new HttpTransport(mapper,
                OutboundEndpointPolicy.localDevelopment(Set.of(port()))));
    }

    private ProviderConfig provider() {
        return new ProviderConfig("gemini-test", ProviderType.GEMINI, baseUrl(), "gemini-secret",
                Map.of(), Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of());
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                Map<String, List<String>> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((key, value) ->
                        headers.put(key.toLowerCase(), List.copyOf(value)));
                captured = new CapturedRequest(exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getQuery(),
                        new String(bytes, StandardCharsets.UTF_8), headers);
                handler.handle(exchange);
            } catch (Throwable error) {
                try {
                    sendJson(exchange, 500, "{\"error\":\"test handler failed\"}");
                } catch (IOException ignored) {
                    // Test teardown closes the exchange.
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void writeFragmented(OutputStream output, String frames) throws Exception {
        byte[] bytes = frames.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < bytes.length) {
            int length = Math.min(19, bytes.length - offset);
            output.write(bytes, offset, length);
            output.flush();
            offset += length;
            Thread.sleep(3);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private record CapturedRequest(String path, String query, String body, Map<String, List<String>> headers) {
    }
}
