package io.github.leohou0430.llmgw.provider;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionPoint;

/** Optional provider request validation and explicitly invoked connectivity check. */
@ExtensionPoint("openai")
public interface ProviderVerifier {
    boolean validate(String model, String prompt);
    String verify(GatewayProperties properties);
}
