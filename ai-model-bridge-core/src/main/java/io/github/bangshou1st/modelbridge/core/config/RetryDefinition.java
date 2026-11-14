package io.github.bangshou1st.modelbridge.core.config;

import io.github.bangshou1st.modelbridge.core.interceptor.RetryPolicy;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

public class RetryDefinition {
    private int maxAttempts = 1;
    private Duration initialBackoff = Duration.ofMillis(500);
    private Duration maxBackoff = Duration.ofSeconds(10);
    private double multiplier = 1.5;
    private boolean respectRetryAfter = true;
    private Set<ErrorCategory> retryCategories = new LinkedHashSet<>();

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public boolean isRespectRetryAfter() {
        return respectRetryAfter;
    }

    public void setRespectRetryAfter(boolean respectRetryAfter) {
        this.respectRetryAfter = respectRetryAfter;
    }

    public Set<ErrorCategory> getRetryCategories() {
        return retryCategories;
    }

    public void setRetryCategories(Set<ErrorCategory> retryCategories) {
        this.retryCategories = retryCategories == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(retryCategories);
    }

    public RetryPolicy toRetryPolicy() {
        return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, multiplier,
                respectRetryAfter, retryCategories);
    }

    public RetryDefinition copy() {
        RetryDefinition copy = new RetryDefinition();
        copy.maxAttempts = maxAttempts;
        copy.initialBackoff = initialBackoff;
        copy.maxBackoff = maxBackoff;
        copy.multiplier = multiplier;
        copy.respectRetryAfter = respectRetryAfter;
        copy.retryCategories = new LinkedHashSet<>(retryCategories);
        return copy;
    }
}
