package io.github.bangshou1st.modelbridge.core.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.StructuredOutput;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.model.ToolChoice;
import io.github.bangshou1st.modelbridge.core.model.ToolDefinition;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.protocol.openai.OpenAiCompatibleAdapter;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ProviderConfig provider;
    private OpenAiCompatibleAdapter adapter;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeMapsMessagesUsageReasoningAndWhitelistedExtensions() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", """
                {
                  "id":"cmpl_1",
                  "model":"upstream-model",
                  "choices":[{
                    "index":0,
                    "message":{
                      "role":"assistant",
                      "content":"Hello from upstream",
                      "reasoning_content":"brief reasoning"
                    },
                    "finish_reason":"stop"
                  }],
                  "usage":{
                    "prompt_tokens":10,
                    "completion_tokens":4,
                    "total_tokens":14,
                    "completion_tokens_details":{"reasoning_tokens":2},
                    "prompt_tokens_details":{"cached_tokens":3}
                  }
                }
                """));

        ChatRequest request = ChatRequest.builder("request-model")
                .messages(List.of(Message.system("be exact"), Message.user("say hello")))
                .temperature(0.2)
                .maxOutputTokens(64)
                .providerOption("enable_thinking", true)
                .providerOption("reasoning_effort", "high")
                .providerOption("not_allowed", "must-not-leak")
                .build();

        ChatResponse response = adapter.complete(provider, request);

        assertThat(response.content()).isEqualTo("Hello from upstream");
        assertThat(response.reasoningContent()).isEqualTo("brief reasoning");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.model()).isEqualTo("upstream-model");
        assertThat(response.provider()).isEqualTo("test-upstream");
        assertThat(response.usage().inputTokens()).isEqualTo(10);
        assertThat(response.usage().outputTokens()).isEqualTo(4);
        assertThat(response.usage().reasoningTokens()).isEqualTo(2);
        assertThat(response.usage().cachedInputTokens()).isEqualTo(3);
        assertThat(response.metadata()).containsEntry("structuredOutputFallback", false);
        assertThat(authorization).containsExactly("Bearer sk-test");

        JsonNode body = requests.getFirst();
        assertThat(body.path("model").asText()).isEqualTo("request-model");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("say hello");
        assertThat(body.path("enable_thinking").asBoolean()).isTrue();
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(body.has("not_allowed")).isFalse();
        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void structuredOutputFallsBackToPromptJsonAfterBadRequest() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        start(exchange -> {
            if (attempts.getAndIncrement() == 0) {
                respond(exchange, 400, "application/json", "{\"error\":\"response_format unsupported\"}");
            } else {
                respond(exchange, 200, "application/json", """
                        {
                          "model":"upstream-model",
                          "choices":[{
                            "index":0,
                            "message":{"role":"assistant","content":"{\\"answer\\":\\"ok\\"}"},
                            "finish_reason":"stop"
                          }],
                          "usage":{"prompt_tokens":7,"completion_tokens":5}
                        }
                        """);
            }
        });

        JsonNode schema = mapper.readTree("""
                {
                  "type":"object",
                  "required":["answer"],
                  "properties":{"answer":{"type":"string"}}
                }
                """);
        ChatRequest request = ChatRequest.builder("request-model")
                .messages(List.of(Message.system("return a result"), Message.user("answer now")))
                .structuredOutput(StructuredOutput.jsonSchema("answer_schema", schema, true))
                .build();

        ChatResponse response = adapter.complete(provider, request);

        assertThat(response.content()).isEqualTo("{\"answer\":\"ok\"}");
        assertThat(response.metadata()).containsEntry("structuredOutputFallback", true);
        assertThat(attempts).hasValue(2);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("response_format").path("type").asText())
                .isEqualTo("json_schema");
        assertThat(requests.get(1).has("response_format")).isFalse();
        String prompt = requests.get(1).path("messages").get(0).path("content").asText();
        assertThat(prompt).contains("JSON Schema").contains("answer");
    }

    @Test
    void lengthFailureCarriesUsage() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", """
                {
                  "model":"upstream-model",
                  "choices":[{
                    "index":0,
                    "message":{"role":"assistant","content":"partial"},
                    "finish_reason":"length"
                  }],
                  "usage":{"prompt_tokens":12,"completion_tokens":99,"total_tokens":111}
                }
                """));

        ChatRequest request = ChatRequest.builder("request-model").user("write more").build();
        assertThatThrownBy(() -> adapter.complete(provider, request))
                .isInstanceOfSatisfying(ModelBridgeException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ErrorCategory.LENGTH);
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.usage().inputTokens()).isEqualTo(12);
                    assertThat(failure.usage().outputTokens()).isEqualTo(99);
                });
    }

    @Test
    void rateLimitIsClassifiedWithRetryAfter() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "3");
            respond(exchange, 429, "application/json", "{\"error\":\"quota\"}");
        });

        assertThatThrownBy(() -> adapter.complete(provider,
                ChatRequest.builder("request-model").user("hello").build()))
                .isInstanceOfSatisfying(ModelBridgeException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ErrorCategory.RATE_LIMIT);
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.upstreamStatus()).isEqualTo(429);
                    assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void gatewayTimeoutIsClassifiedAsRetryableTimeout() throws Exception {
        start(exchange -> respond(exchange, 504, "application/json", "{\"error\":\"timeout\"}"));

        assertThatThrownBy(() -> adapter.complete(provider,
                ChatRequest.builder("request-model").user("hello").build()))
                .isInstanceOfSatisfying(ModelBridgeException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ErrorCategory.TIMEOUT);
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.upstreamStatus()).isEqualTo(504);
                });
    }

    @Test
    void completeStreamReassemblesFragmentedSseAndEmitsDone() throws Exception {
        start(exchange -> {
            String frameOne = """
                    {"model":"stream-model","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel","reasoning_content":"thi"}}]}
                    """.strip();
            String frameTwo = """
                    {"model":"stream-model","choices":[{"index":0,"delta":{"content":"lo","reasoning_content":"nk"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
                    """.strip();
            sse(exchange, "data: " + frameOne + "\n\n"
                    + "data: " + frameTwo + "\n\n"
                    + "data: [DONE]\n\n");
        });

        List<StreamChunk> chunks = new ArrayList<>();
        adapter.completeStream(provider,
                ChatRequest.builder("request-model").user("hello").maxOutputTokens(12).build(),
                chunks::add);

        assertThat(chunks.stream().filter(chunk -> chunk.type() == StreamChunk.Type.CONTENT)
                .map(StreamChunk::delta).collect(Collectors.joining())).isEqualTo("Hello");
        assertThat(chunks.stream().filter(chunk -> chunk.type() == StreamChunk.Type.REASONING)
                .map(StreamChunk::delta).collect(Collectors.joining())).isEqualTo("think");
        assertThat(chunks.getLast().type()).isEqualTo(StreamChunk.Type.DONE);
        assertThat(chunks.getLast().finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(chunks.getLast().usage().inputTokens()).isEqualTo(5);
        assertThat(chunks.getLast().usage().outputTokens()).isEqualTo(2);
        JsonNode body = requests.getFirst();
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void streamedToolCallFragmentsAreAccumulatedAndValidated() throws Exception {
        start(exchange -> {
            ObjectNode first = mapper.createObjectNode().put("model", "tool-model");
            ObjectNode firstChoice = first.putArray("choices").addObject().put("index", 0);
            ObjectNode firstDelta = firstChoice.putObject("delta");
            ObjectNode firstCall = firstDelta.putArray("tool_calls").addObject()
                    .put("index", 0).put("id", "call_1");
            firstCall.putObject("function").put("name", "lookup")
                    .put("arguments", "{\"city\":");

            ObjectNode second = mapper.createObjectNode().put("model", "tool-model");
            ObjectNode secondChoice = second.putArray("choices").addObject().put("index", 0);
            ObjectNode secondDelta = secondChoice.putObject("delta");
            secondDelta.putArray("tool_calls").addObject().put("index", 0)
                    .putObject("function").put("arguments", "\"Paris\"}");

            ObjectNode finish = mapper.createObjectNode().put("model", "tool-model");
            ObjectNode finishChoice = finish.putArray("choices").addObject().put("index", 0);
            finishChoice.putObject("delta");
            finishChoice.put("finish_reason", "tool_calls");
            finish.putObject("usage").put("prompt_tokens", 9).put("completion_tokens", 6);

            sse(exchange,
                    "data: " + mapper.writeValueAsString(first) + "\n\n"
                            + "data: " + mapper.writeValueAsString(second) + "\n\n"
                            + "data: " + mapper.writeValueAsString(finish) + "\n\n"
                            + "data: [DONE]\n\n");
        });

        List<StreamChunk> chunks = new ArrayList<>();
        adapter.completeStream(provider,
                ChatRequest.builder("request-model").user("look it up").build(), chunks::add);

        String arguments = chunks.stream()
                .filter(chunk -> chunk.type() == StreamChunk.Type.TOOL_CALL_DELTA)
                .map(chunk -> chunk.toolCall().argumentsDelta())
                .collect(Collectors.joining());
        assertThat(arguments).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(chunks.getLast().type()).isEqualTo(StreamChunk.Type.DONE);
        assertThat(chunks.getLast().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    void turnParsesNativeToolCallsAndSendsToolDefinitions() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", """
                {
                  "model":"tool-model",
                  "choices":[{
                    "index":0,
                    "message":{
                      "role":"assistant",
                      "content":null,
                      "tool_calls":[{
                        "id":"call_9",
                        "type":"function",
                        "function":{"name":"weather","arguments":"{\\"city\\":\\"Paris\\"}"}
                      }]
                    },
                    "finish_reason":"tool_calls"
                  }],
                  "usage":{"prompt_tokens":11,"completion_tokens":8}
                }
                """));

        ToolDefinition tool = new ToolDefinition("weather", "Get weather",
                mapper.readTree("""
                        {"type":"object","properties":{"city":{"type":"string"}}}
                        """));
        TurnRequest request = new TurnRequest("request-model",
                List.of(Message.system("use the tool"), Message.user("weather in Paris")),
                List.of(tool), ToolChoice.REQUIRED, 0.0, 40, null, java.util.Map.of(), "turn-1");

        TurnResult result = adapter.turn(provider, request);

        assertThat(result.content()).isEmpty();
        assertThat(result.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().id()).isEqualTo("call_9");
        assertThat(result.toolCalls().getFirst().name()).isEqualTo("weather");
        assertThat(result.toolCalls().getFirst().arguments().path("city").asText())
                .isEqualTo("Paris");
        assertThat(result.usage()).isEqualTo(new TokenUsage(11, 8));

        JsonNode body = requests.getFirst();
        assertThat(body.path("tool_choice").asText()).isEqualTo("required");
        assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(body.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("weather");
        assertThat(body.path("stream").asBoolean()).isFalse();
    }

    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(mapper.readTree(body));
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            handler.handle(exchange);
        });
        server.start();
        int port = server.getAddress().getPort();
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(
                true, Set.of("127.0.0.1"), Set.of(port));
        HttpTransport transport = new HttpTransport(mapper, policy);
        adapter = new OpenAiCompatibleAdapter(mapper, transport);
        provider = new ProviderConfig("test-upstream", ProviderType.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + port, "sk-test", java.util.Map.of(), null,
                Duration.ofSeconds(5), java.util.Map.of());
    }

    private static void respond(HttpExchange exchange, int status, String contentType,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void sse(HttpExchange exchange, String payload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < bytes.length; offset += 7) {
                output.write(bytes, offset, Math.min(7, bytes.length - offset));
                output.flush();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            exchange.close();
        }
    }
}
