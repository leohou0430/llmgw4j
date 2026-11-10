package io.github.leohou0430.llmgw.provider;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.apache.dubbo.common.extension.SPI;

/** Optional provider request validation and explicitly invoked connectivity check. */
@SPI("openai")
public interface ProviderVerifier {
    boolean validate(String model, String prompt);
    String verify(GatewayProperties properties);
}
