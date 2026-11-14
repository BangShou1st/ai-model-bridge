package io.github.bangshou1st.modelbridge.core.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseParserTest {
    @Test
    void reassemblesMultiLineFramesAndIgnoresComments() throws Exception {
        String payload = ": keepalive\r\n"
                + "event: message\r\n"
                + "id: 7\r\n"
                + "data: {\"a\":1,\r\n"
                + "data: \"b\":2}\r\n"
                + "\r\n"
                + "data: [DONE]\n\n";
        List<SseParser.Event> events = new ArrayList<>();

        SseParser.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), events::add);

        assertThat(events).hasSize(2);
        assertThat(events.getFirst().event()).isEqualTo("message");
        assertThat(events.getFirst().id()).isEqualTo("7");
        assertThat(events.getFirst().data()).isEqualTo("{\"a\":1,\n\"b\":2}");
        assertThat(events.getLast().data()).isEqualTo("[DONE]");
    }
}
