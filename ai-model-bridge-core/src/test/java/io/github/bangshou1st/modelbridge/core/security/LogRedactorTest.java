package io.github.bangshou1st.modelbridge.core.security;

import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactorTest {
    @Test
    void redactsKeysAndHeaders() {
        String secret = "sk-abcdefghijklmnop";
        String value = "Authorization: Bearer " + secret + " x-api-key: another-secret";
        String redacted = LogRedactor.redact(value);

        assertThat(redacted).doesNotContain(secret, "another-secret");
        assertThat(LogRedactor.redactHeaders(Map.of(
                "Authorization", "Bearer " + secret,
                "Content-Type", "application/json")))
                .containsEntry("Authorization", "***")
                .containsEntry("Content-Type", "application/json");
    }

    @Test
    void requestSummaryNeverContainsPromptText() {
        ChatRequest request = ChatRequest.builder("model")
                .user("TOP SECRET PROMPT")
                .build();
        String summary = LogRedactor.summarize(request);

        assertThat(summary).doesNotContain("TOP SECRET PROMPT");
        assertThat(summary).contains("model=model", "messages=1");
    }
}
