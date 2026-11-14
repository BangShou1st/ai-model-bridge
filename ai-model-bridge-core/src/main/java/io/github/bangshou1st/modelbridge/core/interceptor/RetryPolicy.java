package io.github.bangshou1st.modelbridge.core.interceptor;

import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;

import java.time.Duration;
import java.util.Set;

public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double multiplier,
        boolean respectRetryAfter,
        Set<ErrorCategory> retryCategories
) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        initialBackoff = initialBackoff == null ? Duration.ofMillis(250) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(10) : maxBackoff;
        if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("backoff durations must be non-negative");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
        retryCategories = retryCategories == null ? Set.of() : Set.copyOf(retryCategories);
    }

    public static RetryPolicy disabled() {
        return new RetryPolicy(1, Duration.ofMillis(250), Duration.ofSeconds(10), 1.5,
                true, Set.of());
    }

    public static RetryPolicy of(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ofMillis(250), Duration.ofSeconds(10),
                1.5, true, Set.of());
    }

    public boolean allows(ErrorCategory category) {
        return category != null && category.retryable()
                && (retryCategories.isEmpty() || retryCategories.contains(category));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxAttempts = 1;
        private Duration initialBackoff = Duration.ofMillis(250);
        private Duration maxBackoff = Duration.ofSeconds(10);
        private double multiplier = 1.5;
        private boolean respectRetryAfter = true;
        private Set<ErrorCategory> retryCategories = Set.of();

        public Builder maxAttempts(int value) { this.maxAttempts = value; return this; }
        public Builder initialBackoff(Duration value) { this.initialBackoff = value; return this; }
        public Builder maxBackoff(Duration value) { this.maxBackoff = value; return this; }
        public Builder multiplier(double value) { this.multiplier = value; return this; }
        public Builder respectRetryAfter(boolean value) { this.respectRetryAfter = value; return this; }
        public Builder retryCategories(Set<ErrorCategory> value) {
            this.retryCategories = value;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, multiplier,
                    respectRetryAfter, retryCategories);
        }
    }
}
