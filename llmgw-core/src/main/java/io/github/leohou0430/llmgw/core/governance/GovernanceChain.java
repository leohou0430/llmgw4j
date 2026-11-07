package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.routing.Route;

import java.util.Map;
import java.util.Optional;

/** The single governance contract shared by BOTH engines. */
public interface GovernanceChain {

    GatewayDecision enter(String authorizationHeader, ParsedRequest request, Route route);

    Optional<String> lookupCache(String keyId, ParsedRequest request);

    void cache(String keyId, ParsedRequest request, String response);

    /**
     * Cache a non-streaming response whose cacheability the caller has ALREADY
     * decided (single-parse hot path: the controller parses the body once and
     * derives both the token count and this flag from the same tree).
     * Default keeps SPI compatibility: forwards to {@link #cache} when true.
     */
    default void cache(String keyId, ParsedRequest request, String response, boolean cacheable) {
        if (cacheable) {
            cache(keyId, request, response);
        }
    }

    void finish(GatewayContext context, boolean success);

    Map<String, Object> snapshot();
}
