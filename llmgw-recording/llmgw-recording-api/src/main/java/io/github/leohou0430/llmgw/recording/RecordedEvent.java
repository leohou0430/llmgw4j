package io.github.leohou0430.llmgw.recording;

import java.util.Objects;

/**
 * 单条记录事件（任务5）：方向 + 已消毒载荷。载荷在进入记录器前由 sanitizer 处理（认证字段/带令牌 URL/Base64 与二进制片段不落盘）。
 *
 * @param direction 事件方向
 * @param payload 已消毒的文本载荷（UTF-8）
 * @param atMs 事件时间（epoch ms）
 */
public record RecordedEvent(Direction direction, String payload, long atMs) {

	public enum Direction {

		/** 客户端请求（进入网关的正文）。 */
		REQUEST,
		/** 上游响应正文。 */
		RESPONSE,
		/** 流式中间事件（SSE 帧）。 */
		EVENT

	}

	public RecordedEvent {
		Objects.requireNonNull(direction, "direction");
		Objects.requireNonNull(payload, "payload");
		if (atMs < 0) {
			throw new IllegalArgumentException("atMs must be non-negative");
		}
	}

	public static RecordedEvent now(Direction direction, String payload) {
		return new RecordedEvent(direction, payload, System.currentTimeMillis());
	}
}
