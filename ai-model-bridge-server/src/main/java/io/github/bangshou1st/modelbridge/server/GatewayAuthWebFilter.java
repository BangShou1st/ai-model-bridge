package io.github.bangshou1st.modelbridge.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayAuthWebFilter implements WebFilter {
    private final GatewayProperties properties;
    private final ObjectMapper mapper;

    public GatewayAuthWebFilter(GatewayProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith("/v1/") || !properties.isAuthEnabled()) {
            return chain.filter(exchange);
        }
        String supplied = bearer(exchange.getRequest().getHeaders());
        if (supplied != null && properties.getApiKeys().stream()
                .filter(key -> key != null && !key.isBlank())
                .anyMatch(key -> constantTimeEquals(key, supplied))) {
            return chain.filter(exchange);
        }
        return writeUnauthorized(exchange);
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(java.util.Map.of("error", java.util.Map.of(
                    "message", "missing or invalid bearer token",
                    "type", "invalid_request_error",
                    "code", "invalid_api_key",
                    "retryable", false)));
        } catch (Exception e) {
            body = "{\"error\":{\"message\":\"unauthorized\",\"code\":\"invalid_api_key\"}}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private static String bearer(HttpHeaders headers) {
        List<String> values = headers.get(HttpHeaders.AUTHORIZATION);
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return value.substring(7).strip();
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
