package io.github.leohou0430.llmgw.conversion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 每请求一个实例：requested 是 {@link DegradationGrant} 的授权快照，也是授权判定的唯一真相源；
 * applied 是实际执行的降级；实例之间不共享状态。
 */
public final class DegradationAudit {

    private final Set<String> requested;
    private final List<AppliedDegradation> applied = new ArrayList<>();

    public DegradationAudit(DegradationGrant grant) {
        Objects.requireNonNull(grant, "grant");
        this.requested = grant.allowed();
    }

    public Set<String> requested() {
        return requested;
    }

    public List<AppliedDegradation> applied() {
        return List.copyOf(applied);
    }

    /** 只保证留痕三字段有值且降级 id 已知；是否获得授权由 {@link CapabilityPolicy} 判定。 */
    public void recordApplied(String fieldPath, String degradationId, String reason) {
        AppliedDegradation entry = new AppliedDegradation(fieldPath, degradationId, reason);
        if (!DegradationGrant.KNOWN_IDS.contains(degradationId)) {
            throw new IllegalArgumentException("degradationId must be one of DegradationGrant.KNOWN_IDS: "
                    + degradationId + " at " + fieldPath);
        }
        applied.add(entry);
    }
}