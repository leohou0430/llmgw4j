package io.github.leohou0430.llmgw.core.upstream;

/**
 * A v13 three-tier timeout fired locally by the provider watchdog: the
 * upstream connected but never delivered headers (first-byte) or went silent
 * mid-stream past {@code idle-read-timeout-ms}. Treated as retryable by
 * {@code ForwardingPolicy}: the default classification for anything that is
 * not a 4xx status is "transient".
 */
public class UpstreamTimeoutException extends IllegalStateException {

    public UpstreamTimeoutException(String message) {
        super(message);
    }
}
