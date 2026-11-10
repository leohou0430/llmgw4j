package io.github.leohou0430.llmgw.provider.http.jdk;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;

public final class JdkHttpModule implements ExtensionModule {
    @Override public void configure(ExtensionContext context) {
        context.bindDefault(UpstreamProvider.class, () -> new JdkHttpUpstream(context.get(GatewayProperties.class)));
    }
}
