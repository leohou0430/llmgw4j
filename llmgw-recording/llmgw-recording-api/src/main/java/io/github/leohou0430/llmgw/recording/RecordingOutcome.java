package io.github.leohou0430.llmgw.recording;

/**
 * 记录终态（任务5）：COMPLETE 仅当全部事件落盘；队列溢出、写失败、未终态 关闭都落 INCOMPLETE。终态推进幂等且不可逆——重启修复不得把 OPEN 改成
 * COMPLETE。
 */
public enum RecordingOutcome {

	/** 全部事件已持久化（显式 finish(COMPLETE) 才允许）。 */
	COMPLETE,
	/** 部分事件丢失（队列满/写失败/未完成关闭）：正文不完整，可审计不可重放。 */
	INCOMPLETE

}
