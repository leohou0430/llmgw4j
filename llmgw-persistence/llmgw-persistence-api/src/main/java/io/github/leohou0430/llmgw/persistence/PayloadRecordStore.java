package io.github.leohou0430.llmgw.persistence;

import java.util.List;
import java.util.Optional;

/**
 * 调用正文记录存储（第十六/十七项确认）：默认保存提示词、模型输出与结构化 内容（工具调用、思考内容、流式事件）；文件/多媒体/Base64 仅记元数据引用。
 * 正文保留期限可配置（默认 7 天），到期由 {@link #purgeExpired} 清理；账务 流水与管理审计不随正文删除。
 *
 * <p>
 * 第十七项确认：正文存储不足或写入失败时，新调用被拒绝（由调用方把写入 失败映射为准入拒绝并告警），已在途调用继续转发、无法完整记录的标记缺失，
 * 费用仍正常结算或进入待对账——不通过提前删除未到期正文或静默丢弃维持准入。
 */
public interface PayloadRecordStore {

	/**
	 * 写入一条调用正文。存储不可用（磁盘满/IO 失败）时抛 {@link PayloadStoreUnavailableException}，调用方据此拒绝新调用并告警。
	 * @param expiresAtEpochMs 到期时间（保留期限由配置决定，默认 7 天）
	 * @param truncated 正文是否因超限被截断（无法完整记录的明确标记）
	 */
	void put(String id, String tenantId, String projectId, String requestBody, String responseBody, String structured,
			long createdAtEpochMs, long expiresAtEpochMs, boolean truncated);

	/** 按 id 读取（BODY_READ 授权由控制面层判定，存储层不做授权）。 */
	Optional<PayloadRecord> get(String id);

	/** 删除已到期正文；返回清理条数。账务流水与审计不在本表。 */
	int purgeExpired(long nowEpochMs);

	/** 当前正文条数（容量观测）。 */
	long count();

	/**
	 * B5 导出：按创建时间倒序返回最近正文（上限 limit，不超过存储容量）。 默认空列表：存储无枚举能力时导出受限并文档标注。
	 */
	default List<PayloadRecord> listRecent(int limit) {
		return List.of();
	}

	/** 正文记录（不含凭据与认证头）。 */
	record PayloadRecord(String id, String tenantId, String projectId, String requestBody, String responseBody,
			String structured, long createdAtEpochMs, long expiresAtEpochMs, boolean truncated) {
	}

}
