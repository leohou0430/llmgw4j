package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogCursorException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Signs and verifies opaque pagination cursors with HMAC-SHA256. The token binds the
 * tenant, project, filter and the first page's high-water mark; tampering or a wrong
 * key is rejected. Scope binding (that the token's tenant/project/filter match the
 * current request) is enforced by the caller.
 */
final class CursorCodec {
    private static final char SEP = '\u0001';

    record Token(String tenantId, String projectId, String typeFilter, long highWaterMark, long lastSequence) {
    }

    private final byte[] key;

    CursorCodec(byte[] key) {
        this.key = Objects.requireNonNull(key, "key").clone();
    }

    static CursorCodec random() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new CursorCodec(key);
    }

    String encode(Token token) {
        String payload = String.join(String.valueOf(SEP),
                token.tenantId(), token.projectId(),
                token.typeFilter() == null ? "" : token.typeFilter(),
                Long.toString(token.highWaterMark()), Long.toString(token.lastSequence()));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payloadBytes));
        return body + "." + signature;
    }

    Token decode(String cursor) {
        int dot = cursor.indexOf('.');
        if (dot < 0) {
            throw new CatalogCursorException("malformed cursor");
        }
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(cursor.substring(0, dot));
        } catch (IllegalArgumentException failure) {
            throw new CatalogCursorException("malformed cursor");
        }
        String actual = cursor.substring(dot + 1);
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payloadBytes));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new CatalogCursorException("cursor signature mismatch");
        }
        String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split(String.valueOf(SEP), -1);
        if (parts.length != 5) {
            throw new CatalogCursorException("malformed cursor payload");
        }
        try {
            return new Token(parts[0], parts[1], parts[2].isEmpty() ? null : parts[2],
                    Long.parseLong(parts[3]), Long.parseLong(parts[4]));
        } catch (NumberFormatException failure) {
            throw new CatalogCursorException("malformed cursor numbers");
        }
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("HMAC failure", failure);
        }
    }
}