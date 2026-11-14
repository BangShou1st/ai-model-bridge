package io.github.bangshou1st.modelbridge.core.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.discovery.ModelDiscoveryClient;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.ModelDescriptor;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.StructuredOutput;
import io.github.bangshou1st.modelbridge.core.model.ToolChoice;
import io.github.bangshou1st.modelbridge.core.model.ToolDefinition;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CapabilityProber {
    private final ModelDiscoveryClient discovery;
    private final ModelBridgeClient client;
    private final ObjectMapper mapper;

    public CapabilityProber(ModelDiscoveryClient discovery, ModelBridgeClient client) {
        this.discovery = discovery;
        this.client = client;
        this.mapper = new ObjectMapper();
    }

    public CapabilityReport probe(ProviderConfig provider, String model) {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        List<ProbeStageResult> stages = new ArrayList<>();
        stages.add(stage(ProbeStage.CONNECTIVITY, () -> connectivity(provider, model)));
        stages.add(stage(ProbeStage.STREAMING, () -> streaming(provider, model)));
        stages.add(stage(ProbeStage.STRUCTURED_OUTPUT, () -> structured(provider, model)));
        stages.add(stage(ProbeStage.NATIVE_TOOLS, () -> nativeTools(provider, model)));
        return new CapabilityReport(provider.id(), model, startedAt,
                Math.max(0, (System.nanoTime() - started) / 1_000_000), stages);
    }

    private String connectivity(ProviderConfig provider, String model) {
        List<ModelDescriptor> models = discovery.discover(provider);
        boolean known = models.stream().anyMatch(descriptor -> descriptor.id().equals(model));
        return known ? "model listed" : "endpoint reachable; model not listed";
    }

    private String streaming(ProviderConfig provider, String model) {
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger chars = new AtomicInteger();
        ChatRequest request = ChatRequest.builder(model)
                .user("Reply with one short token.")
                .temperature(0.0)
                .maxOutputTokens(512)
                .build();
        client.completeStream(provider, request, chunk -> {
            if (chunk.type() == StreamChunk.Type.CONTENT) {
                chars.addAndGet(chunk.delta().length());
            } else if (chunk.type() == StreamChunk.Type.DONE) {
                done.set(true);
            }
        });
        if (!done.get() || chars.get() == 0) {
            throw new IllegalStateException("stream did not produce text and a terminal chunk");
        }
        return "stream terminated with " + chars.get() + " text chars";
    }

    private String structured(ProviderConfig provider, String model) throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("status").put("type", "string");
        schema.putArray("required").add("status");
        schema.put("additionalProperties", false);
        ChatRequest request = ChatRequest.builder(model)
                .user("Return the requested JSON object.")
                .temperature(0.0)
                .maxOutputTokens(1024)
                .structuredOutput(StructuredOutput.jsonSchema("bridge_probe", schema, false))
                .build();
        ChatResponse response = client.complete(provider, request);
        JsonNode json = mapper.readTree(response.content());
        if (!json.isObject() || !json.hasNonNull("status")) {
            throw new IllegalStateException("structured response is missing status");
        }
        return "schema-conforming JSON object";
    }

    private String nativeTools(ProviderConfig provider, String model) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("note").put("type", "string");
        schema.putArray("required").add("note");
        ToolDefinition tool = new ToolDefinition("bridge_probe",
                "Report a successful read-only probe.", schema);
        TurnRequest request = new TurnRequest(model,
                List.of(io.github.bangshou1st.modelbridge.core.model.Message.user(
                        "Call bridge_probe with note=ok.")),
                List.of(tool), ToolChoice.REQUIRED, 0.0, 1024, null, java.util.Map.of(), null);
        TurnResult result = client.turn(provider, request);
        boolean called = result.toolCalls().stream()
                .anyMatch(call -> "bridge_probe".equals(call.name()));
        if (!called) {
            throw new IllegalStateException("model did not produce the native tool call");
        }
        return "native tool call returned";
    }

    private ProbeStageResult stage(ProbeStage stage, ProbeOperation operation) {
        long started = System.nanoTime();
        try {
            String detail = operation.run();
            return ProbeStageResult.pass(stage, elapsed(started), detail);
        } catch (io.github.bangshou1st.modelbridge.core.security.ModelBridgeException failure) {
            return ProbeStageResult.fail(stage, elapsed(started),
                    failure.category().name().toLowerCase(), failure.category(),
                    failure.upstreamStatus() == 0 ? null : failure.upstreamStatus());
        } catch (Exception failure) {
            return ProbeStageResult.fail(stage, elapsed(started),
                    failure.getClass().getSimpleName(), null, null);
        }
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    @FunctionalInterface
    private interface ProbeOperation {
        String run() throws Exception;
    }
}
