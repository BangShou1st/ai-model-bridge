package io.github.bangshou1st.modelbridge.starter;

import io.github.bangshou1st.modelbridge.core.config.BridgeConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spring binding facade over the Spring-free declarative configuration model. */
@ConfigurationProperties(prefix = "ai.bridge")
public class ModelBridgeProperties extends BridgeConfig {
}
