package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.ConfigValidator;
import io.github.leohou0430.llmgw.control.PlatformPolicy;
import io.github.leohou0430.llmgw.control.UpstreamCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Default upstream-config validation. Rejects a disallowed scheme; a private/loopback/
 * link-local/metadata IPv4 literal unless the platform policy allows private networks
 * (a project cannot relax this); out-of-bounds timeout/concurrency; and forwarding of
 * client credentials/sensitive headers. DNS-name rebinding is not resolvable at this
 * structural layer and must be re-checked at connect time (documented, not claimed here).
 */
public final class DefaultConfigValidator implements ConfigValidator {
    private static final long MIN_TIMEOUT_MILLIS = 50L;
    private static final long MAX_TIMEOUT_MILLIS = 600_000L;
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 10_000;
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "proxy-authorization", "x-admin-token", "x-api-key");

    @Override
    public List<String> validate(UpstreamCandidate candidate, PlatformPolicy policy) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(policy, "policy");
        List<String> violations = new ArrayList<>();

        if (candidate.name() == null || candidate.name().isBlank()) {
            violations.add("name is required");
        }
        if (!policy.schemeAllowed(candidate.scheme())) {
            violations.add("scheme not allowed: " + candidate.scheme());
        }
        if (candidate.host() == null || candidate.host().isBlank()) {
            violations.add("host is required");
        } else if (!policy.allowPrivateNetwork() && isRestrictedLiteral(candidate.host())) {
            violations.add("host is a private/loopback/metadata address: " + candidate.host());
        }
        if (candidate.timeoutMillis() < MIN_TIMEOUT_MILLIS || candidate.timeoutMillis() > MAX_TIMEOUT_MILLIS) {
            violations.add("timeout out of bounds: " + candidate.timeoutMillis());
        }
        if (candidate.maxConcurrency() < MIN_CONCURRENCY || candidate.maxConcurrency() > MAX_CONCURRENCY) {
            violations.add("maxConcurrency out of bounds: " + candidate.maxConcurrency());
        }
        for (String header : candidate.forwardedHeaders()) {
            if (header != null && SENSITIVE_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                violations.add("sensitive header must not be forwarded: " + header);
            }
        }
        return violations;
    }

    private static boolean isRestrictedLiteral(String host) {
        if (!host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return false;
        }
        String[] parts = host.split("\\.");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        if (a == 0 || a == 127 || a == 10) {
            return true; // this-network, loopback, private 10/8
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true; // private 172.16/12
        }
        if (a == 192 && b == 168) {
            return true; // private 192.168/16
        }
        return a == 169 && b == 254; // link-local + metadata 169.254.169.254
    }
}