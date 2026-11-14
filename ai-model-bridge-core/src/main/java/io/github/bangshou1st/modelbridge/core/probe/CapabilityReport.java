package io.github.bangshou1st.modelbridge.core.probe;

import java.time.Instant;
import java.util.List;

public record CapabilityReport(
        String provider,
        String model,
        Instant startedAt,
        long durationMs,
        List<ProbeStageResult> stages
) {
    public CapabilityReport {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public boolean supportsAll() {
        return stages.size() == ProbeStage.values().length && stages.stream().allMatch(ProbeStageResult::passed);
    }

    public boolean passed(ProbeStage stage) {
        return stages.stream().anyMatch(result -> result.stage() == stage && result.passed());
    }
}
