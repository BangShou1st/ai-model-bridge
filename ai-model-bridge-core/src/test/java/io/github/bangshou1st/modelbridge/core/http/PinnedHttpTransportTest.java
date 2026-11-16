package io.github.bangshou1st.modelbridge.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.core.testsupport.LocalFeatureServer;
import io.github.bangshou1st.modelbridge.core.client.DefaultStreamHandle;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PinnedHttpTransportTest {

    @Test
    void pinnedTransportUsesConnectionTimeResolverForLoopbackAllowlist() throws Exception {
        try (var server = LocalFeatureServer.start(request ->
                new LocalFeatureServer.Response(200, "application/json", "{\"ok\":true}"))) {
            var policy = new OutboundEndpointPolicy(true, Set.of("127.0.0.1"), Set.of(),
                    host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
            var mapper = new ObjectMapper();
            var transport = new HttpTransport(
                    mapper, policy, Duration.ofSeconds(5), Duration.ofSeconds(5),
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build(),
                    io.github.bangshou1st.modelbridge.core.model.ProxyConfig.disabled(),
                    HttpTransport.pinnedClient(policy, Duration.ofSeconds(5), Duration.ofSeconds(5)));
            var config = new ProviderConfig("pinned-test", ProtocolId.OPENAI_COMPATIBLE,
                    server.baseUrl(), null, Map.of(), Duration.ofSeconds(5),
                    Duration.ofSeconds(5), Map.of());

            var response = transport.getJson(config, server.baseUrl() + "/v1/models", Map.of());

            assertThat(response.path("ok").asBoolean()).isTrue();
            assertThat(server.requests()).singleElement()
                    .satisfies(request -> assertThat(request.path()).isEqualTo("/v1/models"));
        }
    }

    @Test
    void pinnedTransportStreamsSseWithoutBufferingTheWholeResponse() throws Exception {
        try (var server = LocalFeatureServer.start(request ->
                new LocalFeatureServer.Response(200, "text/event-stream",
                        "data: {\"delta\":\"ok\"}\n\n"))) {
            var policy = new OutboundEndpointPolicy(true, Set.of("127.0.0.1"), Set.of(),
                    host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
            var mapper = new ObjectMapper();
            var transport = new HttpTransport(
                    mapper, policy, Duration.ofSeconds(5), Duration.ofSeconds(5),
                    HttpClient.newBuilder().build(),
                    io.github.bangshou1st.modelbridge.core.model.ProxyConfig.disabled(),
                    HttpTransport.pinnedClient(policy, Duration.ofSeconds(5), Duration.ofSeconds(5)));
            var config = new ProviderConfig("pinned-stream", ProtocolId.OPENAI_COMPATIBLE,
                    server.baseUrl(), null, Map.of(), Duration.ofSeconds(5),
                    Duration.ofSeconds(5), Map.of());
            var events = new java.util.ArrayList<String>();

            transport.postSse(config, server.baseUrl() + "/v1/chat/completions",
                    Map.of(), mapper.createObjectNode(),
                    event -> events.add(event.data()), new DefaultStreamHandle());

            assertThat(events).containsExactly("{\"delta\":\"ok\"}");
        }
    }
}
