package io.github.leohou0430.llmgw.conversion;

import io.github.leohou0430.llmgw.core.extension.ExtensionPoint;

/**
 * 上游编解码 SPI：语义合同 ↔ 上游线协议。命名发现（如 {@code deepseek-chat}），无默认实现。 实现必须无状态可共享；每请求的流式状态归
 * {@link StreamDecoder} 实例。
 */
@ExtensionPoint
public interface UpstreamCodec {

	/** 语义请求 → 上游请求载荷；targetModel 为路由改写后的上游模型 id。 */
	UpstreamPayload encodeRequest(SemanticRequest req, String targetModel);

	/**
	 * 上游非流式响应 → 语义响应；异常终止/非法 JSON/不完整响应（缺 choices、message、finish_reason） 抛
	 * {@link UpstreamException}，不伪造成功。
	 */
	SemanticResponse decodeResponse(String upstreamJson);

	/** 每请求一个全新的有状态解码器实例。 */
	StreamDecoder newStreamDecoder();

}