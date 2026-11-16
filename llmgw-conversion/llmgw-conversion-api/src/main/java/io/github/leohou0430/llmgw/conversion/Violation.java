package io.github.leohou0430.llmgw.conversion;

/** 一条能力违规：稳定码 + 字段路径 + 原因（矩阵 §10.1）；三者都必须有值，错误消息里不允许出现 null。 */
public record Violation(String code, String fieldPath, String reason) {

    public Violation {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("fieldPath must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}