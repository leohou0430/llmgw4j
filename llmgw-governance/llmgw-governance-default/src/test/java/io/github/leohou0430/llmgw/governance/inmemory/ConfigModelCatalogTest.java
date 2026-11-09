package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.model.ModelCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13 model catalog: exact (case-insensitive) lookups, a {@code *} fallback
 * for unknown models, and micro-USD-per-million price semantics untouched.
 */
class ConfigModelCatalogTest {

    private static GatewayProperties propertiesWith(GatewayProperties.ModelDef... defs) {
        GatewayProperties properties = new GatewayProperties();
        properties.getModels().addAll(List.of(defs));
        return properties;
    }

    @Test
    void exactLookupIsCaseInsensitive() {
        GatewayProperties.ModelDef qwen = new GatewayProperties.ModelDef();
        qwen.setModel("qwen-plus");
        qwen.setProvider("dashscope");
        qwen.setContextWindowTokens(131_072);
        qwen.setInputPriceMicroUsdPerMillion(400_000);
        qwen.setOutputPriceMicroUsdPerMillion(1_200_000);
        ModelCatalog catalog = new ConfigModelCatalog(propertiesWith(qwen));

        ModelCatalog.ModelInfo info = catalog.find("Qwen-Plus").orElseThrow();
        assertEquals("dashscope", info.provider());
        assertEquals(131_072, info.contextWindowTokens());
        assertEquals(400_000, info.inputPriceMicroUsdPerMillion());
        assertEquals(1_200_000, info.outputPriceMicroUsdPerMillion());
    }

    @Test
    void wildcardEntryAnswersUnknownModels() {
        GatewayProperties.ModelDef fallback = new GatewayProperties.ModelDef();
        fallback.setModel("*");
        fallback.setProvider("default");
        fallback.setInputPriceMicroUsdPerMillion(1);
        ModelCatalog catalog = new ConfigModelCatalog(propertiesWith(fallback));
        assertTrue(catalog.find("totally-unknown").isPresent());
        assertEquals("default", catalog.find("totally-unknown").orElseThrow().provider());
        // Blank/null model names also fall back.
        assertTrue(catalog.find(null).isPresent());
        assertTrue(catalog.find("").isPresent());
    }

    @Test
    void unknownModelsWithoutWildcardAreAbsentNotZero() {
        ModelCatalog catalog = new ConfigModelCatalog(propertiesWith());
        assertTrue(catalog.find("mystery").isEmpty(), "no wildcard = honest absence");
        assertTrue(catalog.all().isEmpty());
    }
}
