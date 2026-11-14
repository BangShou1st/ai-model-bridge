package io.github.bangshou1st.modelbridge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayIntegrationTest {
    private static final Upstream UPSTREAM = Upstream.start();
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("ai.bridge.gateway.api-keys[0]", () -> "test-key");
        registry.add("ai.bridge.security.allow-loopback", () -> "true");
        registry.add("ai.bridge.security.loopback-ports[0]", UPSTREAM::port);

        registry.add("ai.bridge.gateway.upstreams.mock.type", () -> "openai-compatible");
        registry.add("ai.bridge.gateway.upstreams.mock.base-url",
                () -> "http://127.0.0.1:" + UPSTREAM.port() + "/v1");
        registry.add("ai.bridge.gateway.upstreams.mock.api-key", () -> "upstream-secret");
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].id", () -> "mock-chat");
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].alias", () -> "fast");
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].display-name", () -> "Mock Chat");
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].context-window", () -> 128000);
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].max-output-tokens", () -> 4096);
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].supports-tools", () -> true);
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].supports-reasoning", () -> true);
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].reasoning-levels[0]", () -> "low");
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].supported-parameters[0]", () -> "tools");
        registry.add("ai.bridge.gateway.upstreams.mock.models[0].pricing.prompt", () -> "0.000001");
        registry.add("ai.bridge.gateway.upstreams.mock.models[1].id", () -> "tool-chat");
        registry.add("ai.bridge.gateway.upstreams.mock.models[1].alias", () -> "tools");
        registry.add("ai.bridge.gateway.upstreams.mock.models[2].id", () -> "embed-model");
        registry.add("ai.bridge.gateway.upstreams.mock.models[2].alias", () -> "embed");
        registry.add("ai.bridge.gateway.upstreams.mock.models[2].supports-embeddings", () -> true);
        registry.add("ai.bridge.gateway.upstreams.mock.models[2].supports-streaming", () -> false);
        registry.add("ai.bridge.gateway.upstreams.mock.models[2].supports-structured-output", () -> false);
        registry.add("ai.bridge.gateway.upstreams.mock.models[3].id", () -> "fail-chat");
        registry.add("ai.bridge.gateway.upstreams.mock.models[3].alias", () -> "failing");
        registry.add("ai.bridge.gateway.upstreams.mock.models[4].id", () -> "timeout-chat");
        registry.add("ai.bridge.gateway.upstreams.mock.models[4].alias", () -> "timing-out");
        registry.add("ai.bridge.gateway.upstreams.mock.models[5].id", () -> "slow-stream");
        registry.add("ai.bridge.gateway.upstreams.mock.models[5].alias", () -> "slow");
        registry.add("ai.bridge.gateway.upstreams.mock.models[6].id", () -> "shared-model");
        registry.add("ai.bridge.gateway.upstreams.mock.models[7].id", () -> "shadow-model");

        registry.add("ai.bridge.gateway.upstreams.mock-rerank.type", () -> "llama-cpp");
        registry.add("ai.bridge.gateway.upstreams.mock-rerank.base-url",
                () -> "http://127.0.0.1:" + UPSTREAM.port());
        registry.add("ai.bridge.gateway.upstreams.mock-rerank.models[0].id", () -> "rerank-model");
        registry.add("ai.bridge.gateway.upstreams.mock-rerank.models[0].alias", () -> "rerank");
        registry.add("ai.bridge.gateway.upstreams.mock-rerank.models[0].supports-rerank", () -> true);
        registry.add("ai.bridge.gateway.upstreams.mock-rerank.models[0].supports-streaming", () -> false);
        registry.add("ai.bridge.gateway.upstreams.mock-rerank.models[0].supports-structured-output", () -> false);

        registry.add("ai.bridge.gateway.upstreams.gemini-embed.type", () -> "gemini");
        registry.add("ai.bridge.gateway.upstreams.gemini-embed.base-url",
                () -> "http://127.0.0.1:" + UPSTREAM.port() + "/gemini");
        registry.add("ai.bridge.gateway.upstreams.gemini-embed.models[0].id",
                () -> "gemini-embedding-001");
        registry.add("ai.bridge.gateway.upstreams.gemini-embed.models[0].alias",
                () -> "gemini-embed");
        registry.add("ai.bridge.gateway.upstreams.gemini-embed.models[0].supports-embeddings",
                () -> true);
        registry.add("ai.bridge.gateway.upstreams.gemini-embed.models[0].supports-streaming",
                () -> false);
        registry.add("ai.bridge.gateway.upstreams.gemini-embed.models[0].supports-structured-output",
                () -> false);
        registry.add("ai.bridge.gateway.aliases.smart", () -> "mock/mock-chat");

        registry.add("ai.bridge.providers.core.protocol", () -> "openai-compatible");
        registry.add("ai.bridge.providers.core.base-url",
                () -> "http://127.0.0.1:" + UPSTREAM.port() + "/v1");
        registry.add("ai.bridge.providers.core.api-key", () -> "core-secret");
        registry.add("ai.bridge.providers.core.models[0].id", () -> "core-chat");
        registry.add("ai.bridge.providers.core.models[0].alias", () -> "core-fast");
        registry.add("ai.bridge.providers.core.models[1].id", () -> "shared-model");
        registry.add("ai.bridge.providers.core.models[2].id", () -> "aliased-model");
        registry.add("ai.bridge.providers.core.models[2].alias", () -> "shadow-model");
        registry.add("ai.bridge.aliases.unified[0]", () -> "core/core-chat");
    }

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry registry;

    private WebTestClient http;

    @BeforeEach
    void setUp() {
        http = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        UPSTREAM.reset();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Test
    void requiresUnifiedGatewayBearerToken() {
        http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");
    }

    @Test
    void listsRichMetadataAndAliases() {
        JsonNode response = http.get().uri("/v1/models")
                .header("Authorization", "Bearer test-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();

        JsonNode fast = find(response.path("data"), "fast");
        assertThat(fast).isNotNull();
        assertThat(fast.path("context_window").asInt()).isEqualTo(128000);
        assertThat(fast.path("max_output_tokens").asInt()).isEqualTo(4096);
        assertThat(fast.path("supports_tools").asBoolean()).isTrue();
        assertThat(fast.path("pricing").path("prompt").asText()).isEqualTo("0.000001");
        assertThat(fast.path("supported_parameters").toString()).contains("tools");
        assertThat(find(response.path("data"), "smart")).isNotNull();
        JsonNode embedding = find(response.path("data"), "embed");
        assertThat(embedding.path("supports_embeddings").asBoolean()).isTrue();
        assertThat(embedding.path("native_endpoints").toString()).contains("/v1/embeddings");
        assertThat(find(response.path("data"), "rerank").path("supports_rerank").asBoolean())
                .isTrue();
        assertThat(find(response.path("data"), "unified")).isNotNull();
    }

    @Test
    void routesAliasAndReturnsOpenAiCompatibleCompletion() throws Exception {
        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "smart",
                        "messages", List.of(Map.of("role", "user", "content", "hello"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.model").isEqualTo("smart")
                .jsonPath("$.choices[0].message.content").isEqualTo("upstream answer")
                .jsonPath("$.choices[0].message.reasoning_content").isEqualTo("reasoned")
                .jsonPath("$.usage.prompt_tokens").isEqualTo(7);

        JsonNode upstream = JSON.readTree(UPSTREAM.lastChatBody.get());
        assertThat(upstream.path("model").asText()).isEqualTo("mock-chat");
        assertThat(UPSTREAM.lastChatAuthorization.get()).isEqualTo("Bearer upstream-secret");
    }

    @Test
    void routesCoreDeclarativeProviderAndAliasThroughSameGateway() throws Exception {
        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "unified",
                        "messages", List.of(Map.of("role", "user", "content", "hello"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("unified")
                .jsonPath("$.choices[0].message.content").isEqualTo("upstream answer");

        JsonNode upstream = JSON.readTree(UPSTREAM.lastChatBody.get());
        assertThat(upstream.path("model").asText()).isEqualTo("core-chat");
        assertThat(UPSTREAM.lastChatAuthorization.get()).isEqualTo("Bearer core-secret");
    }

    @Test
    void modelCatalogExposesRoutableProviderQualifiedIdsForConflicts() throws Exception {
        JsonNode response = http.get().uri("/v1/models")
                .header("Authorization", "Bearer test-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();

        assertThat(find(response.path("data"), "mock/shared-model")).isNotNull();
        assertThat(find(response.path("data"), "core/shared-model")).isNotNull();
        assertThat(find(response.path("data"), "shared-model")).isNull();
        assertThat(find(response.path("data"), "shadow-model").path("provider").asText())
                .isEqualTo("core");
        assertThat(find(response.path("data"), "mock/shadow-model").path("provider").asText())
                .isEqualTo("mock");

        chat("mock/shared-model")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("upstream answer");
        assertThat(JSON.readTree(UPSTREAM.lastChatBody.get()).path("model").asText())
                .isEqualTo("shared-model");
        assertThat(UPSTREAM.lastChatAuthorization.get()).isEqualTo("Bearer upstream-secret");

        chat("core/shared-model")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("upstream answer");
        assertThat(JSON.readTree(UPSTREAM.lastChatBody.get()).path("model").asText())
                .isEqualTo("shared-model");
        assertThat(UPSTREAM.lastChatAuthorization.get()).isEqualTo("Bearer core-secret");

        chat("shadow-model")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("shadow-model");
        assertThat(JSON.readTree(UPSTREAM.lastChatBody.get()).path("model").asText())
                .isEqualTo("aliased-model");
        assertThat(UPSTREAM.lastChatAuthorization.get()).isEqualTo("Bearer core-secret");

        chat("mock/shadow-model")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("mock/shadow-model");
        assertThat(JSON.readTree(UPSTREAM.lastChatBody.get()).path("model").asText())
                .isEqualTo("shadow-model");
        assertThat(UPSTREAM.lastChatAuthorization.get()).isEqualTo("Bearer upstream-secret");

        chat("shared-model")
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("model_not_found")
                .jsonPath("$.error.message").value(message -> assertThat(String.valueOf(message))
                        .contains("ambiguous model id")
                        .contains("provider/model"));
    }

    @Test
    void streamsSseWithFragmentedUpstreamChunks() {
        EntityExchangeResult<String> result = http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "model", "fast",
                        "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "hello"))))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult();

        assertThat(result.getResponseBody())
                .contains("chat.completion.chunk")
                .contains("\"content\":\"Hel\"")
                .contains("\"content\":\"lo\"")
                .contains("\"finish_reason\":\"stop\"")
                .contains("[DONE]");
    }

    @Test
    void streamsOpenAiToolCallDeltas() {
        EntityExchangeResult<String> result = http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "model", "tools",
                        "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "find x")),
                        "tools", List.of(Map.of(
                                "type", "function",
                                "function", Map.of(
                                        "name", "lookup",
                                        "description", "look up a value",
                                        "parameters", Map.of("type", "object"))))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        assertThat(result.getResponseBody())
                .contains("\"tool_calls\"")
                .contains("\"name\":\"lookup\"")
                .contains("\"arguments\"")
                .contains("\"finish_reason\":\"tool_calls\"")
                .contains("[DONE]");
    }

    @Test
    void proxiesEmbeddings() {
        http.post().uri("/v1/embeddings")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "embed", "input", List.of("one", "two")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].embedding[0]").isEqualTo(0.1)
                .jsonPath("$.usage.prompt_tokens").isEqualTo(2);
    }

    @Test
    void proxiesGeminiEmbeddingsThroughTheUnifiedRouter() {
        http.post().uri("/v1/embeddings")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gemini-embed", "input", "gemini input"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].embedding[0]").isEqualTo(0.5);
    }

    @Test
    void clientCancellationClosesTheUpstreamStream() {
        WebClient client = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader("Authorization", "Bearer test-key")
                .build();
        List<ServerSentEvent<String>> events = client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "model", "slow",
                        "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "stream"))))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .takeUntil(event -> event.data() != null
                        && event.data().contains("\"content\":\"tick\""))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!UPSTREAM.slowStreamCancelled.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(UPSTREAM.slowStreamCancelled).isTrue();
    }

    @Test
    void rerankRequestsAllCandidatesAndHonorsTopN() {
        http.post().uri("/v1/rerank")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "rerank",
                        "query", "target",
                        "documents", List.of("first", "second"),
                        "top_n", 1))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("rerank")
                .jsonPath("$.results.length()").isEqualTo(1)
                .jsonPath("$.results[0].index").isEqualTo(1);

        assertThat(UPSTREAM.lastRerankTopN.get()).isEqualTo(2);
    }

    @Test
    void classifiesRateLimitAndTimeoutAndRecordsMetrics() {
        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "failing",
                        "messages", List.of(Map.of("role", "user", "content", "hello"))))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("rate_limit")
                .jsonPath("$.error.retryable").isEqualTo(true);

        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "timing-out",
                        "messages", List.of(Map.of("role", "user", "content", "hello"))))
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("timeout")
                .jsonPath("$.error.retryable").isEqualTo(true);

        assertThat(registry.get("ai.bridge.gateway.errors")
                .tag("category", "RATE_LIMIT").counter().count()).isGreaterThan(0);
        assertThat(registry.get("ai.bridge.gateway.latency")
                .tag("endpoint", "chat").tag("outcome", "error").timer().count()).isGreaterThan(0);
    }

    private static JsonNode find(JsonNode data, String id) {
        for (JsonNode item : data) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private WebTestClient.ResponseSpec chat(String model) {
        return http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", model,
                        "messages", List.of(Map.of("role", "user", "content", "hello"))))
                .exchange();
    }

    private static final class Upstream implements AutoCloseable {
        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicReference<String> lastChatBody = new AtomicReference<>("");
        private final AtomicReference<String> lastChatAuthorization = new AtomicReference<>();
        private final AtomicInteger lastRerankTopN = new AtomicInteger();
        private final AtomicBoolean slowStreamCancelled = new AtomicBoolean();

        private Upstream(HttpServer server) {
            this.server = server;
        }

        static Upstream start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                Upstream upstream = new Upstream(server);
                server.createContext("/v1/chat/completions", upstream::chat);
                server.createContext("/v1/embeddings", upstream::embeddings);
                server.createContext("/v1/rerank", upstream::rerank);
                server.createContext("/gemini/v1beta/models/gemini-embedding-001:batchEmbedContents",
                        upstream::geminiEmbeddings);
                server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
                server.start();
                return upstream;
            } catch (IOException e) {
                throw new IllegalStateException("could not start upstream test server", e);
            }
        }

        int port() {
            return server.getAddress().getPort();
        }

        void reset() {
            lastChatBody.set("");
            lastChatAuthorization.set(null);
            lastRerankTopN.set(0);
            slowStreamCancelled.set(false);
        }

        private void chat(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastChatBody.set(body);
            lastChatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request;
            try {
                request = mapper.readTree(body);
            } catch (Exception e) {
                writeJson(exchange, 400, "{\"error\":{\"message\":\"bad json\"}}");
                return;
            }
            String model = request.path("model").asText();
            if ("fail-chat".equals(model)) {
                writeJson(exchange, 429, "{\"error\":{\"message\":\"busy\"}}");
                return;
            }
            if ("timeout-chat".equals(model)) {
                writeJson(exchange, 504, "{\"error\":{\"message\":\"timeout\"}}");
                return;
            }
            if (request.path("stream").asBoolean(false)) {
                streamChat(exchange, model);
                return;
            }
            if ("tool-chat".equals(model)) {
                writeJson(exchange, 200, """
                        {"id":"chatcmpl-up","object":"chat.completion","model":"tool-chat",
                         "choices":[{"index":0,"finish_reason":"tool_calls","message":{
                           "role":"assistant","content":null,"tool_calls":[
                             {"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\\"q\\":\\"x\\"}"}}
                           ]}}],
                         "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
                        """);
                return;
            }
            String content = request.has("response_format") ? "{\"ok\":true}" : "upstream answer";
            writeJson(exchange, 200, """
                    {"id":"chatcmpl-up","object":"chat.completion","model":"mock-chat",
                     "choices":[{"index":0,"finish_reason":"stop","message":{
                       "role":"assistant","content":"%s","reasoning_content":"reasoned"}}],
                     "usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9,
                       "completion_tokens_details":{"reasoning_tokens":1}}}
                    """.formatted(content));
        }

        private void streamChat(HttpExchange exchange, String model) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                if ("slow-stream".equals(model)) {
                    try {
                        for (int frame = 0; frame < 500; frame++) {
                            writeEvent(output, """
                                    {"id":"chatcmpl-up","object":"chat.completion.chunk","model":"slow-stream",
                                     "choices":[{"index":0,"finish_reason":null,"delta":{"content":"tick"}}]}
                                    """);
                            pause();
                        }
                    } catch (IOException cancelled) {
                        slowStreamCancelled.set(true);
                    }
                    return;
                }
                if ("tool-chat".equals(model)) {
                    writeEvent(output, """
                            {"id":"chatcmpl-up","object":"chat.completion.chunk","model":"tool-chat",
                             "choices":[{"index":0,"finish_reason":null,"delta":{"tool_calls":[
                               {"index":0,"id":"call_1","type":"function","function":{
                                 "name":"lookup","arguments":"{\\"q\\":"}}]}}]}
                            """);
                    pause();
                    writeEvent(output, """
                            {"id":"chatcmpl-up","object":"chat.completion.chunk","model":"tool-chat",
                             "choices":[{"index":0,"finish_reason":null,"delta":{"tool_calls":[
                               {"index":0,"function":{"arguments":"\\"x\\"}"}}]}}]}
                            """);
                    pause();
                    writeEvent(output, """
                            {"id":"chatcmpl-up","object":"chat.completion.chunk","model":"tool-chat",
                             "choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],
                             "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
                            """);
                } else {
                    writeEvent(output, """
                            {"id":"chatcmpl-up","object":"chat.completion.chunk","model":"mock-chat",
                             "choices":[{"index":0,"finish_reason":null,"delta":{"content":"Hel"}}]}
                            """);
                    pause();
                    writeEvent(output, """
                            {"id":"chatcmpl-up","object":"chat.completion.chunk","model":"mock-chat",
                             "choices":[{"index":0,"finish_reason":null,"delta":{"content":"lo"}}]}
                            """);
                    pause();
                    writeEvent(output, """
                            {"id":"chatcmpl-up","object":"chat.completion.chunk","model":"mock-chat",
                             "choices":[{"index":0,"finish_reason":"stop","delta":{}}],
                             "usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}
                            """);
                }
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                exchange.close();
            }
        }

        private void embeddings(HttpExchange exchange) throws IOException {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            int count = request.path("input").isArray() ? request.path("input").size() : 1;
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) data.append(',');
                data.append("{\"object\":\"embedding\",\"index\":").append(i)
                        .append(",\"embedding\":[").append(i == 0 ? "0.1" : "0.2")
                        .append(",0.3]}");
            }
            writeJson(exchange, 200, "{\"object\":\"list\",\"data\":[" + data
                    + "],\"model\":\"embed-model\",\"usage\":{\"prompt_tokens\":" + count
                    + ",\"total_tokens\":" + count + "}}");
        }

        private void rerank(HttpExchange exchange) throws IOException {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            lastRerankTopN.set(request.path("top_n").asInt());
            int count = request.path("documents").size();
            StringBuilder results = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) results.append(',');
                results.append("{\"index\":").append(i)
                        .append(",\"relevance_score\":").append(i == 0 ? "0.2" : "0.9").append('}');
            }
            writeJson(exchange, 200, "{\"model\":\"rerank-model\",\"results\":[" + results
                    + "],\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}");
        }

        private void geminiEmbeddings(HttpExchange exchange) throws IOException {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            int count = request.path("requests").isArray() ? request.path("requests").size() : 1;
            StringBuilder values = new StringBuilder();
            for (int index = 0; index < count; index++) {
                if (index > 0) values.append(',');
                values.append("{\"values\":[0.5,0.25]}");
            }
            writeJson(exchange, 200, "{\"embeddings\":[" + values + "]}");
        }

        private static void writeEvent(OutputStream output, String json) throws IOException {
            String compact = JSON.readTree(json).toString();
            output.write(("data: " + compact + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        }

        private static void pause() {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
