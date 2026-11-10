package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Boot configuration binding belongs to the application, never the core model. */
@Configuration(proxyBeanMethods = false)
public class GatewayConfiguration {
    @Bean(initMethod = "validate")
    @ConfigurationProperties(prefix = "gateway")
    public GatewayProperties gatewayProperties() { return new GatewayProperties(); }
}
