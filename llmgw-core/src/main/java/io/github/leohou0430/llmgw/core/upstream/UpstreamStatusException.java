package io.github.leohou0430.llmgw.core.upstream;

/**
 * A non-2xx upstream response. Extends IllegalStateException so every existing
 * catch site (and the reactive fallback chain, which only sees RuntimeExceptions)
 * keeps working; the status code rides along for retry classification instead
 * of being parsed back out of the message string.
 * <p>The message format is CONTRACT: the 67-assertion stream/error suite asserts
 * its exact shape ("upstream status N: detail"), so it must not change.
 */
public class UpstreamStatusException extends IllegalStateException {

    private final int status;
    /** B9: upstream-declared Retry-After (ms); 0 when absent/unparseable. */
    private final long retryAfterMs;

    public UpstreamStatusException(int status, String body) {
        this(status, body, 0);
    }

    public UpstreamStatusException(int status, String body, long retryAfterMs) {
        super(message(status, body));
        this.status = status;
        this.retryAfterMs = Math.max(0, retryAfterMs);
    }

    public int status() {
        return status;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    private static String message(int status, String body) {
        String detail = body == null ? "" : body.strip();
        if (detail.length() > 300) {
            detail = detail.substring(0, 300);
        }
        return "upstream status " + status + (detail.isEmpty() ? "" : ": " + detail);
    }
}
