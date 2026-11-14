package io.github.bangshou1st.modelbridge.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class GatewayExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    private final OpenAiWireMapper wire;

    public GatewayExceptionHandler(OpenAiWireMapper wire) {
        this.wire = wire;
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ObjectNode> gateway(GatewayException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(),
                exception.retryable());
    }

    @ExceptionHandler(ModelBridgeException.class)
    public ResponseEntity<ObjectNode> bridge(ModelBridgeException exception) {
        HttpStatus status = HttpStatus.resolve(exception.httpStatus());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        log.warn("gateway upstream failure: category={}, provider={}, status={}",
                exception.category(), exception.provider(), exception.upstreamStatus());
        String code = exception.category().name().toLowerCase();
        String message = message(exception.category());
        return response(status, code, message, exception.retryable());
    }

    @ExceptionHandler({ServerWebInputException.class, IllegalArgumentException.class})
    public ResponseEntity<ObjectNode> badRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "invalid_request_error",
                "invalid gateway request", false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> unexpected(Exception exception) {
        log.error("unexpected gateway failure: type={}", exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "gateway_error",
                "internal gateway failure", false);
    }

    private ResponseEntity<ObjectNode> response(HttpStatus status, String code, String message,
                                                boolean retryable) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(wire.error(message, status.is4xxClientError()
                        ? "invalid_request_error" : "api_error", code, retryable));
    }

    private static String message(ErrorCategory category) {
        return switch (category) {
            case AUTHENTICATION -> "upstream authentication failed";
            case AUTHORIZATION -> "upstream authorization failed";
            case RATE_LIMIT -> "upstream rate limit exceeded";
            case UNAVAILABLE -> "upstream unavailable";
            case TIMEOUT -> "upstream timed out";
            case LENGTH -> "upstream output was truncated";
            case SCHEMA -> "structured output did not satisfy the schema";
            case BAD_REQUEST -> "upstream rejected the request";
            case NETWORK_POLICY -> "upstream endpoint was rejected by security policy";
            case UNSUPPORTED -> "operation is not supported by the upstream protocol";
            case INVALID_RESPONSE -> "upstream returned an invalid response";
            case INTERNAL -> "internal gateway failure";
        };
    }
}
