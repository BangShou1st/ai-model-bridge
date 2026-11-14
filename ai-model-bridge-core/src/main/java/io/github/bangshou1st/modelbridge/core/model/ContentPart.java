package io.github.bangshou1st.modelbridge.core.model;

import java.util.Objects;

/** Multimodal and reasoning content blocks accepted by the provider-neutral message model. */
public sealed interface ContentPart permits ContentPart.Text, ContentPart.Image, ContentPart.Thinking {
    record Text(String text) implements ContentPart {
        public Text {
            text = text == null ? "" : text;
        }
    }

    record Image(String url, String mediaType, String base64Data) implements ContentPart {
        public Image {
            if ((url == null || url.isBlank()) && (base64Data == null || base64Data.isBlank())) {
                throw new IllegalArgumentException("image requires url or base64Data");
            }
        }

        public static Image ofUrl(String url, String mediaType) {
            return new Image(Objects.requireNonNull(url, "url"), mediaType, null);
        }

        public static Image ofBase64(String mediaType, String base64Data) {
            return new Image(null, Objects.requireNonNull(mediaType, "mediaType"),
                    Objects.requireNonNull(base64Data, "base64Data"));
        }
    }

    record Thinking(String text, String signature) implements ContentPart {
        public Thinking {
            text = text == null ? "" : text;
        }
    }
}
