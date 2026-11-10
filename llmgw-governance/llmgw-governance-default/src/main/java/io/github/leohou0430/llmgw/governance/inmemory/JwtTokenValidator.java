package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.core.governance.TokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * B1: dependency-free HS256 JWT validation (JDK HMAC + Jackson 3). Verifies
 * signature, NumericDate exp/nbf and the configured issuer/audience, then maps the
 * token onto a virtual registry key (id = "jwt:<sub>") carrying the
 * {@code gateway.auth.jwt-quota-tokens} allowance. alg=none and asymmetric
 * algs are rejected: this validator is HS256-only by contract.
 */
public class JwtTokenValidator implements TokenValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final byte[] secret;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final long quotaTokens;

    public JwtTokenValidator(GatewayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtTokenValidator(GatewayProperties properties, Clock clock) {
        String s = properties.getAuth().getJwtSecret();
        this.secret = (s == null || s.isBlank()) ? null : s.getBytes(StandardCharsets.UTF_8);
        this.issuer = properties.getAuth().getJwtIssuer();
        this.audience = properties.getAuth().getJwtAudience();
        this.clock = java.util.Objects.requireNonNull(clock);
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
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]),
                    Base64.getUrlDecoder().decode(parts[2]))) {
                return Optional.empty();
            }
            var header = JSON.readTree(b64(parts[0]));
            var claims = JSON.readTree(b64(parts[1]));
            if (!header.isObject() || !claims.isObject()
                    || !"HS256".equals(header.path("alg").asText(""))) {
                return Optional.empty();
            }
            var instant = clock.instant();
            BigDecimal now = BigDecimal.valueOf(instant.getEpochSecond())
                    .add(BigDecimal.valueOf(instant.getNano(), 9));
            if (!validTime(claims.path("exp"), now, true)
                    || !validTime(claims.path("nbf"), now, false)) {
                return Optional.empty();
            }
            if ((claims.has("iss") && !claims.get("iss").isTextual())
                    || (claims.has("sub") && !claims.get("sub").isTextual())
                    || (!issuer.isBlank() && !issuer.equals(claims.path("iss").asText("")))
                    || (!audience.isBlank() && !matchesAudience(claims.path("aud")))) {
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

    private static boolean validTime(JsonNode claim, BigDecimal now, boolean expiration) {
        if (claim.isMissingNode()) return true;
        if (!claim.isNumber()) return false;
        int comparison = now.compareTo(claim.decimalValue());
        return expiration ? comparison < 0 : comparison >= 0;
    }

    private boolean matchesAudience(JsonNode claim) {
        if (claim.isTextual()) return audience.equals(claim.asText());
        if (!claim.isArray()) return false;
        boolean found = false;
        for (var value : claim) {
            if (!value.isTextual()) return false;
            found |= audience.equals(value.asText());
        }
        return found;
    }
}
