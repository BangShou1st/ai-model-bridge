package io.github.bangshou1st.modelbridge.core.security;

import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Safe logging helpers. Prompt text and credentials are never included in summaries.
 */
public final class LogRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern API_KEY = Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{20,})");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|access[_-]?token|authorization|x-api-key)\"\\s*:\\s*\")[^\"]*(\")");
    private static final Pattern HEADER_SECRET = Pattern.compile("(?i)(x-api-key\\s*[:=]\\s*)[^\\s,;]+");

    private LogRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer ***");
        redacted = API_KEY.matcher(redacted).replaceAll("***");
        redacted = JSON_SECRET.matcher(redacted).replaceAll("$1***$2");
        return HEADER_SECRET.matcher(redacted).replaceAll("$1***");
    }

    public static Map<String, String> redactHeaders(Map<String, String> headers) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (headers == null) {
            return safe;
        }
        headers.forEach((key, value) -> {
            String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (lower.contains("authorization") || lower.contains("api-key")
                    || lower.contains("token") || lower.contains("secret")) {
                safe.put(key, "***");
            } else {
                safe.put(key, redact(value));
            }
        });
        return safe;
    }

    public static String summarize(ChatRequest request) {
        if (request == null) {
            return "request=null";
        }
        return "model=" + request.model()
                + ", messages=" + request.messages().size()
                + ", chars=" + request.messages().stream().mapToInt(m -> m.content().length()).sum()
                + ", tools=" + request.tools().size()
                + ", structured=" + request.structuredOutput().mode();
    }

    public static String summarize(TurnRequest request) {
        if (request == null) {
            return "turn=null";
        }
        return "model=" + request.model()
                + ", messages=" + request.messages().size()
                + ", chars=" + request.messages().stream().mapToInt(m -> m.content().length()).sum()
                + ", tools=" + request.tools().size();
    }

    public static String safeMessageSummary(Message message) {
        if (message == null) {
            return "message=null";
        }
        return "role=" + message.role() + ", chars=" + message.content().length()
                + ", toolCalls=" + message.toolCalls().size();
    }
}
