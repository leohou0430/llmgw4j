package io.github.leohou0430.llmgw.app;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class NativeRuntimeBridgeTest {
    @TempDir Path folder;
    private AnnotationConfigApplicationContext context(Map<String,Object> properties) {
        var c=new AnnotationConfigApplicationContext();
        c.getEnvironment().getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        c.getEnvironment().getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var values=new HashMap<String,Object>(properties);values.put("gateway.keys-store-path",folder.resolve("keys.json").toString());
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",values));
        ConfigurationPropertiesBindingPostProcessor.register(c);
        c.register(GatewayConfiguration.class,GatewayRuntimeConfiguration.class);
        return c;
    }
    @Test void configuredNamedModuleReplacesTheExportedNativeService() {
        try(var c=context(Map.of("gateway.extension-modules[0]","test-policy"))) {
            c.refresh();assertInstanceOf(TestPolicyModule.DenyLimiter.class,c.getBean(TokenLimiter.class));
        }
    }
    @Test void unknownConfiguredModuleFailsStartup() {
        try(var c=context(Map.of("gateway.extension-modules[0]","missing-module"))) {
            assertThrows(RuntimeException.class,c::refresh);
        }
    }
    @Test void legacyTlsAndTimeoutAreMappedUnlessCanonicalValuesAreProvided() {
        try(var c=context(Map.of("spring.data.redis.ssl.enabled","true","spring.data.redis.timeout","3s"))) {
            c.refresh();var p=c.getBean(GatewayProperties.class);assertTrue(p.getRedis().isSsl());assertEquals(3000,p.getRedis().getTimeoutMs());
        }
        try(var c=context(Map.of("spring.data.redis.ssl.enabled","true","gateway.redis.ssl","false"))) {
            c.refresh();assertFalse(c.getBean(GatewayProperties.class).getRedis().isSsl());
        }
    }
}
