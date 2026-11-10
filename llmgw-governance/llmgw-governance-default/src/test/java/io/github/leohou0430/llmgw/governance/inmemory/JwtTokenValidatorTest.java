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
    private static String signedClaims(String claimsJson) throws Exception {
        String header = b64("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(claimsJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + "." + sign(header + "." + claims);
    }

    @Test void malformedNumericDatesCannotBypassTimeValidation() throws Exception {
        var validator = new JwtTokenValidator(props());
        for (String claim : new String[]{"exp", "nbf"}) {
            for (String value : new String[]{"null", "\"not-a-date\"", "{}", "[]", "true"}) {
                assertTrue(validator.validate(signedClaims("{\"iss\":\"test-issuer\",\"sub\":\"alice\",\""
                        + claim + "\":" + value + "}")).isEmpty(), claim + "=" + value);
            }
        }
    }

    @Test void expirationAtTheCurrentInstantIsAlreadyExpired() throws Exception {
        var clock = java.time.Clock.fixed(java.time.Instant.ofEpochSecond(2000000000), java.time.ZoneOffset.UTC);
        var validator = new JwtTokenValidator(props(), clock);
        assertTrue(validator.validate(token(2000000000, "test-issuer", "alice")).isEmpty());
    }

    @Test void claimsMustBeAnObjectAndSubjectMustBeAString() throws Exception {
        var p = props();p.getAuth().setJwtIssuer("");
        var validator = new JwtTokenValidator(p);
        for (String claims : new String[]{"[]", "null", "42", "{\"sub\":123}", "{\"sub\":{}}"}) {
            assertTrue(validator.validate(signedClaims(claims)).isEmpty(), claims);
        }
    }

    private GatewayProperties audienceProps() {
        var p = props();
        p.getAuth().setJwtAudience("gateway");
        return p;
    }

    @Test void configuredAudienceRejectsMissingWrongOrMalformedTargets() throws Exception {
        var validator = new JwtTokenValidator(audienceProps());
        for (String suffix : new String[]{"", ",\"aud\":\"other-service\"", ",\"aud\":[]",
                ",\"aud\":123", ",\"aud\":[\"gateway\",123]"}) {
            assertTrue(validator.validate(signedClaims("{\"iss\":\"test-issuer\",\"sub\":\"alice\""
                    + suffix + "}")).isEmpty(), suffix);
        }
    }

    @Test void configuredAudienceAcceptsStringOrAnArrayContainingTheTarget() throws Exception {
        var validator = new JwtTokenValidator(audienceProps());
        for (String audience : new String[]{"\"gateway\"", "[\"other-service\",\"gateway\"]"}) {
            assertTrue(validator.validate(signedClaims("{\"iss\":\"test-issuer\",\"sub\":\"alice\",\"aud\":"
                    + audience + "}")).isPresent());
        }
    }

    @Test void fractionalNumericDatesAreComparedWithoutTruncation() throws Exception {
        var clock = java.time.Clock.fixed(java.time.Instant.ofEpochSecond(2000000000,500000000), java.time.ZoneOffset.UTC);
        var validator = new JwtTokenValidator(props(), clock);
        assertTrue(validator.validate(signedClaims("{\"iss\":\"test-issuer\",\"exp\":2000000000.500000001}")).isPresent());
        assertTrue(validator.validate(signedClaims("{\"iss\":\"test-issuer\",\"exp\":2000000000.5}")).isEmpty());
        assertTrue(validator.validate(signedClaims("{\"iss\":\"test-issuer\",\"nbf\":2000000000.500000001}")).isEmpty());
        assertTrue(validator.validate(signedClaims("{\"iss\":\"test-issuer\",\"nbf\":2000000000.5}")).isPresent());
    }

    @Test void extraCompactSegmentsAreRejected() throws Exception {
        var validator = new JwtTokenValidator(props());
        String valid = token(System.currentTimeMillis()/1000+600,"test-issuer","alice");
        assertTrue(validator.validate(valid + ".").isEmpty());
        assertTrue(validator.validate(valid + ".extra").isEmpty());
    }

    @Test void configuredIssuerIsNotCoercedFromNumericClaims() throws Exception {
        var p = props();p.getAuth().setJwtIssuer("123");
        assertTrue(new JwtTokenValidator(p).validate(signedClaims("{\"iss\":123,\"sub\":\"alice\"}")).isEmpty());
    }

    @Test void sharedValidatorIsSafeAcrossConcurrentCallers() throws Exception {
        var validator = new JwtTokenValidator(props());
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for (int i=0;i<100;i++) {
                String name="caller-"+i;
                String signed=token(System.currentTimeMillis()/1000+600,"test-issuer",name);
                jobs.add(() -> validator.validate(signed).orElseThrow().id());
            }
            var results=workers.invokeAll(jobs);
            for (int i=0;i<results.size();i++) assertEquals("jwt:caller-"+i,results.get(i).get());
        }
    }

}
