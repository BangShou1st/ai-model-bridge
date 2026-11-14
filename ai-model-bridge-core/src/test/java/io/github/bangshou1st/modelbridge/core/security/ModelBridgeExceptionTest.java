package io.github.bangshou1st.modelbridge.core.security;

import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelBridgeExceptionTest {
    @Test
    void mapsStatusCodesAndRetryability() {
        assertThat(ModelBridgeException.fromHttpStatus(401, "p", null).category())
                .isEqualTo(ErrorCategory.AUTHENTICATION);
        assertThat(ModelBridgeException.fromHttpStatus(403, "p", null).category())
                .isEqualTo(ErrorCategory.AUTHORIZATION);
        assertThat(ModelBridgeException.fromHttpStatus(429, "p", null).retryable()).isTrue();
        assertThat(ModelBridgeException.fromHttpStatus(503, "p", null).category())
                .isEqualTo(ErrorCategory.UNAVAILABLE);
        assertThat(ModelBridgeException.fromHttpStatus(504, "p", null).category())
                .isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(ModelBridgeException.truncated("p", new TokenUsage(3, 4)).usage().totalTokens())
                .isEqualTo(7);
        assertThat(ModelBridgeException.schema("bad", "p").category())
                .isEqualTo(ErrorCategory.SCHEMA);
    }
}
