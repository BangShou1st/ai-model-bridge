package io.github.bangshou1st.modelbridge.core.probe;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;

public record ProbeStageResult(
        ProbeStage stage,
        boolean passed,
        @JsonProperty("duration_ms")
        long durationMs,
        String detail,
        @JsonProperty("error_category")
        ErrorCategory errorCategory,
        @JsonProperty("http_status")
        Integer httpStatus
) {
    public static ProbeStageResult pass(ProbeStage stage, long durationMs, String detail) {
        return new ProbeStageResult(stage, true, durationMs, detail, null, null);
    }

    public static ProbeStageResult fail(ProbeStage stage, long durationMs, String detail,
                                        ErrorCategory category, Integer httpStatus) {
        return new ProbeStageResult(stage, false, durationMs, detail, category, httpStatus);
    }
}
