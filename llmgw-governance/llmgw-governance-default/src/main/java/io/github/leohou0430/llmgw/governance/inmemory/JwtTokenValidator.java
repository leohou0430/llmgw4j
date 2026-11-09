package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.TokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * B1: dependency-free HS256 JWT validation (JDK HMAC + Jackson 3). Verifies
 * signature, exp/nbf (epoch SECONDS) and the configured issuer, then maps the
 * token onto a virtual registry key (id = "jwt:<sub>") carrying the
 * {@code gateway.auth.jwt.quota-tokens} allowance. alg=none and asymmetric
 * algs are rejected: this validator is HS256-only by contract.
 */
public class JwtTokenValidator implements TokenValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

    private final byte[] secret;
    private final String issuer;
    private final long quotaTokens;

    public JwtTokenValidator(GatewayProperties properties) {
        String s = properties.getAuth().getJwtSecret();
        this.secret = (s == null || s.isBlank()) ? null : s.getBytes(StandardCharsets.UTF_8);
        this.issuer = properties.getAuth().getJwtIssuer();
        this.quotaTokens = properties.getAuth().getJwtQuotaTokens();
    }

    public boolean enabled() {
        return secret != null;
    }

    @Override
    public Optional<ApiKeyRegistry.ApiKey> validate(String bearer) {
        if (secret == null || bearer == null) {
            return Optional.empty();
        }
        String token = bearer.startsWith("Bearer ") ? bearer.substring(7).trim() : bearer.trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]),
                    Base64.getUrlDecoder().decode(parts[2]))) {
                return Optional.empty();
            }
            var header = Json.parse(b64(parts[0]));
            var claims = Json.parse(b64(parts[1]));
            if (!"HS256".equals(header.path("alg").asText(""))) {
                return Optional.empty();
            }
            long now = System.currentTimeMillis() / 1000;
            if (claims.path("exp").canConvertToLong() && claims.path("exp").asLong(0) < now) {
                return Optional.empty();
            }
            if (claims.path("nbf").canConvertToLong() && claims.path("nbf").asLong(0) > now) {
                return Optional.empty();
            }
            if (!issuer.isBlank() && !issuer.equals(claims.path("iss").asText(""))) {
                return Optional.empty();
            }
            String sub = claims.path("sub").asText("");
            if (sub.isBlank()) {
                sub = "anonymous";
            }
            return Optional.of(new ApiKeyRegistry.ApiKey("jwt:" + sub, sub,
                    quotaTokens, Set.of(), 0, "jwt", 0, java.util.Map.of()));
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
    }

    private static String b64(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /** Tiny JSON access shim over the Jackson mapper already on the classpath. */
    private static final class Json {
        static tools.jackson.databind.JsonNode parse(String json) {
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
        }
    }
}
