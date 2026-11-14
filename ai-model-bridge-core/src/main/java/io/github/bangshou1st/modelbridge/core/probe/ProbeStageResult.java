package io.github.bangshou1st.modelbridge.core.probe;

import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;

public record ProbeStageResult(
        ProbeStage stage,
        boolean passed,
        long durationMs,
        String detail,
        ErrorCategory errorCategory,
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
