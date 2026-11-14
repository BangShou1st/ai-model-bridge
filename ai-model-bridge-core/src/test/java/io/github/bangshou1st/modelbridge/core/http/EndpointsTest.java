package io.github.bangshou1st.modelbridge.core.http;

import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.EndpointConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointsTest {
    @Test
    void recognizesCommonVersionSuffixesInsteadOfAppendingV1Again() {
        assertThat(Endpoints.endpoint(provider("https://ark.example.com/api/v3"), "chatPath",
                "/v1", "/chat/completions"))
                .isEqualTo("https://ark.example.com/api/v3/chat/completions");
        assertThat(Endpoints.endpoint(provider("https://open.bigmodel.cn/api/paas/v4"), "chatPath",
                "/v1", "/chat/completions"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(Endpoints.endpoint(provider("https://example.com/v1beta"), "chatPath",
                "/v1", "/chat/completions"))
                .isEqualTo("https://example.com/v1beta/chat/completions");
    }

    @Test
    void doesNotDuplicateAnAlreadyCompleteEndpointAndHonorsExplicitPaths() {
        assertThat(Endpoints.endpoint(provider("https://example.com/v1/chat/completions"), "chatPath",
                "/v1", "/chat/completions"))
                .isEqualTo("https://example.com/v1/chat/completions");
        assertThat(Endpoints.endpoint(provider("https://example.com",
                        Map.of("chatPath", "/custom/chat")), "chatPath", "/v1", "/chat/completions"))
                .isEqualTo("https://example.com/custom/chat");
        assertThat(Endpoints.endpoint(provider("https://example.com",
                        Map.of("chatPath", "https://other.example.com/complete")),
                "chatPath", "/v1", "/chat/completions"))
                .isEqualTo("https://other.example.com/complete");
    }

    @Test
    void typedEndpointConfigTakesPrecedenceOverLegacyOptionMap() {
        ProviderConfig provider = ProviderConfig.openAiCompatible(
                "test", "https://example.com", "key")
                .withOptions(Map.of("chatPath", "/legacy/chat"))
                .withEndpointConfig(EndpointConfig.builder()
                        .chatPath("/typed/chat")
                        .modelsPath("/typed/models")
                        .build());

        assertThat(Endpoints.endpoint(provider, "chatPath", "/v1", "/chat/completions"))
                .isEqualTo("https://example.com/typed/chat");
        assertThat(Endpoints.endpoint(provider, "modelsPath", "/v1", "/models"))
                .isEqualTo("https://example.com/typed/models");
    }

    private static ProviderConfig provider(String baseUrl) {
        return provider(baseUrl, Map.of());
    }

    private static ProviderConfig provider(String baseUrl, Map<String, Object> options) {
        return ProviderConfig.openAiCompatible("test", baseUrl, "key")
                .withOptions(options);
    }
}
