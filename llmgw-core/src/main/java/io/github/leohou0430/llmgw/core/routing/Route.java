package io.github.leohou0430.llmgw.core.routing;

import java.util.List;

public record Route(
        String id,
        String model,
        String targetModel,
        String targetUrl,
        String apiKey,
        int weight,
        List<String> fallbackIds) {
}
