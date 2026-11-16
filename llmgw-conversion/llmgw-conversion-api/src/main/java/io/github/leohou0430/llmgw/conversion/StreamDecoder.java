package io.github.leohou0430.llmgw.conversion;

import java.util.List;

/**
 * 每请求一个有状态实例，消费上游 chat.completion.chunk 的 JSON 载荷
 * （不含 SSE "data:" 前缀与 [DONE]，行框架归 transcoder）。
 * 正常终态发恰一次 {@link TerminalEvent}；异常终止（aborted/insufficient_system_resource）
 * 或非法 chunk 置 {@link #failed()} + {@link #failureReason()}，绝不发成功终态。
 * 实现不得让原始 RuntimeException（IllegalArgumentException/JsonNodeException 等）逃逸出
 * {@link #onChunk(String)}：非法载荷一律归一为失败状态（upstream_error 语义）。
 * 本 SPI 无"流结束"信号：上游截断（连接结束仍 {@code terminalSeen()==false}）由 transcoder/transport
 * 判定为不完整并终止，不得据此伪造成功终态。
 */
public interface StreamDecoder {

    /** 消费一个上游 chunk，返回该 chunk 分解出的语义事件（可能为空）。 */
    List<SemanticEvent> onChunk(String chunkJson);

    /** 见到 finish_reason（正常或异常）后为 true。 */
    boolean terminalSeen();

    boolean failed();

    /** 失败原因；未失败时为 null。 */
    String failureReason();
}