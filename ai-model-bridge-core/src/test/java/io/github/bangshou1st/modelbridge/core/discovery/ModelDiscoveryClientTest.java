package io.github.bangshou1st.modelbridge.core.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.core.testsupport.LocalFeatureServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelDiscoveryClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRichVendorModelMetadata() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                """
                {"data":[{"context_length":131072,"context_window":131072,"created":0,
                "created_at":"2025-01-01T00:00:00Z","display_name":"Vendor Chat",
                "id":"vendor/chat-model","vendor_label":"Vendor Chat",
                "max_input_tokens":131072,"max_output_tokens":8192,
                "modalities":{"input":["text","image"],"output":["text"]},
                "native_endpoints":["/v1/chat/completions"],"object":"model",
                "owned_by":"vendor","pricing":{"completion":"0","prompt":"0",
                "input_cache_read":"0.000000003"},"reasoning":true,"reasoning_levels":["medium"],
                "supported_parameters":["tools","tool_choice","reasoning"],
                "supported_reasoning_levels":[{"effort":"medium"}],"tool_call":true,"type":"model"}],
                "first_id":"vendor/chat-model","has_more":false,
                "last_id":"vendor/chat-model","object":"list"}
                """))) {
            ModelDiscoveryClient client = client(server);
            List<ModelDescriptor> models = client.discover(provider(server, ProviderType.OPENAI_COMPATIBLE, "key"));

            assertThat(models).hasSize(1);
            ModelDescriptor model = models.getFirst();
            assertThat(model.id()).isEqualTo("vendor/chat-model");
            assertThat(model.displayName()).isEqualTo("Vendor Chat");
            assertThat(model.ownedBy()).isEqualTo("vendor");
            assertThat(model.contextWindow()).isEqualTo(131072);
            assertThat(model.maxInputTokens()).isEqualTo(131072);
            assertThat(model.maxOutputTokens()).isEqualTo(8192);
            assertThat(model.supportsTools()).isTrue();
            assertThat(model.supportsReasoning()).isTrue();
            assertThat(model.reasoningLevels()).containsExactly("medium");
            assertThat(model.supportedParameters()).contains("tools", "reasoning");
            assertThat(model.pricing()).containsEntry("completion", BigDecimal.ZERO)
                    .containsEntry("input_cache_read", new BigDecimal("0.000000003"));
            assertThat(model.inputModalities()).containsExactly("text", "image");
            assertThat(model.outputModalities()).containsExactly("text");
            assertThat(model.nativeEndpoints()).containsExactly("/v1/chat/completions");
            assertThat(model.createdAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
            assertThat(model.extra()).containsEntry("vendor_label", "Vendor Chat");
            assertThat(server.requests().getFirst().path()).isEqualTo("/v1/models");
            assertThat(server.requests().getFirst().header("authorization")).isEqualTo("Bearer key");
        }
    }

    @Test
    void parsesStandardOpenAiModelList() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                """
                {"object":"list","data":[{"id":"gpt-x","object":"model","created":123,
                "owned_by":"lab","context_length":128000,"max_output_tokens":4096,
                "supports_tools":true,"supports_streaming":false}]}
                """))) {
            ModelDescriptor model = client(server).discover(
                    provider(server, ProviderType.OPENAI_COMPATIBLE, "key")).getFirst();

            assertThat(model.id()).isEqualTo("gpt-x");
            assertThat(model.contextWindow()).isEqualTo(128000);
            assertThat(model.maxInputTokens()).isEqualTo(128000);
            assertThat(model.maxOutputTokens()).isEqualTo(4096);
            assertThat(model.supportsTools()).isTrue();
            assertThat(model.supportsStreaming()).isFalse();
            assertThat(model.createdAt()).isEqualTo(Instant.ofEpochSecond(123));
        }
    }

    @Test
    void parsesAnthropicHeadersAndPagination() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> {
            if (request.query() != null && request.query().contains("after=claude-a")) {
                return new LocalFeatureServer.Response(200, "application/json",
                        """
                        {"data":[{"id":"claude-b","display_name":"Claude B",
                        "created_at":"2025-02-01T00:00:00Z","max_input_tokens":200000,
                        "max_output_tokens":8192,"capabilities":{"tools":true}}],
                        "has_more":false,"last_id":"claude-b"}
                        """);
            }
            return new LocalFeatureServer.Response(200, "application/json",
                    """
                    {"data":[{"id":"claude-a","display_name":"Claude A",
                    "created_at":"2025-01-01T00:00:00Z","max_input_tokens":200000,
                    "max_output_tokens":8192,"capabilities":{"tools":true}}],
                    "has_more":true,"last_id":"claude-a"}
                    """);
        })) {
            List<ModelDescriptor> models = client(server).discover(
                    provider(server, ProviderType.ANTHROPIC, "anthropic-key"));

            assertThat(models).extracting(ModelDescriptor::id).containsExactly("claude-a", "claude-b");
            assertThat(models.getFirst().maxInputTokens()).isEqualTo(200000);
            assertThat(models.getFirst().supportsTools()).isTrue();
            assertThat(server.requests()).hasSize(2);
            assertThat(server.requests().getFirst().header("x-api-key")).isEqualTo("anthropic-key");
            assertThat(server.requests().getFirst().header("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(server.requests().get(1).query()).contains("after=claude-a").contains("limit=1000");
        }
    }

    @Test
    void parsesGeminiModelMethodsHeadersAndPagination() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> {
            if (request.query() != null && request.query().contains("pageToken=tok")) {
                return new LocalFeatureServer.Response(200, "application/json",
                        """
                        {"models":[{"name":"models/gemini-embedding","displayName":"Embedding",
                        "inputTokenLimit":2048,"outputTokenLimit":0,
                        "supportedGenerationMethods":["embedContent"]}]}
                        """);
            }
            return new LocalFeatureServer.Response(200, "application/json",
                    """
                    {"models":[{"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro",
                    "inputTokenLimit":1048576,"outputTokenLimit":65536,"thinking":true,
                    "supportedGenerationMethods":["generateContent","streamGenerateContent","embedContent"]}],
                    "nextPageToken":"tok"}
                    """);
        })) {
            List<ModelDescriptor> models = client(server).discover(
                    provider(server, ProviderType.GEMINI, "gemini-key"));

            assertThat(models).extracting(ModelDescriptor::id)
                    .containsExactly("gemini-2.5-pro", "gemini-embedding");
            ModelDescriptor model = models.getFirst();
            assertThat(model.contextWindow()).isEqualTo(1048576);
            assertThat(model.maxOutputTokens()).isEqualTo(65536);
            assertThat(model.supportsTools()).isTrue();
            assertThat(model.supportsStreaming()).isTrue();
            assertThat(model.supportsEmbeddings()).isTrue();
            assertThat(model.nativeEndpoints()).contains("generateContent", "streamGenerateContent");
            assertThat(models.get(1).supportsEmbeddings()).isTrue();
            assertThat(server.requests().getFirst().header("x-goog-api-key")).isEqualTo("gemini-key");
            assertThat(server.requests().getFirst().path()).isEqualTo("/v1beta/models");
            assertThat(server.requests().get(1).query()).contains("pageToken=tok");
        }
    }

    @Test
    void parsesOllamaModelList() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json",
                """
                {"models":[{"name":"qwen3:8b","model":"qwen3:8b",
                "modified_at":"2025-01-01T00:00:00Z","size":123,
                "digest":"sha256:abc","details":{"family":"qwen","parameter_size":"8B"}}]}
                """))) {
            ProviderConfig provider = new ProviderConfig("ollama", ProviderType.OLLAMA,
                    server.baseUrl(), "", Map.of(), Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of());
            ModelDescriptor model = client(server).discover(provider).getFirst();

            assertThat(model.id()).isEqualTo("qwen3:8b");
            assertThat(model.displayName()).isEqualTo("qwen3:8b");
            assertThat(model.ownedBy()).isEqualTo("qwen");
            assertThat(model.supportsStreaming()).isTrue();
            assertThat(model.createdAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
            assertThat(server.requests().getFirst().path()).isEqualTo("/api/tags");
        }
    }

    @Test
    void rejectsMalformedModelList() throws Exception {
        try (LocalFeatureServer server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                200, "application/json", "{\"object\":\"list\"}"))) {
            assertThatThrownBy(() -> client(server).discover(
                    provider(server, ProviderType.OPENAI_COMPATIBLE, "key")))
                    .isInstanceOf(ModelBridgeException.class)
                    .satisfies(error -> assertThat(((ModelBridgeException) error).category())
                            .isEqualTo(ErrorCategory.INVALID_RESPONSE));
        }
    }

    private ModelDiscoveryClient client(LocalFeatureServer server) {
        OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(
                java.util.Set.of(server.port()));
        return new ModelDiscoveryClient(mapper, new HttpTransport(mapper, policy));
    }

    private static ProviderConfig provider(LocalFeatureServer server, ProviderType type, String key) {
        return new ProviderConfig("upstream", type, server.baseUrl(), key, Map.of(),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of());
    }
}
