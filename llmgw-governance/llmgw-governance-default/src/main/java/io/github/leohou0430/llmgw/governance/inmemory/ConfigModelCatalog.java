package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.model.ModelCatalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Config-driven default catalog: entries come from {@code gateway.models[]}.
 * Lookups are case-insensitive on the model name (clients write
 * {@code Qwen-Plus}, config says {@code qwen-plus}); an entry named {@code *}
 * is the fallback for unknown models.
 */
public class ConfigModelCatalog implements ModelCatalog {

    private final Map<String, ModelInfo> byModel;
    private final ModelInfo wildcard;

    public ConfigModelCatalog(GatewayProperties properties) {
        this.byModel = properties.getModels().stream()
                .map(ConfigModelCatalog::toInfo)
                .collect(Collectors.toUnmodifiableMap(
                        info -> info.model().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (first, second) -> first));
        this.wildcard = byModel.get("*");
    }

    private static ModelInfo toInfo(GatewayProperties.ModelDef def) {
        return new ModelInfo(def.getModel(), def.getProvider(),
                def.getContextWindowTokens(),
                def.getInputPriceMicroUsdPerMillion(),
                def.getOutputPriceMicroUsdPerMillion());
    }

    @Override
    public Optional<ModelInfo> find(String model) {
        if (model == null || model.isBlank()) {
            return Optional.ofNullable(wildcard);
        }
        Optional<ModelInfo> exact = Optional.ofNullable(byModel.get(model.toLowerCase(Locale.ROOT)));
        return exact.isPresent() ? exact : Optional.ofNullable(wildcard);
    }

    @Override
    public List<ModelInfo> all() {
        return List.copyOf(byModel.values());
    }
}
