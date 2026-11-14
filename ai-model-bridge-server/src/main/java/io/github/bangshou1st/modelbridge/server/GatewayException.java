package io.github.bangshou1st.modelbridge.server;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    public GatewayException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public static GatewayException badRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error", message, false);
    }

    public static GatewayException notFound(String message) {
        return new GatewayException(HttpStatus.NOT_FOUND, "model_not_found", message, false);
    }

    public static GatewayException unauthorized() {
        return new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_api_key",
                "missing or invalid bearer token", false);
    }
}
