package io.github.bangshou1st.modelbridge.core.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import io.github.bangshou1st.modelbridge.core.testsupport.LocalFeatureServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityProberTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private LocalFeatureServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void reportsAllFourReadOnlyStages() throws Exception {
        server = LocalFeatureServer.start(request -> {
            if ("/v1/models".equals(request.path())) {
                return new LocalFeatureServer.Response(200, "application/json",
                        "{\"object\":\"list\",\"data\":[{\"id\":\"probe-model\",\"object\":\"model\"}]}");
            }
            if (!"/v1/chat/completions".equals(request.path())) {
                return new LocalFeatureServer.Response(404, "application/json", "{}");
            }
            JsonNode body = mapper.readTree(request.body());
            if (body.path("stream").asBoolean(false)) {
                String sse = "data: {\"id\":\"p\",\"model\":\"probe-model\",\"choices\":[{\"index\":0,"
                        + "\"finish_reason\":\"stop\",\"delta\":{\"content\":\"ok\"}}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\n"
                        + "data: [DONE]\n\n";
                return new LocalFeatureServer.Response(200, "text/event-stream", sse);
            }
            if (body.has("tools")) {
                return new LocalFeatureServer.Response(200, "application/json", """
                        {"id":"p","model":"probe-model","choices":[{"index":0,"finish_reason":"tool_calls",
                        "message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"call_p","type":"function","function":{"name":"bridge_probe",
                           "arguments":"{\\"note\\":\\"ok\\"}"}}]}}],
                        "usage":{"prompt_tokens":4,"completion_tokens":2}}
                        """);
            }
            if (body.has("response_format")) {
                return new LocalFeatureServer.Response(200, "application/json", """
                        {"id":"p","model":"probe-model","choices":[{"index":0,"finish_reason":"stop",
                        "message":{"role":"assistant","content":"{\\"status\\":\\"ok\\"}"}}],
                        "usage":{"prompt_tokens":4,"completion_tokens":2}}
                        """);
            }
            return new LocalFeatureServer.Response(200, "application/json", """
                    {"id":"p","model":"probe-model","choices":[{"index":0,"finish_reason":"stop",
                    "message":{"role":"assistant","content":"ok"}}],
                    "usage":{"prompt_tokens":2,"completion_tokens":1}}
                    """);
        });

        ProviderConfig provider = new ProviderConfig("probe", ProviderConfig.openAiCompatible(
                "probe", server.baseUrl() + "/v1", "secret").type(), server.baseUrl() + "/v1",
                "secret");
        OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(Set.of(server.port()));
        ModelBridgeClient client = ModelBridgeClient.create(mapper, policy);
        CapabilityReport report = new CapabilityProber(
                new ModelDiscoveryClient(mapper, new io.github.bangshou1st.modelbridge.core.http.HttpTransport(mapper, policy)),
                client).probe(provider, "probe-model");

        assertThat(report.stages()).extracting(ProbeStageResult::stage)
                .containsExactly(ProbeStage.CONNECTIVITY, ProbeStage.STREAMING,
                        ProbeStage.STRUCTURED_OUTPUT, ProbeStage.NATIVE_TOOLS);
        assertThat(report.supportsAll()).isTrue();
        assertThat(report.stages()).allSatisfy(stage -> {
            assertThat(stage.passed()).isTrue();
            assertThat(stage.durationMs()).isGreaterThanOrEqualTo(0);
            assertThat(stage.detail()).doesNotContain("Return the requested JSON");
        });
    }

    @Test
    void reportsStageFailureWithoutStoppingLaterStages() throws Exception {
        server = LocalFeatureServer.start(request -> new LocalFeatureServer.Response(
                429, "application/json", "{\"error\":{\"message\":\"slow down\"}}"));
        ProviderConfig provider = new ProviderConfig("probe", ProviderConfig.openAiCompatible(
                "probe", server.baseUrl() + "/v1", "secret").type(), server.baseUrl() + "/v1",
                "secret");
        OutboundEndpointPolicy policy = OutboundEndpointPolicy.localDevelopment(Set.of(server.port()));
        ModelBridgeClient client = ModelBridgeClient.create(mapper, policy);
        CapabilityReport report = new CapabilityProber(
                new ModelDiscoveryClient(mapper, new io.github.bangshou1st.modelbridge.core.http.HttpTransport(mapper, policy)),
                client).probe(provider, "probe-model");

        assertThat(report.stages()).hasSize(4);
        assertThat(report.passed(ProbeStage.CONNECTIVITY)).isFalse();
        assertThat(report.stages().getFirst().errorCategory().name()).isEqualTo("RATE_LIMIT");
        assertThat(report.supportsAll()).isFalse();
    }
}
