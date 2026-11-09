package io.github.leohou0430.llmgw.core.governance;

import java.util.Optional;

/**
 * B1 ingress-auth SPI: validates a bearer token that is NOT a registry key
 * (e.g. an HS256 JWT minted by your own auth service) and maps it onto a
 * virtual {@link ApiKeyRegistry.ApiKey}. The default pack ships an HS256
 * implementation ({@code gateway.auth.jwt.*}); deploy custom validators
 * (RS256/OIDC introspection) by declaring this bean -- chain order: registry
 * keys first, validator second, 401 only when both miss.
 */
public interface TokenValidator {

    /**
     * @param bearer the raw Authorization header value (with or without the
     *               "Bearer " prefix)
     */
    Optional<ApiKeyRegistry.ApiKey> validate(String bearer);
}
