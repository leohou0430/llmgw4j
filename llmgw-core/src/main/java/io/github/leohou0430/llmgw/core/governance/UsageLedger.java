package io.github.leohou0430.llmgw.core.governance;

import java.time.Instant;
import java.util.Map;

public interface UsageLedger {

    /**
     * @param reservedTokens tokens held at admission; released by this record
     *                       so in-flight quota can never leak on abort
     */
    record UsageRecord(String keyId, String model, int promptTokens, int completionTokens,
                       long latencyMs, boolean success, Instant at, int reservedTokens) {
    }

    record UsageSummary(long requests, long promptTokens, long completionTokens, long errors) {
    }

    void record(UsageRecord record);

    Map<String, UsageSummary> snapshot();

    default long used(String keyId) {
        UsageSummary summary = snapshot().get(keyId);
        return summary == null ? 0 : summary.promptTokens() + summary.completionTokens();
    }

    /**
     * Atomically reserve {@code tokens} against {@code quota}, INCLUDING all
     * in-flight requests. A plain {@code used() + estimate > quota} check is a
     * read-modify-write race: N concurrent requests all read used=0 and pass,
     * so the quota is oversold by N times. The reservation is released by the
     * matching {@link #record(UsageRecord)} call, whether the request succeeded
     * or failed.
     */
    boolean tryReserve(String keyId, long tokens, long quota);

    void load(Map<String, UsageSummary> data);
}
