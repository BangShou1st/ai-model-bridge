package io.github.bangshou1st.modelbridge.core.security;

/**
 * Stable consumer-facing classification. A category is deliberately more useful than an
 * upstream error body, which may contain secrets or prompt fragments.
 */
public enum ErrorCategory {
    AUTHENTICATION(401, false),
    AUTHORIZATION(403, false),
    BAD_REQUEST(400, false),
    RATE_LIMIT(429, true),
    UNAVAILABLE(503, true),
    TIMEOUT(504, true),
    LENGTH(502, false),
    SCHEMA(502, false),
    INVALID_RESPONSE(502, false),
    NETWORK_POLICY(403, false),
    UNSUPPORTED(501, false),
    INTERNAL(500, false);

    private final int statusCode;
    private final boolean retryable;

    ErrorCategory(int statusCode, boolean retryable) {
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
