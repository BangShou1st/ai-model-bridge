package io.github.bangshou1st.modelbridge.core.security;

import io.github.bangshou1st.modelbridge.core.model.TokenUsage;

import java.time.Duration;
import java.util.Objects;

/**
 * One exception hierarchy for authentication, authorization, rate limits, upstream availability,
 * timeouts, truncation and schema failures. Error bodies are never copied into the message.
 */
public class ModelBridgeException extends RuntimeException {
    private final ErrorCategory category;
    private final int upstreamStatus;
    private final boolean retryable;
    private final String provider;
    private final Duration retryAfter;
    private final TokenUsage usage;

    public ModelBridgeException(ErrorCategory category, String message, String provider,
                                int upstreamStatus, Duration retryAfter, TokenUsage usage,
                                Throwable cause) {
        super(message == null || message.isBlank() ? category.name() : message, cause);
        this.category = Objects.requireNonNull(category, "category");
        this.upstreamStatus = upstreamStatus;
        this.retryable = category.retryable();
        this.provider = provider;
        this.retryAfter = retryAfter;
        this.usage = usage == null ? TokenUsage.empty() : usage;
    }

    public ModelBridgeException(ErrorCategory category, String message, String provider) {
        this(category, message, provider, 0, null, null, null);
    }

    public ModelBridgeException(ErrorCategory category, String message) {
        this(category, message, null);
    }

    public ErrorCategory category() {
        return category;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public String provider() {
        return provider;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public TokenUsage usage() {
        return usage;
    }

    public int httpStatus() {
        return category.statusCode();
    }

    public static ModelBridgeException fromHttpStatus(int status, String provider, Duration retryAfter) {
        ErrorCategory category = switch (status) {
            case 401 -> ErrorCategory.AUTHENTICATION;
            case 403 -> ErrorCategory.AUTHORIZATION;
            case 408, 504 -> ErrorCategory.TIMEOUT;
            case 429 -> ErrorCategory.RATE_LIMIT;
            case 400, 404, 405, 409, 413, 415, 422 -> ErrorCategory.BAD_REQUEST;
            case 502, 503 -> ErrorCategory.UNAVAILABLE;
            default -> status >= 500 ? ErrorCategory.UNAVAILABLE : ErrorCategory.BAD_REQUEST;
        };
        return new ModelBridgeException(category, "upstream HTTP " + status, provider, status,
                retryAfter, null, null);
    }

    public static ModelBridgeException truncated(String provider, TokenUsage usage) {
        return new ModelBridgeException(ErrorCategory.LENGTH, "model output was truncated", provider,
                0, null, usage, null);
    }

    public static ModelBridgeException schema(String message, String provider) {
        return new ModelBridgeException(ErrorCategory.SCHEMA, message, provider);
    }

    public static ModelBridgeException invalidResponse(String message, String provider, TokenUsage usage) {
        return new ModelBridgeException(ErrorCategory.INVALID_RESPONSE, message, provider, 0, null, usage, null);
    }

    public static ModelBridgeException networkPolicy(String message) {
        return new ModelBridgeException(ErrorCategory.NETWORK_POLICY, message, null);
    }
}
