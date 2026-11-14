package io.github.bangshou1st.modelbridge.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bangshou1st.modelbridge.core.client.ChatModel;
import io.github.bangshou1st.modelbridge.core.client.EmbeddingModel;
import io.github.bangshou1st.modelbridge.core.client.RerankModel;
import io.github.bangshou1st.modelbridge.core.config.ModelRouter;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.NetworkPolicyMode;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Phase3RoutingIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void yamlBindsProvidersModelsAliasesAndInheritsOptionsThroughRequest() throws Exception {
        AtomicReference<JsonNode> upstreamRequest = new AtomicReference<>();
        AtomicReference<String> upstreamPath = new AtomicReference<>();
        startServer(exchange -> {
            upstreamPath.set(exchange.getRequestURI().getPath());
            upstreamRequest.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"model":"model-a","choices":[{"index":0,"finish_reason":"stop",
                     "message":{"role":"assistant","content":"configured"}}],
                     "usage":{"prompt_tokens":3,"completion_tokens":2}}
                    """);
        });
        String yaml = """
                ai:
                  bridge:
                    default-chat-model: smart
                    default-embedding-model: embed
                    default-rerank-model: rerank
                    defaults:
                      temperature: 0.7
                      top-p: 0.55
                      max-output-tokens: 100
                    security:
                      allow-loopback: true
                      loopback-ports: [%d]
                    providers:
                      mock:
                        protocol: openai-compatible
                        base-url: http://127.0.0.1:%d
                        api-key: secret
                        endpoints:
                          chat-path: /custom/chat
                        defaults:
                          temperature: 0.6
                          top-p: 0.9
                          max-output-tokens: 77
                        models:
                          - id: model-a
                            alias: fast
                            display-name: Mock A
                            defaults:
                              temperature: 0.5
                              top-p: 0.8
                              max-output-tokens: 55
                          - id: embed-a
                            alias: embed
                            supports-embeddings: true
                            supports-streaming: false
                      mock-rerank:
                        protocol: llama-cpp
                        base-url: http://127.0.0.1:%d
                        models:
                          - id: rerank-a
                            alias: rerank
                            supports-rerank: true
                            supports-streaming: false
                    aliases:
                      smart:
                        - mock/model-a
                """.formatted(port(), port(), port());

        runner(yaml).withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModelRouter.class);
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context).hasSingleBean(RerankModel.class);
            ChatModel model = context.getBean(ChatModel.class);
            ChatResponse response = model.call(ChatRequest.builder("smart")
                    .user("hello")
                    .temperature(0.2)
                    .build());

            assertThat(response.content()).isEqualTo("configured");
            assertThat(upstreamPath.get()).isEqualTo("/custom/chat");
            JsonNode body = upstreamRequest.get();
            assertThat(body.path("model").asText()).isEqualTo("model-a");
            assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
            assertThat(body.path("top_p").asDouble()).isEqualTo(0.8);
            assertThat(body.path("max_tokens").asInt()).isEqualTo(55);
            assertThat(context.getBean(ModelRouter.class).catalog().aliases())
                    .containsKey("smart").containsKey("fast");
        });
    }

    @Test
    void configuredAliasFallsBackToNextProviderOnRetryableFailure() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().startsWith("/primary/")) {
                respond(exchange, 503, "{\"error\":\"busy\"}");
            } else {
                respond(exchange, 200, """
                        {"model":"secondary-model","choices":[{"index":0,
                         "finish_reason":"stop","message":{"role":"assistant","content":"fallback"}}],
                         "usage":{"prompt_tokens":2,"completion_tokens":1}}
                        """);
            }
        });
        String yaml = """
                ai:
                  bridge:
                    default-chat-model: smart
                    security:
                      allow-loopback: true
                      loopback-ports: [%d]
                    providers:
                      primary:
                        protocol: openai-compatible
                        base-url: http://127.0.0.1:%d/primary
                        models:
                          - id: primary-model
                      secondary:
                        protocol: openai-compatible
                        base-url: http://127.0.0.1:%d/secondary
                        models:
                          - id: secondary-model
                    aliases:
                      smart:
                        - primary/primary-model
                        - secondary/secondary-model
                """.formatted(port(), port(), port());

        runner(yaml).withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            String response = context.getBean(ChatModel.class).call("hello");
            assertThat(response).isEqualTo("fallback");
            assertThat(paths).containsExactly("/primary/v1/chat/completions",
                    "/secondary/v1/chat/completions");
        });
    }

    @Test
    void globalProxyIsInjectedWithoutJvmSystemProperties() {
        String yaml = """
                ai:
                  bridge:
                    defaults:
                      proxy:
                        enabled: true
                        host: 127.0.0.1
                        port: 7890
                """;
        runner(yaml).run(context -> {
            assertThat(context).hasNotFailed();
            HttpTransport transport = context.getBean(HttpTransport.class);
            assertThat(transport.proxy().enabled()).isTrue();
            assertThat(transport.proxy().host()).isEqualTo("127.0.0.1");
            assertThat(transport.proxy().port()).isEqualTo(7890);
        });
    }

    @Test
    void privateNetworkModeWorksInProductionButMetadataStaysBlocked() {
        runner("").withPropertyValues(
                        "spring.profiles.active=prod",
                        "ai.bridge.security.mode=allow-private-network")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OutboundEndpointPolicy policy = context.getBean(OutboundEndpointPolicy.class);
                    assertThat(policy.mode()).isEqualTo(NetworkPolicyMode.ALLOW_PRIVATE_NETWORK);
                    assertThatThrownBy(() -> policy.validate(
                            URI.create("https://169.254.169.254/latest/meta-data")))
                            .isInstanceOf(ModelBridgeException.class);
                    assertThatThrownBy(() -> policy.validate(
                            URI.create("https://metadata.google.internal/computeMetadata")))
                            .isInstanceOf(ModelBridgeException.class);
                });
    }

    @Test
    void explicitProductionLoopbackOptInStillRequiresHostAndPortAllowlist() {
        runner("").withPropertyValues(
                        "spring.profiles.active=prod",
                        "ai.bridge.security.allow-loopback=true",
                        "ai.bridge.security.allow-loopback-in-production=true",
                        "ai.bridge.security.loopback-ports=18080")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OutboundEndpointPolicy.class)
                            .validate(URI.create("http://127.0.0.1:18080/v1/models")).host())
                            .isEqualTo("127.0.0.1");
                });
    }

    @Test
    void discoveryMergesDynamicModelsAndStaticDeclarationsOverrideMetadata() throws Exception {
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 200, """
                        {"object":"list","data":[
                          {"id":"model-a","display_name":"Remote A","context_length":1000,
                           "supports_tools":true},
                          {"id":"dynamic-only","display_name":"Dynamic only","context_length":2000}
                        ]}
                        """);
            } else {
                respond(exchange, 200, """
                        {"model":"model-a","choices":[{"index":0,"finish_reason":"stop",
                         "message":{"role":"assistant","content":"merged"}}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1}}
                        """);
            }
        });
        String yaml = """
                ai:
                  bridge:
                    default-chat-model: mock/model-a
                    security:
                      allow-loopback: true
                      loopback-ports: [%d]
                    providers:
                      mock:
                        protocol: openai-compatible
                        base-url: http://127.0.0.1:%d
                        discover-models: true
                        models:
                          - id: model-a
                            alias: local-a
                            display-name: Local A
                """.formatted(port(), port());

        runner(yaml).withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            List<ModelDescriptor> models = context.getBean(ModelRouter.class)
                    .catalog().models();
            ModelDescriptor modelA = find(models, "model-a");
            assertThat(modelA.displayName()).isEqualTo("Local A");
            assertThat(modelA.contextWindow()).isEqualTo(1000);
            assertThat(find(models, "dynamic-only")).isNotNull();
            assertThat(find(models, "local-a")).isNotNull();
            assertThat(context.getBean(ChatModel.class).call("hello")).isEqualTo("merged");
        });
    }

    private ApplicationContextRunner runner(String yaml) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModelBridgeAutoConfiguration.class));
        if (yaml != null && !yaml.isBlank()) {
            runner = runner.withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("phase3", new ByteArrayResource(
                                    yaml.getBytes(StandardCharsets.UTF_8)))
                            .forEach(source -> context.getEnvironment().getPropertySources()
                                    .addFirst(source));
                } catch (IOException error) {
                    throw new IllegalStateException("could not load test YAML", error);
                }
            });
        }
        return runner;
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception error) {
                respondQuietly(exchange, 500, "{\"error\":\"test server\"}");
            }
        });
        server.start();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static void respondQuietly(HttpExchange exchange, int status, String body) {
        try {
            respond(exchange, status, body);
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private static ModelDescriptor find(List<ModelDescriptor> models, String id) {
        return models.stream().filter(model -> model.id().equals(id)).findFirst().orElse(null);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
