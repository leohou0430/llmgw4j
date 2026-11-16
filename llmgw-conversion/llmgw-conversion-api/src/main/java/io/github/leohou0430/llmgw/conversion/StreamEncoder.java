package io.github.leohou0430.llmgw.conversion;

import java.util.List;

/**
 * 每请求一个有状态实例：语义事件 → 入口协议 SSE 行（含 {@code "data:"} 前缀，不含结尾换行；
 * 换行与帧间隔归上层 transcoder，与 {@link StreamDecoder} 的行框架归属一致）。
 *
 * <p>正常序：首帧带 role → 增量* → 恰一次终态帧 →（include_usage 且 usage 已知）独立 usage 帧
 * → {@link #onComplete()} 的 {@code [DONE]} 一次（矩阵 §6.2）。
 * 异常：{@link #onError(String, String)} 发错误帧后终止，绝不发 {@code [DONE]}；
 * 缺终态时 {@link #onComplete()} 不得伪造成功。
 */
public interface StreamEncoder {

    /** 消费一个语义事件，返回 0..n 行入口协议 SSE；终态/错误之后到达的事件被忽略。 */
    List<String> onEvent(SemanticEvent e);

    /**
     * 上游正常结束：发一次 {@code [DONE]}（重复调用返回空）。
     * 未见终态且未出错时抛 {@link IllegalStateException}——截断由上层判定，这里不补成功终态。
     */
    List<String> onComplete();

    /** 头已发送后的错误：发一帧入口协议错误事件并终止（幂等，不发 {@code [DONE]}）。 */
    List<String> onError(String code, String message);

    /** 已发终态帧或错误帧后为 true。 */
    boolean terminalSeen();
}