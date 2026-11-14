package io.github.bangshou1st.modelbridge.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.bangshou1st.modelbridge.core.client.ChatModel;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReadmeQuickStartIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mainReadmeExampleBindsAndInjectsTheDefaultChatModel() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> upstreamRequest = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            upstreamRequest.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {"id":"readme","object":"chat.completion","model":"deepseek-chat",
                     "choices":[{"index":0,"finish_reason":"stop",
                       "message":{"role":"assistant","content":"readme-ok"}}],
                     "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                    """);
        });
        server.start();
        int port = server.getAddress().getPort();

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("spring.profiles.active", "test");
        overrides.put("ai.bridge.providers.deepseek.base-url", "http://127.0.0.1:" + port);
        overrides.put("ai.bridge.providers.deepseek.api-key", "readme-test-key");
        overrides.put("ai.bridge.providers.gemini.base-url",
                "http://127.0.0.1:" + port + "/gemini");
        overrides.put("ai.bridge.providers.gemini.api-key", "readme-test-key");
        overrides.put("ai.bridge.security.allow-loopback", "true");
        overrides.put("ai.bridge.security.loopback-ports[0]", Integer.toString(port));

        runner(readmeMainExample(), overrides).run(context -> {
            assertThat(context).hasNotFailed();
            ModelBridgeProperties properties = context.getBean(ModelBridgeProperties.class);
            assertThat(properties.getDefaultChatModel()).isEqualTo("smart");
            assertThat(properties.getProviders()).containsKeys("deepseek", "gemini");
            assertThat(properties.getAliases().get("smart")).containsExactly(
                    "deepseek/deepseek-chat", "gemini/gemini-2.5-flash");

            assertThat(context).hasSingleBean(ChatModel.class);
            ChatResponse response = context.getBean(ChatModel.class)
                    .call(ChatRequest.builder("ignored").user("hello").build());
            assertThat(response.content()).isEqualTo("readme-ok");
            assertThat(path.get()).isEqualTo("/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer readme-test-key");
            assertThat(upstreamRequest.get().path("model").asText())
                    .isEqualTo("deepseek-chat");
        });
    }

    private static ApplicationContextRunner runner(String yaml, Map<String, Object> overrides) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModelBridgeAutoConfiguration.class))
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load("readme", new ByteArrayResource(
                                        yaml.getBytes(StandardCharsets.UTF_8)))
                                .forEach(source -> context.getEnvironment().getPropertySources()
                                        .addFirst(source));
                        context.getEnvironment().getPropertySources().addFirst(
                                new MapPropertySource("readme-test-overrides", overrides));
                    } catch (IOException error) {
                        throw new IllegalStateException("could not load README YAML", error);
                    }
                });
    }

    private static String readmeMainExample() throws IOException {
        String readme = Files.readString(repositoryRoot().resolve("README.md"),
                StandardCharsets.UTF_8);
        String startMarker = "<!-- readme-main-example:start -->";
        String endMarker = "<!-- readme-main-example:end -->";
        int start = readme.indexOf(startMarker);
        int end = readme.indexOf(endMarker);
        if (start < 0 || end <= start) {
            throw new IllegalStateException("README main example markers are missing");
        }
        String section = readme.substring(start + startMarker.length(), end);
        int yamlStart = section.indexOf("```yaml");
        int yamlEnd = section.indexOf("```", yamlStart + "```yaml".length());
        if (yamlStart < 0 || yamlEnd <= yamlStart) {
            throw new IllegalStateException("README main example has no YAML block");
        }
        return section.substring(yamlStart + "```yaml".length(), yamlEnd).strip();
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("README.md"))
                    && Files.isDirectory(candidate.resolve("ai-model-bridge-core"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate repository root from "
                + System.getProperty("user.dir"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
