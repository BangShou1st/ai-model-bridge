package io.github.bangshou1st.modelbridge.core.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ReasoningOptions;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.ToolCall;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.protocol.anthropic.AnthropicAdapter;
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

class AnthropicAdapterTest {
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
    void completeMapsRolesToolsHeadersAndResponse() throws Exception {
        start(exchange -> {
            String response = """
                    {
                      "model":"claude-test",
                      "content":[
                        {"type":"text","text":"Hi"},
                        {"type":"tool_use","id":"toolu_server","name":"lookup","input":{"q":"x"}}
                      ],
                      "stop_reason":"tool_use",
                      "usage":{"input_tokens":7,"output_tokens":3,"cache_read_input_tokens":2}
                    }
                    """;
            sendJson(exchange, 200, response);
        });

        ToolCall call = new ToolCall("tool-1", "lookup", mapper.readTree("{\"q\":\"old\"}"));
        ChatRequest request = ChatRequest.builder("claude-test")
                .messages(List.of(
                        Message.system("sys one"),
                        Message.system("sys two"),
                        Message.user("hello"),
                        Message.user("again"),
                        Message.assistant("", List.of(call)),
                        Message.tool("tool-1", "lookup", "{\"ok\":true}"),
                        Message.tool("tool-2", "lookup", "{\"ok\":false}"),
                        Message.user("continue")))
                .temperature(0.2)
                .maxOutputTokens(321)
                .tools(List.of(new io.github.bangshou1st.modelbridge.core.model.ToolDefinition(
                        "lookup", "lookup a value", mapper.readTree("{\"type\":\"object\"}"))))
                .toolChoice(io.github.bangshou1st.modelbridge.core.model.ToolChoice.REQUIRED)
                .build();

        ChatResponse response = adapter(provider()).complete(provider(), request);

        assertThat(captured.path()).isEqualTo("/v1/messages");
        assertThat(captured.headers().get("x-api-key")).containsExactly("test-secret");
        assertThat(captured.headers().get("anthropic-version")).containsExactly("2023-06-01");
        JsonNode body = mapper.readTree(captured.body());
        assertThat(body.path("model").asText()).isEqualTo("claude-test");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(321);
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("system").asText()).isEqualTo("sys one\n\nsys two");
        assertThat(body.path("messages").size()).isEqualTo(3);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(0).path("content").size()).isEqualTo(2);
        assertThat(body.path("messages").path(1).path("role").asText()).isEqualTo("assistant");
        assertThat(body.path("messages").path(1).path("content").path(0).path("type").asText())
                .isEqualTo("tool_use");
        assertThat(body.path("messages").path(2).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(2).path("content").size()).isEqualTo(3);
        assertThat(body.path("messages").path(2).path("content").path(0).path("type").asText())
                .isEqualTo("tool_result");
        assertThat(body.path("messages").path(2).path("content").path(2).path("text").asText())
                .isEqualTo("continue");
        assertThat(body.path("tools").path(0).path("input_schema").path("type").asText())
                .isEqualTo("object");
        assertThat(body.path("tool_choice").path("type").asText()).isEqualTo("any");

        assertThat(response.content()).isEqualTo("Hi");
        assertThat(response.toolCalls()).singleElement()
                .satisfies(tool -> {
                    assertThat(tool.id()).isEqualTo("toolu_server");
                    assertThat(tool.name()).isEqualTo("lookup");
                    assertThat(tool.arguments().path("q").asText()).isEqualTo("x");
                });
        assertThat(response.usage().inputTokens()).isEqualTo(7);
        assertThat(response.usage().outputTokens()).isEqualTo(3);
        assertThat(response.usage().cachedInputTokens()).isEqualTo(2);
    }

    @Test
    void turnReturnsNativeToolResult() throws Exception {
        start(exchange -> sendJson(exchange, 200, """
                {
                  "model":"claude-turn",
                  "content":[{"type":"tool_use","id":"turn-tool","name":"lookup","input":{"id":"42"}}],
                  "stop_reason":"tool_use",
                  "usage":{"input_tokens":5,"output_tokens":2}
                }
                """));

        TurnResult result = adapter(provider()).turn(provider(), new TurnRequest(
                "claude-turn", List.of(Message.user("find it")),
                List.of(new io.github.bangshou1st.modelbridge.core.model.ToolDefinition(
                        "lookup", "lookup", mapper.readTree("{\"type\":\"object\"}"))),
                io.github.bangshou1st.modelbridge.core.model.ToolChoice.AUTO,
                0.0, 50, ReasoningOptions.none(), Map.of(), null));

        assertThat(result.toolCalls()).singleElement()
                .satisfies(tool -> assertThat(tool.arguments().path("id").asText()).isEqualTo("42"));
        assertThat(result.finishReason()).isEqualTo(io.github.bangshou1st.modelbridge.core.model.FinishReason.TOOL_CALLS);
    }

    @Test
    void streamReassemblesTextAndToolArgumentsAcrossFrames() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String frames = String.join("\n\n",
                    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-stream\",\"usage\":{\"input_tokens\":10}}}",
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"lookup\",\"input\":{}}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\"}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"x\\\"}\"}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}",
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":5}}",
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}") + "\n\n";
            writeFragmented(exchange.getResponseBody(), frames);
        });

        List<StreamChunk> chunks = new ArrayList<>();
        adapter(provider()).completeStream(provider(),
                ChatRequest.builder("claude-stream").user("stream").build(), chunks::add);

        String content = chunks.stream()
                .filter(chunk -> chunk.type() == StreamChunk.Type.CONTENT)
                .map(StreamChunk::delta)
                .reduce("", String::concat);
        String toolArguments = chunks.stream()
                .filter(chunk -> chunk.type() == StreamChunk.Type.TOOL_CALL_DELTA)
                .map(chunk -> chunk.toolCall().argumentsDelta())
                .reduce("", String::concat);
        assertThat(content).isEqualTo("hello");
        assertThat(toolArguments).isEqualTo("{\"q\":\"x\"}");
        assertThat(chunks.stream().filter(chunk -> chunk.type() == StreamChunk.Type.TOOL_CALL_DELTA)
                .findFirst().orElseThrow().toolCall().id()).isEqualTo("toolu_1");
        assertThat(chunks.getLast().type()).isEqualTo(StreamChunk.Type.DONE);
        assertThat(chunks.getLast().finishReason())
                .isEqualTo(io.github.bangshou1st.modelbridge.core.model.FinishReason.TOOL_CALLS);
        assertThat(chunks.getLast().usage().outputTokens()).isEqualTo(5);
    }

    @Test
    void lengthCarriesUsageAnd429IsClassified() throws Exception {
        start(exchange -> sendJson(exchange, 200, """
                {
                  "model":"claude-truncated",
                  "content":[{"type":"text","text":"partial"}],
                  "stop_reason":"max_tokens",
                  "usage":{"input_tokens":4,"output_tokens":9}
                }
                """));

        assertThatThrownBy(() -> adapter(provider()).complete(provider(),
                ChatRequest.builder("claude-truncated").user("long").build()))
                .isInstanceOfSatisfying(ModelBridgeException.class, error -> {
                    assertThat(error.category()).isEqualTo(ErrorCategory.LENGTH);
                    assertThat(error.usage().outputTokens()).isEqualTo(9);
                    assertThat(error.retryable()).isFalse();
                });

        stopServer();
        start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "3");
            sendJson(exchange, 429, "{\"error\":{\"type\":\"rate_limit_error\"}}");
        });
        assertThatThrownBy(() -> adapter(provider()).complete(provider(),
                ChatRequest.builder("claude-test").user("again").build()))
                .isInstanceOfSatisfying(ModelBridgeException.class, error -> {
                    assertThat(error.category()).isEqualTo(ErrorCategory.RATE_LIMIT);
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    private AnthropicAdapter adapter(ProviderConfig provider) {
        return new AnthropicAdapter(mapper, new HttpTransport(mapper,
                OutboundEndpointPolicy.localDevelopment(Set.of(port()))));
    }

    private ProviderConfig provider() {
        return new ProviderConfig("anthropic-test", io.github.bangshou1st.modelbridge.core.model.ProviderType.ANTHROPIC,
                baseUrl(), "test-secret", Map.of(), Duration.ofSeconds(2), Duration.ofSeconds(5),
                Map.of("anthropicVersion", "2023-06-01"));
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
            int length = Math.min(17, bytes.length - offset);
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

    private record CapturedRequest(String path, String body, Map<String, List<String>> headers) {
    }
}
