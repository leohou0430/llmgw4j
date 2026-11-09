package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** B1: dependency-free HS256 validation — signature, exp/nbf, issuer, sub mapping. */
class JwtTokenValidatorTest {

    private static final String SECRET = "test-secret";

    private GatewayProperties props() {
        GatewayProperties p = new GatewayProperties();
        p.getAuth().setJwtSecret(SECRET);
        p.getAuth().setJwtIssuer("test-issuer");
        p.getAuth().setJwtQuotaTokens(123456);
        return p;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return b64(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String token(long exp, String iss, String sub) throws Exception {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"exp\":" + exp + ",\"iss\":\"" + iss + "\",\"sub\":\"" + sub + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + "." + sign(header + "." + claims);
    }

    @Test
    void validTokenMapsToVirtualKey() throws Exception {
        var validator = new JwtTokenValidator(props());
        var key = validator.validate("Bearer " + token(
                System.currentTimeMillis() / 1000 + 600, "test-issuer", "alice")).orElseThrow();
        assertEquals("jwt:alice", key.id());
        assertEquals("alice", key.name());
        assertEquals(123456, key.quotaTokens());
        assertEquals("jwt", key.group());
    }

    @Test
    void expiredTokenRejected() throws Exception {
        var validator = new JwtTokenValidator(props());
        assertTrue(validator.validate("Bearer " + token(
                System.currentTimeMillis() / 1000 - 10, "test-issuer", "alice")).isEmpty());
    }

    @Test
    void wrongIssuerRejected() throws Exception {
        var validator = new JwtTokenValidator(props());
        assertTrue(validator.validate("Bearer " + token(
                System.currentTimeMillis() / 1000 + 600, "evil", "alice")).isEmpty());
    }

    @Test
    void tamperedSignatureRejected() throws Exception {
        var validator = new JwtTokenValidator(props());
        String t = token(System.currentTimeMillis() / 1000 + 600, "test-issuer", "alice");
        assertTrue(validator.validate("Bearer " + t.substring(0, t.length() - 3) + "abc").isEmpty());
    }

    @Test
    void algNoneRejected() throws Exception {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + ",\"sub\":\"x\"}")
                .getBytes(StandardCharsets.UTF_8));
        var validator = new JwtTokenValidator(props());
        assertTrue(validator.validate("Bearer " + header + "." + claims + ".").isEmpty());
    }

    @Test
    void disabledValidatorAlwaysEmpty() {
        GatewayProperties p = new GatewayProperties();
        p.getAuth().setJwtSecret("");
        assertFalse(new JwtTokenValidator(p).enabled());
        assertTrue(new JwtTokenValidator(p).validate("Bearer anything").isEmpty());
    }

    @Test
    void bearerPrefixOptional() throws Exception {
        var validator = new JwtTokenValidator(props());
        var withPrefix = validator.validate("Bearer " + token(
                System.currentTimeMillis() / 1000 + 600, "test-issuer", "a")).orElseThrow();
        var without = validator.validate(token(
                System.currentTimeMillis() / 1000 + 600, "test-issuer", "a")).orElseThrow();
        assertEquals(withPrefix, without);
    }
}
