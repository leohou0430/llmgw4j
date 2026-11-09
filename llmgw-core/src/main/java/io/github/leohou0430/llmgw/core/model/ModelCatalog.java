package io.github.leohou0430.llmgw.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Model metadata SPI (v13). One catalog feeds three consumers: $ budget math
 * (prices), context-window-aware fallback decisions (window), and human-facing
 * surfaces like the admin console (provider / display name).
 *
 * <p>Prices are {@code micro-USD per 1M tokens} -- longs, no floating point on
 * the hot path: a model billed $1.20 per million input tokens carries
 * {@code 1_200_000}. Zero price = unknown/free, never a crash.
 */
public interface ModelCatalog {

    Optional<ModelInfo> find(String model);

    List<ModelInfo> all();

    record ModelInfo(
            String model,
            String provider,
            long contextWindowTokens,
            long inputPriceMicroUsdPerMillion,
            long outputPriceMicroUsdPerMillion) {
    }
}
