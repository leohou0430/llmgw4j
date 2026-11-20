package io.github.leohou0430.llmgw.recording;

/**
 * 正文记录存储（任务5）：失败显式健康闸门——写失败后新调用在提交前被拒绝 （抛 IllegalStateException），在途调用继续且终态为
 * INCOMPLETE；不静默吞 异常、不无界排队。
 */
public interface RecordingStore {

	/**
	 * 打开一次调用的记录句柄。健康闸门关闭（近期写失败）时抛 IllegalStateException：新调用提交前拒绝。
	 */
	RecordingHandle open(RecordingStart start);

	/** 健康闸门状态（管理面可见）。 */
	boolean healthy();

	/** 保留策略清理：只删过期正文，不触碰账务/审计/未知预留。 */
	int purgeExpired();

}
