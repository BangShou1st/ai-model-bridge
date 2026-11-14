package io.github.bangshou1st.modelbridge.core.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.StructuredOutput;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Unifies JSON syntax/schema validation and one prompt-only fallback for every protocol. */
public final class StructuredOutputInterceptor implements ChatInterceptor {
    private final StructuredOutputValidator validator;

    public StructuredOutputInterceptor(ObjectMapper mapper) {
        this.validator = new StructuredOutputValidator(mapper);
    }

    public StructuredOutputInterceptor(StructuredOutputValidator validator) {
        this.validator = validator;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public ChatResponse intercept(ChatRequest request, ChatInvocation next) {
        StructuredOutput structured = request.structuredOutput();
        if (structured == null || !structured.enabled()) {
            return next.invoke(request);
        }
        try {
            ChatResponse response = next.invoke(request);
            validator.validate(response.content(), structured, response.provider());
            return withFallbackMetadata(response, false);
        } catch (ModelBridgeException failure) {
            if (!fallbackAllowed(structured, failure)) {
                throw normalize(failure, responseProvider(failure));
            }
            ChatRequest fallbackRequest = request.withStructuredOutput(
                    new StructuredOutput(StructuredOutput.Mode.PROMPT_JSON,
                            structured.name(), structured.schema(), false));
            ChatResponse response = next.invoke(fallbackRequest);
            validator.validate(response.content(), fallbackRequest.structuredOutput(),
                    response.provider());
            return withFallbackMetadata(response, true);
        }
    }

    @Override
    public StreamHandle interceptStream(ChatRequest request, Consumer<StreamChunk> consumer,
                                        ChatStreamInvocation next) {
        return next.invoke(request, consumer);
    }

    private static boolean fallbackAllowed(StructuredOutput structured,
                                           ModelBridgeException failure) {
        if (structured.mode() == StructuredOutput.Mode.PROMPT_JSON
                || !structured.fallbackToPromptJson()) {
            return false;
        }
        if (failure.category() == ErrorCategory.BAD_REQUEST) {
            return failure.upstreamStatus() == 400 || failure.upstreamStatus() == 404
                    || failure.upstreamStatus() == 422;
        }
        return failure.category() == ErrorCategory.SCHEMA
                || failure.category() == ErrorCategory.INVALID_RESPONSE;
    }

    private static ModelBridgeException normalize(ModelBridgeException failure, String provider) {
        if (failure.category() == ErrorCategory.INVALID_RESPONSE) {
            return ModelBridgeException.schema("structured output was not valid JSON", provider);
        }
        return failure;
    }

    private static String responseProvider(ModelBridgeException failure) {
        return failure.provider();
    }

    static ChatResponse withFallbackMetadata(ChatResponse response, boolean fallback) {
        Map<String, Object> metadata = new LinkedHashMap<>(response.metadata());
        metadata.put("structuredOutputFallback", fallback);
        return new ChatResponse(response.content(), response.reasoningContent(),
                response.toolCalls(), response.finishReason(), response.usage(),
                response.provider(), response.model(), response.latencyMs(), metadata,
                response.parts());
    }
}
