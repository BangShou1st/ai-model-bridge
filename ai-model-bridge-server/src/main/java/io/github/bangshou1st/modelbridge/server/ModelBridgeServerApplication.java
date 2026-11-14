package io.github.bangshou1st.modelbridge.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class ModelBridgeServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelBridgeServerApplication.class, args);
    }
}
