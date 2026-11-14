package io.github.bangshou1st.modelbridge.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentAndOptionsTest {
    @Test
    void multimodalMessageKeepsPartsAndLegacyTextAccessor() {
        Message message = Message.user(List.of(
                new ContentPart.Text("describe "),
                ContentPart.Image.ofUrl("https://example.com/image.png", "image/png")));

        assertThat(message.content()).isEqualTo("describe ");
        assertThat(message.parts()).hasSize(2);
        assertThat(message.parts().get(1)).isInstanceOf(ContentPart.Image.class);
    }

    @Test
    void optionsMergeFromGlobalThroughRequestAndRejectReservedExtraBody() {
        ChatOptions global = ChatOptions.builder()
                .temperature(0.7)
                .topP(0.4)
                .maxOutputTokens(100)
                .extraBody("top_k", 20)
                .build();
        ChatOptions provider = ChatOptions.builder().temperature(0.6).topP(0.8).build();
        ChatOptions model = ChatOptions.builder().maxOutputTokens(55).build();
        ChatOptions request = ChatOptions.builder().temperature(0.2)
                .extraBody("repetition_penalty", 1.1).build();

        ChatOptions merged = global.mergeWith(provider).mergeWith(model).mergeWith(request);

        assertThat(merged.temperature()).isEqualTo(0.2);
        assertThat(merged.topP()).isEqualTo(0.8);
        assertThat(merged.maxOutputTokens()).isEqualTo(55);
        assertThat(merged.extraBody())
                .containsEntry("top_k", 20)
                .containsEntry("repetition_penalty", 1.1);
        assertThatThrownBy(() -> ChatOptions.builder()
                .extraBody(Map.of("messages", List.of())).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chatResponseCanCarryThinkingSignatureAsContentPart() {
        ChatResponse response = new ChatResponse("answer", null, List.of(),
                FinishReason.STOP, TokenUsage.empty(), "anthropic", "claude", 1, Map.of(),
                List.of(new ContentPart.Thinking("thought", "signature-1"),
                        new ContentPart.Text("answer")));

        assertThat(response.content()).isEqualTo("answer");
        assertThat(response.reasoningContent()).isEqualTo("thought");
        assertThat(response.parts()).anySatisfy(part -> {
            assertThat(part).isInstanceOf(ContentPart.Thinking.class);
            assertThat(((ContentPart.Thinking) part).signature()).isEqualTo("signature-1");
        });
    }
}
