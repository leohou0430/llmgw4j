package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * Composition root. IMPORTANT: component scanning deliberately EXCLUDES
 * {@code io.github.leohou0430.llmgw.governance} -- the default governance implementations are
 * registered as an auto-configuration ({@code META-INF/spring/...AutoConfiguration.imports})
 * guarded by {@code @ConditionalOnMissingBean}, so user-supplied SPI beans always
 * win. Scanning that package here would re-register the defaults as ordinary
 * beans and break that ordering guarantee.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "io.github.leohou0430.llmgw.app",
        "io.github.leohou0430.llmgw.engine.webflux",
        "io.github.leohou0430.llmgw.engine.vt",
        "io.github.leohou0430.llmgw.provider.openai"
})
@EnableConfigurationProperties(GatewayProperties.class)
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}
