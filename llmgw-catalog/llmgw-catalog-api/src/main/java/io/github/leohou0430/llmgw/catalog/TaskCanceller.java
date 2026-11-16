package io.github.leohou0430.llmgw.catalog;

/**
 * Forwards a cancel to the task's fixed upstream source. The catalog owns only the
 * directory; it does not run an executor, queue or recovery scheduler. A cancel is
 * reflected as "cancelled" only after the upstream confirms.
 */
@FunctionalInterface
public interface TaskCanceller {

    void cancel(String sourceRoute, String upstreamId);
}