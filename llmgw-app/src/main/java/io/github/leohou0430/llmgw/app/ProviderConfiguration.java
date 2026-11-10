package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.provider.ProviderVerifier;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP application bridge; provider implementations never depend on Spring. */
@Configuration(proxyBeanMethods = false)
public class ProviderConfiguration {
    @Bean public ProviderVerifier providerVerifier() {
        return ExtensionLoader.getExtensionLoader(ProviderVerifier.class).getDefaultExtension();
    }
}
