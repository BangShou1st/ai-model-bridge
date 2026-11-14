package io.github.bangshou1st.modelbridge.core.protocol;

import java.util.List;

/** ServiceLoader-compatible extension point for additional protocols. */
public interface BridgePlugin {
    String id();

    List<Object> adapters();
}
