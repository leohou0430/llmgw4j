package io.github.leohou0430.llmgw.core.routing;

import java.util.List;

public interface Router {
    Route resolve(String model);

    List<Route> fallbacks(Route primary);

    List<Route> all();
}
