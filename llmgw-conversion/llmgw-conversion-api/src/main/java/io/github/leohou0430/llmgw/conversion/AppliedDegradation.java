package io.github.leohou0430.llmgw.conversion;

/** 一次实际执行的降级留痕：字段路径 + 矩阵降级 id + 语义损失原因，三者都必须有值。 */
public record AppliedDegradation(String fieldPath, String degradationId, String reason) {

    public AppliedDegradation {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("fieldPath must not be blank");
        }
        if (degradationId == null || degradationId.isBlank()) {
            throw new IllegalArgumentException("degradationId must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}