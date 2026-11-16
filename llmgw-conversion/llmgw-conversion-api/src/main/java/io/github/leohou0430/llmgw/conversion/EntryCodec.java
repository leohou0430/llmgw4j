package io.github.leohou0430.llmgw.conversion;

import io.github.leohou0430.llmgw.core.extension.ExtensionPoint;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

/**
 * 入口编解码 SPI：调用方线协议 ↔ 语义合同。命名发现（如 {@code chat}），无默认实现。
 * 实现必须无状态可共享；每请求状态归 {@link CapabilityContext#audit()} 与 {@link StreamEncoder} 实例。
 * <p>{@code ctx} 携带 {@code projectId} 与 {@link CapabilityContext#envelope()}，供续轮信封的铸造/校验
 * （矩阵 §7）；encode 路径 {@code ctx} 必需非 null，不提供 null-ctx 便捷默认。
 */
@ExtensionPoint
public interface EntryCodec {

    ProtocolKind kind();

    /** 入口请求 JSON → 语义请求；字段分类与能力守卫违规抛 {@link CapabilityException}（矩阵 §5/§10.1）。 */
    SemanticRequest decode(String rawJson, CapabilityContext ctx);

    /**
     * 语义响应 → 入口非流式响应 JSON。
     * {@code model} 是调用方请求的模型 id：语义合同（T1.2）不承载它，由调用方传入；
     * null＝未知，省略该字段而不是伪造上游模型。
     * {@code ctx} 必需非 null：其 {@code projectId}/{@code envelope} 用于续轮信封铸造（矩阵 §7.2/§7.3）。
     */
    String encodeResponse(SemanticResponse resp, String model, CapabilityContext ctx);

    /**
     * 每请求一个全新的有状态流式编码器。
     * {@code includeUsage} 还原调用方 {@code stream_options.include_usage}：true 时终态后补独立
     * usage-only chunk（矩阵 §6.2）；{@code model} 同 {@link #encodeResponse(SemanticResponse, String, CapabilityContext)}。
     * {@code ctx} 必需非 null：其 {@code projectId}/{@code envelope} 供流式终态铸造续轮信封。
     */
    StreamEncoder newStreamEncoder(String model, boolean includeUsage, CapabilityContext ctx);

    /** 头未发送：入口协议错误 JSON（矩阵 §10.2）。 */
    String errorJson(CapabilityException e);

    /** 头已发送：入口协议 SSE 错误事件行；调用方随后必须终止，不得再发成功终态。 */
    String errorEvent(CapabilityException e);
}