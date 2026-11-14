package io.github.bangshou1st.modelbridge.core.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Strict SSE frame parser. It is line-oriented so a frame split across TCP reads is reassembled,
 * and it tolerates CRLF/LF plus multi-line data fields.
 */
public final class SseParser {
    private static final int MAX_FRAME_CHARS = 4 * 1024 * 1024;

    private SseParser() {
    }

    public static void parse(InputStream input, Consumer<Event> consumer) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String event = "message";
        String id = null;
        StringBuilder data = new StringBuilder();
        String line;
        int frameChars = 0;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    consumer.accept(new Event(event, data.toString(), id));
                }
                event = "message";
                id = null;
                data.setLength(0);
                frameChars = 0;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            frameChars += line.length() + 1;
            if (frameChars > MAX_FRAME_CHARS) {
                throw new IOException("SSE frame exceeds size limit");
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> event = value;
                case "id" -> id = value;
                case "data" -> {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                default -> {
                    // retry/comments/unknown fields are intentionally ignored.
                }
            }
        }
        if (!data.isEmpty()) {
            consumer.accept(new Event(event, data.toString(), id));
        }
    }

    public record Event(String event, String data, String id) {
    }
}
