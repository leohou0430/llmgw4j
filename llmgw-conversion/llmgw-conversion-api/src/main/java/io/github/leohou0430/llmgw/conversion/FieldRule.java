package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/**
 * 单个入口字段的准入判定输入，分类表由各入口 codec 提供。
 * {@code degradationId} 仅 DEGRADABLE 必填，且必须是 {@link DegradationGrant#KNOWN_IDS} 中的矩阵 id（§11）；
 * {@code reason} 仅 REJECT 必填。拼错的降级 id 在构造期即失败，不留到运行期报"未授权"的误导错误。
 */
public record FieldRule(String fieldPath, Classification classification, String degradationId, String reason) {

    public FieldRule {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("fieldPath must not be blank");
        }
        Objects.requireNonNull(classification, "classification");
        if (classification == Classification.DEGRADABLE) {
            if (degradationId == null || degradationId.isBlank()) {
                throw new IllegalArgumentException("DEGRADABLE requires degradationId: " + fieldPath);
            }
            if (!DegradationGrant.KNOWN_IDS.contains(degradationId)) {
                throw new IllegalArgumentException("DEGRADABLE degradationId must be one of DegradationGrant.KNOWN_IDS: "
                        + degradationId + " at " + fieldPath);
            }
        }
        if (classification == Classification.REJECT && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("REJECT requires reason: " + fieldPath);
        }
    }
}