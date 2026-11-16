package io.github.leohou0430.llmgw.catalog;

/**
 * One page request: a bounded page size and an optional {@code typeFilter} (null =
 * no filter). The limit is clamped to a hard maximum so a caller cannot force an
 * unbounded scan.
 */
public record PageRequest(int limit, String typeFilter) {
    public static final int MAX_LIMIT = 50;

    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ": " + limit);
        }
    }

    public static PageRequest of(int limit) {
        return new PageRequest(limit, null);
    }

    public static PageRequest of(int limit, String typeFilter) {
        return new PageRequest(limit, typeFilter);
    }
}