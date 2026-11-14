package io.github.bangshou1st.modelbridge.core.model;

/**
 * Optional reasoning controls. Adapters only emit provider fields they explicitly support.
 */
public record ReasoningOptions(String effort, Integer budgetTokens, Boolean enabled) {
    public static ReasoningOptions none() {
        return new ReasoningOptions(null, null, null);
    }

    public ReasoningOptions {
        if (budgetTokens != null && budgetTokens < 0) {
            throw new IllegalArgumentException("budgetTokens must be non-negative");
        }
    }
}
