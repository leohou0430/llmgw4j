package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/**
 * 无状态判定器：分类表由 codec 提供，授权与留痕由每请求的 {@link DegradationAudit} 承载。
 * 授权判定的唯一真相源是 {@code audit.requested()}（授权头快照），不再另收 grant，避免两处授权不一致。
 */
public final class CapabilityPolicy {

    private CapabilityPolicy() {
    }

    public static Decision evaluate(FieldRule rule, DegradationAudit audit) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(audit, "audit");
        return switch (rule.classification()) {
            case EQUIVALENT -> new Decision.Accept(rule.fieldPath());
            case IGNORABLE -> new Decision.Ignore(rule.fieldPath());
            case REJECT -> throw new CapabilityException(
                    CapabilityException.NOT_SUPPORTED, 400, rule.fieldPath(), rule.reason());
            case DEGRADABLE -> {
                String id = rule.degradationId();
                if (!audit.requested().contains(id)) {
                    throw new CapabilityException(CapabilityException.DEGRADATION_NOT_AUTHORIZED, 400,
                            rule.fieldPath(), "需授权降级 " + id);
                }
                // 留痕三字段必须有值：codec 未给 reason 时用降级 id 兜底，不写入 null/blank。
                String reason = rule.reason() == null || rule.reason().isBlank()
                        ? "已授权降级 " + id
                        : rule.reason();
                audit.recordApplied(rule.fieldPath(), id, reason);
                yield new Decision.Degrade(rule.fieldPath(), id, reason);
            }
        };
    }

    /** 未知字段与"不可表达"是不同稳定码；总是抛出，返回值只为让 codec 能在表达式位置调用。 */
    public static Decision rejectUnknown(String fieldPath) {
        throw new CapabilityException(CapabilityException.UNKNOWN_FIELD, 400, fieldPath, "未知请求字段: " + fieldPath);
    }
}