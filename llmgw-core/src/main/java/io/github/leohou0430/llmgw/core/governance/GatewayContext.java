package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request accounting context. Completion tokens accumulate while SSE chunks flow.
 * <p>An upstream {@code usage} chunk is authoritative: it REPLACES the running
 * per-delta estimate instead of being added on top of it. Without that rule a
 * normal stream with N deltas plus one usage frame is billed twice.
 *
 * <p>v10 timing: the three marks below are the only per-request work added for
 * AI observability. They are plain atomic CAS/long writes (no allocation, no
 * registry lookup); all formatting, Timer recording and audit enrichment happen
 * on the governance side car after the response has been delivered.
 */
public final class GatewayContext {

    private final String keyId;
    private final ProtocolKind kind;
    private final String model;
    private final String routeId;
    private final boolean stream;
    private final String requestId;
    private final long startNanos;
    private final int promptTokens;
    private final int units;
    private final AtomicInteger estimatedCompletion = new AtomicInteger();
    /** -1 = the upstream never reported usage; otherwise the authoritative value. */
    private final AtomicInteger reportedCompletion = new AtomicInteger(-1);
    private volatile boolean cached;
    /** v13 $ admission reservation (micro-USD); 0 = no budget applied. */
    private volatile long budgetReservedMicroUsd;
    /** B4: ownership group carried from the key ("" = ungrouped). */
    private volatile String group = "";
    /** B4: group-level $ reservation (micro-USD); 0 = no group budget applied. */
    private volatile long groupBudgetReservedMicroUsd;

    /** 0 = not started. First CAS wins so a fallback attempt does not move T0. */
    private final AtomicLong upstreamStartNanos = new AtomicLong();
    /** 0 = no chunk yet. Time of the FIRST upstream byte delivered downstream. */
    private final AtomicLong firstChunkNanos = new AtomicLong();
    /** 0 = not finished. Only set on the non-streaming path. */
    private final AtomicLong upstreamEndNanos = new AtomicLong();

    public GatewayContext(String keyId, String model, String routeId, boolean stream, int promptTokens) {
        this(keyId, ProtocolKind.CHAT, model, routeId, stream, promptTokens, 1, "", System.nanoTime());
    }

    public GatewayContext(String keyId, String model, String routeId, boolean stream,
                          int promptTokens, String requestId) {
        this(keyId, ProtocolKind.CHAT, model, routeId, stream, promptTokens, 1,
                requestId, System.nanoTime());
    }

    /** Start the clock at request arrival, not after admission (v10). */
    public GatewayContext(String keyId, String model, String routeId, boolean stream,
                          int promptTokens, String requestId, long arrivedNanos) {
        this(keyId, ProtocolKind.CHAT, model, routeId, stream, promptTokens, 1,
                requestId, arrivedNanos);
    }

    public GatewayContext(String keyId, ProtocolKind kind, String model, String routeId,
                          boolean stream, int promptTokens, int units,
                          String requestId, long arrivedNanos) {
        this.keyId = keyId;
        this.kind = kind;
        this.model = model;
        this.routeId = routeId;
        this.stream = stream;
        this.requestId = requestId == null ? "" : requestId;
        this.promptTokens = promptTokens;
        this.units = Math.max(1, units);
        this.startNanos = arrivedNanos;
    }

    /** Called immediately before an upstream attempt is issued. */
    public void markUpstreamStart() {
        upstreamStartNanos.compareAndSet(0, System.nanoTime());
    }

    /** Called on the first non-blank SSE line; TTFT is measured from arrival. */
    public void markFirstChunk() {
        firstChunkNanos.compareAndSet(0, System.nanoTime());
    }

    /** Called once a non-streaming upstream body has been fully received. */
    public void markUpstreamEnd() {
        upstreamEndNanos.set(System.nanoTime());
    }

    /** Gateway work before the response is committed (headers must be set by then). */
    public long preflightNanos() {
        long upstream = upstreamStartNanos.get();
        return Math.max(0, (upstream == 0 ? System.nanoTime() : upstream) - startNanos);
    }

    /** Time to first token, or -1 when no chunk arrived (failure / non-stream). */
    public long ttftNanos() {
        long first = firstChunkNanos.get();
        return first == 0 ? -1 : Math.max(0, first - startNanos);
    }

    /**
     * Upstream time: full body for non-streaming, time-to-first-byte for SSE.
     * Returns -1 when no upstream attempt was made (cache hit / admission deny).
     */
    public long upstreamNanos() {
        long start = upstreamStartNanos.get();
        if (start == 0) {
            return -1;
        }
        long first = firstChunkNanos.get();
        long end = upstreamEndNanos.get();
        long stop = first != 0 ? first : (end != 0 ? end : System.nanoTime());
        return Math.max(0, stop - start);
    }

    /** Total latency minus upstream time: what the gateway itself contributed. */
    public long gatewayOverheadNanos() {
        long total = Math.max(0, System.nanoTime() - startNanos);
        long upstream = upstreamNanos();
        return upstream < 0 ? total : Math.max(0, total - upstream);
    }

    /** Allocation-light 0.1ms formatting (String.format costs microseconds). */
    public static String formatMillis(long nanos) {
        if (nanos <= 0) {
            return "0.0";
        }
        long tenths = (nanos + 50_000L) / 100_000L;
        return (tenths / 10) + "." + (tenths % 10);
    }

    /** One decimal place in milliseconds, for audit events. */
    public static double millis(long nanos) {
        return nanos <= 0 ? 0.0 : Math.round(nanos / 100_000.0) / 10.0;
    }

    /** Accumulate an estimate from a delta's text (used only when usage is absent). */
    public void addDeltaTokens(String delta) {
        estimatedCompletion.addAndGet(ProtocolCodec.estimateTokens(delta));
    }

    /** Authoritative upstream usage; overrides any estimate accumulated so far. */
    public void setCompletionTokens(int tokens) {
        reportedCompletion.set(Math.max(0, tokens));
    }

    /** Exact value for a non-streaming response (or a stream with no usage chunk). */
    public void setCompletionTokensIfAbsent(int tokens) {
        reportedCompletion.compareAndSet(-1, Math.max(0, tokens));
    }

    public void markCached() { this.cached = true; }

    /** $ reservation held at admission; settled by the budget ledger at finish. */
    public void setBudgetReservedMicroUsd(long microUsd) { this.budgetReservedMicroUsd = microUsd; }
    public long budgetReservedMicroUsd() { return budgetReservedMicroUsd; }

    public void setGroup(String group) { this.group = group == null ? "" : group; }
    public String group() { return group; }
    public void setGroupBudgetReservedMicroUsd(long microUsd) {
        this.groupBudgetReservedMicroUsd = microUsd;
    }
    public long groupBudgetReservedMicroUsd() { return groupBudgetReservedMicroUsd; }

    public String keyId() { return keyId; }
    public ProtocolKind kind() { return kind; }
    public String model() { return model; }
    public String routeId() { return routeId; }
    public boolean stream() { return stream; }
    /** Request units (images: n; video create: 1 task; text protocols: 1). */
    public int units() { return units; }
    /** Correlation id echoed to the client and written into audit events. */
    public String requestId() { return requestId; }
    public int promptTokens() { return promptTokens; }
    /** The exact amount charged to the token bucket / reserved at admission. */
    public int reservedTokens() { return Math.max(1, promptTokens); }
    public int completionTokens() {
        int reported = reportedCompletion.get();
        return reported >= 0 ? reported : estimatedCompletion.get();
    }
    public boolean cached() { return cached; }
    public long latencyMs() { return (System.nanoTime() - startNanos) / 1_000_000; }
}
