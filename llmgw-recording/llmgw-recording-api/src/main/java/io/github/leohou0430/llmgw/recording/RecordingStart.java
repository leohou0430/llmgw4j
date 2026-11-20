package io.github.leohou0430.llmgw.recording;

import java.util.Objects;

/**
 * 打开一次调用的记录上下文（任务5）：callId 与计费侧 {@code billingCallId} 同源（引擎 requestId），正文按租户/项目隔离。
 *
 * @param callId 调用标识（关联账单与审计）
 * @param tenantId 租户（真实 scope）
 * @param projectId 项目
 * @param kind 协议种类（wire name）
 */
public record RecordingStart(String callId, String tenantId, String projectId, String kind) {
	public RecordingStart {
		Objects.requireNonNull(callId, "callId");
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(kind, "kind");
	}
}
