package io.github.bangshou1st.modelbridge.core.http;

import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;

import java.util.Map;

/** Stable endpoint construction with explicit per-provider path overrides. */
public final class Endpoints {
    private Endpoints() {
    }

    public static String endpoint(ProviderConfig config, String optionKey, String versionSegment,
                                  String suffix) {
        Object override = config.options().get(optionKey);
        if (override instanceof String path && !path.isBlank()) {
            return path.startsWith("http://") || path.startsWith("https://")
                    ? path : join(config.baseUrl(), path);
        }
        String base = stripTrailingSlash(config.baseUrl());
        String normalizedVersion = versionSegment.startsWith("/") ? versionSegment : "/" + versionSegment;
        String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        if (base.endsWith(normalizedVersion)) {
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
}
