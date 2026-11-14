package io.github.bangshou1st.modelbridge.core.model;

/**
 * Strongly typed endpoint overrides. Relative paths are appended to the provider base URL and
 * absolute URLs are used as-is.
 */
public record EndpointConfig(
        String chatPath,
        String modelsPath,
        String embeddingsPath,
        String embeddingsLegacyPath,
        String rerankPath,
        String anthropicVersion,
        String embeddingTaskType
) {
    public static EndpointConfig empty() {
        return new EndpointConfig(null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return chatPath == null && modelsPath == null && embeddingsPath == null
                && embeddingsLegacyPath == null && rerankPath == null
                && anthropicVersion == null && embeddingTaskType == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String chatPath;
        private String modelsPath;
        private String embeddingsPath;
        private String embeddingsLegacyPath;
        private String rerankPath;
        private String anthropicVersion;
        private String embeddingTaskType;

        public Builder chatPath(String value) { this.chatPath = value; return this; }
        public Builder modelsPath(String value) { this.modelsPath = value; return this; }
        public Builder embeddingsPath(String value) { this.embeddingsPath = value; return this; }
        public Builder embeddingsLegacyPath(String value) { this.embeddingsLegacyPath = value; return this; }
        public Builder rerankPath(String value) { this.rerankPath = value; return this; }
        public Builder anthropicVersion(String value) { this.anthropicVersion = value; return this; }
        public Builder embeddingTaskType(String value) { this.embeddingTaskType = value; return this; }

        public EndpointConfig build() {
            return new EndpointConfig(chatPath, modelsPath, embeddingsPath, embeddingsLegacyPath,
                    rerankPath, anthropicVersion, embeddingTaskType);
        }
    }
}
