package io.github.leohou0430.llmgw.conversion;

import java.util.Objects;

/** 上游请求载荷：rawJson 为请求体，path 为上游端点路径（DeepSeek Chat 固定 /chat/completions）。 */
public record UpstreamPayload(String rawJson, String path) {
    public UpstreamPayload {
        Objects.requireNonNull(rawJson, "rawJson");
        Objects.requireNonNull(path, "path");
        if (rawJson.isBlank()) {
            throw new IllegalArgumentException("rawJson must not be blank");
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }
}