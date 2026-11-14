package io.github.bangshou1st.modelbridge.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GatewayMetrics {
    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void success(Timer.Sample sample, String endpoint, String provider) {
        sample.stop(Timer.builder("ai.bridge.gateway.latency")
                .tag("endpoint", endpoint)
                .tag("provider", safe(provider))
                .tag("outcome", "success")
                .publishPercentileHistogram()
                .register(registry));
    }

    public void failure(Timer.Sample sample, String endpoint, String provider, String category) {
        sample.stop(Timer.builder("ai.bridge.gateway.latency")
                .tag("endpoint", endpoint)
                .tag("provider", safe(provider))
                .tag("outcome", "error")
                .publishPercentileHistogram()
                .register(registry));
        Counter.builder("ai.bridge.gateway.errors")
                .tag("endpoint", endpoint)
                .tag("provider", safe(provider))
                .tag("category", safe(category))
                .register(registry)
                .increment();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
