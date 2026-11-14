package io.github.bangshou1st.modelbridge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.EmbeddingResult;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.RerankResult;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class GatewayController {
    private final GatewayModelCatalog catalog;
    private final ModelBridgeClient client;
    private final OpenAiWireMapper wire;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper;

    public GatewayController(GatewayModelCatalog catalog, ModelBridgeClient client,
                             OpenAiWireMapper wire, GatewayMetrics metrics, ObjectMapper mapper) {
        this.catalog = catalog;
        this.client = client;
        this.wire = wire;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @GetMapping(path = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ObjectNode> models() {
        return Mono.fromSupplier(() -> mapper.valueToTree(catalog.modelList()));
    }

    @PostMapping(path = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> chatCompletions(@RequestBody JsonNode body) {
        OpenAiWireMapper.ParsedChat parsed = wire.parseChat(body);
        GatewayModelCatalog.ResolvedModel route = catalog.resolve(parsed.requestedModel());
        Timer.Sample sample = metrics.start();
        ChatRequest upstreamRequest = parsed.request().withModel(route.model().getId());
        if (parsed.stream()) {
            return Mono.just(streamChat(parsed, route, sample));
        }
        return Mono.<ResponseEntity<?>>fromCallable(() -> {
                    ChatResponse response = client.complete(route.provider(), upstreamRequest);
                    metrics.success(sample, "chat", route.upstreamId());
                    return ResponseEntity.ok(wire.chatCompletion(response, parsed.requestedModel()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    metrics.failure(sample, "chat", route.upstreamId(), category(error));
                    return Mono.error(error);
                });
    }

    @PostMapping(path = "/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ObjectNode>> embeddings(@RequestBody JsonNode body) {
        var request = wire.parseEmbedding(body);
        GatewayModelCatalog.ResolvedModel route = catalog.resolve(request.model());
        if (route.provider().type() != ProviderType.OPENAI_COMPATIBLE
                && route.provider().type() != ProviderType.OLLAMA) {
            throw GatewayException.badRequest("configured upstream does not support embeddings");
        }
        Timer.Sample sample = metrics.start();
        return Mono.fromCallable(() -> {
                    EmbeddingResult result = client.embed(route.provider(),
                            request.withModel(route.model().getId()));
                    metrics.success(sample, "embeddings", route.upstreamId());
                    return ResponseEntity.ok(wire.embeddingResponse(result, request.model()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    metrics.failure(sample, "embeddings", route.upstreamId(), category(error));
                    return Mono.error(error);
                });
    }

    @PostMapping(path = "/rerank", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ObjectNode>> rerank(@RequestBody JsonNode body) {
        var request = wire.parseRerank(body);
        GatewayModelCatalog.ResolvedModel route = catalog.resolve(request.model());
        if (route.provider().type() != ProviderType.LLAMA_CPP) {
            throw GatewayException.badRequest("configured upstream does not support llama.cpp rerank");
        }
        Timer.Sample sample = metrics.start();
        return Mono.fromCallable(() -> {
                    RerankResult result = client.rerank(route.provider(),
                            request.withModel(route.model().getId()));
                    metrics.success(sample, "rerank", route.upstreamId());
                    return ResponseEntity.ok(wire.rerankResponse(result, request.model()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    metrics.failure(sample, "rerank", route.upstreamId(), category(error));
                    return Mono.error(error);
                });
    }

    private ResponseEntity<Flux<ServerSentEvent<String>>> streamChat(
            OpenAiWireMapper.ParsedChat parsed,
            GatewayModelCatalog.ResolvedModel route,
            Timer.Sample sample) {
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        Flux<ServerSentEvent<String>> events = Flux.create(sink -> Schedulers.boundedElastic().schedule(() -> {
            try {
                if (sink.isCancelled()) {
                    return;
                }
                sink.next(event(wire.streamInitial(id, created, parsed.requestedModel())));
                client.completeStream(route.provider(),
                        parsed.request().withModel(route.model().getId()), chunk -> {
                    if (sink.isCancelled()) {
                        return;
                    }
                    ObjectNode payload = wire.streamChunk(chunk, id, created, parsed.requestedModel());
                    sink.next(event(payload));
                });
                sink.next(event("[DONE]"));
                metrics.success(sample, "chat.stream", route.upstreamId());
                sink.complete();
            } catch (Exception error) {
                metrics.failure(sample, "chat.stream", route.upstreamId(), category(error));
                if (!sink.isCancelled()) {
                    sink.next(event(wire.error(streamErrorMessage(error),
                            "api_error", streamErrorCode(error), retryable(error))));
                    sink.next(event("[DONE]"));
                    sink.complete();
                }
            }
        }));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(events);
    }

    private ServerSentEvent<String> event(ObjectNode payload) {
        return ServerSentEvent.builder(wire.stringify(payload)).build();
    }

    private static ServerSentEvent<String> event(String data) {
        return ServerSentEvent.builder(data).build();
    }

    private static String category(Throwable error) {
        return error instanceof ModelBridgeException bridge
                ? bridge.category().name() : "INTERNAL";
    }

    private static String streamErrorMessage(Throwable error) {
        if (error instanceof ModelBridgeException bridge) {
            return "upstream " + bridge.category().name().toLowerCase();
        }
        return "gateway stream failed";
    }

    private static String streamErrorCode(Throwable error) {
        if (error instanceof ModelBridgeException bridge) {
            return bridge.category().name().toLowerCase();
        }
        return "stream_error";
    }

    private static boolean retryable(Throwable error) {
        return error instanceof ModelBridgeException bridge && bridge.retryable();
    }
}
