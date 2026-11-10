package io.github.leohou0430.llmgw.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/** HTTP composition root. Only app and engine adapters are scanned; foundation packs use Dubbo SPI. */
@SpringBootApplication
@ComponentScan(basePackages = {
        "io.github.leohou0430.llmgw.app",
        "io.github.leohou0430.llmgw.engine.webflux",
        "io.github.leohou0430.llmgw.engine.vt"
})
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}
