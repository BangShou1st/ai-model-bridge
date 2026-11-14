package io.github.bangshou1st.modelbridge.core.http;

import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.EndpointConfig;

import java.util.Map;
import java.util.regex.Pattern;

/** Stable endpoint construction with explicit per-provider path overrides. */
public final class Endpoints {
    private static final Pattern VERSION_SUFFIX =
            Pattern.compile("/v\\d+[a-z0-9-]*$", Pattern.CASE_INSENSITIVE);

    private Endpoints() {
    }

    public static String endpoint(ProviderConfig config, String optionKey, String versionSegment,
                                  String suffix) {
        Object override = config.options().get(optionKey);
        String typed = typedPath(config.endpointConfig(), optionKey);
        if (typed != null && !typed.isBlank()) {
            override = typed;
        }
        if (override instanceof String path && !path.isBlank()) {
            return path.startsWith("http://") || path.startsWith("https://")
                    ? path : join(config.baseUrl(), path);
        }
        String base = stripTrailingSlash(config.baseUrl());
        String normalizedVersion = versionSegment.startsWith("/") ? versionSegment : "/" + versionSegment;
        String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        if (base.endsWith(normalizedSuffix)) {
            return base;
        }
        if (VERSION_SUFFIX.matcher(base).find()) {
            return base + normalizedSuffix;
        }
        return base + normalizedVersion + normalizedSuffix;
    }

    public static String join(String base, String path) {
        String left = stripTrailingSlash(base);
        if (path == null || path.isBlank()) {
            return left;
        }
        return left + (path.startsWith("/") ? path : "/" + path);
    }

    public static String option(ProviderConfig config, String key, String fallback) {
        EndpointConfig endpoints = config.endpointConfig();
        String typed = switch (key) {
            case "anthropicVersion" -> endpoints.anthropicVersion();
            case "embeddingTaskType" -> endpoints.embeddingTaskType();
            case "chatPath" -> endpoints.chatPath();
            case "modelsPath" -> endpoints.modelsPath();
            case "embeddingsPath" -> endpoints.embeddingsPath();
            case "embeddingsLegacyPath" -> endpoints.embeddingsLegacyPath();
            case "rerankPath" -> endpoints.rerankPath();
            default -> null;
        };
        if (typed != null && !typed.isBlank()) {
            return typed;
        }
        Object value = config.options().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static Map<String, String> configHeaders(ProviderConfig config) {
        return config.headers();
    }

    private static String stripTrailingSlash(String value) {
        String result = value.strip();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String typedPath(EndpointConfig config, String optionKey) {
        if (config == null) {
            return null;
        }
        return switch (optionKey) {
            case "chatPath" -> config.chatPath();
            case "modelsPath" -> config.modelsPath();
            case "embeddingsPath" -> config.embeddingsPath();
            case "embeddingsLegacyPath" -> config.embeddingsLegacyPath();
            case "rerankPath" -> config.rerankPath();
            default -> null;
        };
    }
}
