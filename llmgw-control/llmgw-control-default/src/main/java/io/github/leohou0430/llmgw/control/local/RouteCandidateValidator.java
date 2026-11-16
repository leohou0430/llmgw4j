package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.ConfigValidator;
import io.github.leohou0430.llmgw.control.PlatformPolicy;
import io.github.leohou0430.llmgw.control.UpstreamCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges a management-API route spec (untyped map from JSON) to {@link UpstreamCandidate}
 * and runs the platform validator. Defaults keep a spec minimal but every security-relevant
 * field is validated: a missing/odd spec yields violations instead of silently passing.
 */
public final class RouteCandidateValidator {
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final int DEFAULT_MAX_CONCURRENCY = 100;

    private final ConfigValidator validator;

    public RouteCandidateValidator(ConfigValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public List<String> check(Map<String, Object> spec, PlatformPolicy policy) {
        if (spec == null) {
            return List.of("upstream spec is required");
        }
        try {
            return validator.validate(candidate(spec), policy);
        } catch (IllegalArgumentException failure) {
            return List.of(failure.getMessage());
        }
    }

    private static UpstreamCandidate candidate(Map<String, Object> spec) {
        return new UpstreamCandidate(
                text(spec, "name"),
                text(spec, "scheme"),
                text(spec, "host"),
                longValue(spec, "timeoutMillis", DEFAULT_TIMEOUT_MILLIS),
                intValue(spec, "maxConcurrency", DEFAULT_MAX_CONCURRENCY),
                headers(spec.get("forwardedHeaders")));
    }

    private static String text(Map<String, Object> spec, String key) {
        Object value = spec.get(key);
        return value instanceof String string ? string : null;
    }

    private static long longValue(Map<String, Object> spec, String key, long fallback) {
        Object value = spec.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException(key + " must be a number");
    }

    private static int intValue(Map<String, Object> spec, String key, int fallback) {
        Object value = spec.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(key + " must be a number");
    }

    private static java.util.Set<String> headers(Object value) {
        if (value == null) {
            return java.util.Set.of();
        }
        if (!(value instanceof Collection<?> items)) {
            throw new IllegalArgumentException("forwardedHeaders must be a list");
        }
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String header)) {
                throw new IllegalArgumentException("forwardedHeaders must contain strings");
            }
            out.add(header.toLowerCase(Locale.ROOT));
        }
        return java.util.Set.copyOf(out);
    }
}
