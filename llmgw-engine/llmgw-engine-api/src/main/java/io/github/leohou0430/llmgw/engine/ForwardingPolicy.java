package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.UpstreamStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The ONE owner of attempt-ordering policy for both data planes: same-route
 * retries with exponential backoff, then the ordered fallback chain. The
 * engines keep only the execution mechanics (Reactor chaining vs a blocking
 * loop) and the first-chunk guard; WHICH attempt runs next, how long to wait,
 * and which failures deserve another try are decided here.
 *
 * <p>Deep module rationale: before v13 the fallback loop was duplicated in the
 * two mirrored controllers, which is exactly why same-route retry could not be
 * added -- there was no single place to put it. Delete this class and the
 * attempt rules reappear in both controllers, twice as complex.
 *
 * <p>Unknown outcomes stop the plan: a lost response is not proof that a paid
 * request was never accepted. Only proven non-delivery or explicit rejection
 * may advance to another attempt.
 */
public final class ForwardingPolicy {

    /** One executable attempt. {@code delayBeforeMs > 0} only for same-route retries. */
    public record Attempt(Route route, int ordinal, long delayBeforeMs) {
    }

    /**
     * @param attempts how many times ONE route may be tried before moving to
     *                 the fallback chain (1 = retry disabled, v12 semantics)
     */
    public record RetryConfig(int attempts, int backoffBaseMs, int backoffMaxMs) {

        public static RetryConfig off() {
            return new RetryConfig(1, 0, 0);
        }

        public RetryConfig {
            if (attempts < 1) {
                attempts = 1;
            }
        }
    }

    private ForwardingPolicy() {
    }

    /**
     * Primary first, then configured fallbacks, each retried up to
     * {@code retry.attempts} times. Backoff delays are drawn at plan time
     * (exponential, full jitter) -- one draw per request per retry attempt.
     * PERF: the overwhelmingly common shape (single route, no retry, no
     * fallback) returns a one-element list without touching the fallback
     * resolution at all.
     */
    public static List<Attempt> plan(Router router, Route primary, RetryConfig retry) {
        if (primary == null) {
            return List.of();
        }
        if (retry.attempts() == 1) {
            List<Route> fallbacks = router.fallbacks(primary);
            if (fallbacks.isEmpty()) {
                return List.of(new Attempt(primary, 1, 0));
            }
            List<Attempt> plan = new ArrayList<>(1 + fallbacks.size());
            plan.add(new Attempt(primary, 1, 0));
            for (Route fallback : fallbacks) {
                plan.add(new Attempt(fallback, 1, 0));
            }
            return plan;
        }
        List<Attempt> plan = new ArrayList<>();
        appendRoute(plan, primary, retry);
        for (Route fallback : router.fallbacks(primary)) {
            appendRoute(plan, fallback, retry);
        }
        return plan;
    }

    private static void appendRoute(List<Attempt> plan, Route route, RetryConfig retry) {
        for (int ordinal = 1; ordinal <= retry.attempts(); ordinal++) {
            plan.add(new Attempt(route, ordinal, ordinal == 1 ? 0 : backoffDelayMs(retry, ordinal)));
        }
    }

    /**
     * Exponential backoff with full jitter: a uniform draw from
     * [50%, 100%] of {@code min(base * 2^(ordinal-1), max)}.
     */
    static long backoffDelayMs(RetryConfig retry, int ordinal) {
        long base = Math.max(0, retry.backoffBaseMs());
        long max = Math.max(base, retry.backoffMaxMs());
        long ceiling = Math.min(max, base * (1L << Math.min(ordinal - 1, 16)));
        if (ceiling <= 0) {
            return 0;
        }
        long lower = ceiling / 2;
        return lower + (long) (ThreadLocalRandom.current().nextDouble() * (ceiling - lower));
    }

    /**
     * B9: the delay before the attempt at {@code next}. For SAME-ROUTE retries
     * the upstream's own Retry-After wins when it exceeds the configured
     * backoff (the upstream told us when it will accept work again); a jump to
     * a different route keeps the planned delay (the Retry-After describes the
     * failed upstream, not the next one).
     */
    public static long effectiveDelayMs(List<Attempt> plan, int from, int next, Throwable error) {
        if (next < 0 || next >= plan.size()) {
            return 0;
        }
        long planned = plan.get(next).delayBeforeMs();
        if (next < plan.size()
                && plan.get(next).route().id().equals(plan.get(from).route().id())
                && error instanceof io.github.leohou0430.llmgw.core.upstream.UpstreamStatusException status
                && status.retryAfterMs() > 0) {
            return Math.max(planned, status.retryAfterMs());
        }
        return planned;
    }

    /**
     * Index of the next viable attempt after {@code from} failed with
     * {@code error}, or -1 when the chain is exhausted. Retryable errors
     * advance to the very next attempt (which may retry the same route);
     * an open circuit or explicit context rejection may skip to a fallback.
     * All other errors stop because the upstream outcome may be unknown.
     */
    public static int nextViable(List<Attempt> plan, int from, Throwable error) {
        int next = from + 1;
        if (next >= plan.size()) {
            return -1;
        }
        if (retryable(error)) {
            return next;
        }
        if (!(error instanceof CircuitOpenException)
                && !(error instanceof UpstreamStatusException status
                    && status.status() >= 400 && status.status() < 500 && isContextOverflow(status))) {
            return -1;
        }
        String failedRoute = plan.get(from).route().id();
        while (next < plan.size() && plan.get(next).route().id().equals(failedRoute)) {
            next++;
        }
        return next < plan.size() ? next : -1;
    }

    /**
     * B10: context-overflow recognition. A 4xx whose body names a context
     * limit is NOT a caller bug — the model cannot fit the prompt, so the
     * fallback chain SHOULD advance to a route with a larger window (it does:
     * overflow errors are classified non-retryable, which skips same-route
     * retries and jumps straight to fallbacks). The marker also feeds the
     * request log so operators can size model catalogs.
     */
    public static boolean isContextOverflow(Throwable error) {
        String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("context_length")
                || message.contains("context length")
                || message.contains("context window")
                || message.contains("maximum context")
                || message.contains("too many tokens")
                || message.contains("input length exceeds");
    }

    public static boolean retryable(Throwable error) {
        if (error instanceof CircuitOpenException) {
            return false;
        }
        if (error instanceof UpstreamStatusException status) {
            return status.status() == 429;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
