package io.github.leohou0430.llmgw.recording;

/**
 * 一次调用的记录句柄（任务5）：事件旁路（不阻塞数据面）、终态推进幂等且 不可逆。close 等价于未终态时的 finish(INCOMPLETE)——绝不把静默关闭记成
 * COMPLETE。
 */
public interface RecordingHandle extends AutoCloseable {

	/**
	 * 追加一条事件（非阻塞：满队列时事件丢弃且终态降级为 INCOMPLETE， 绝不反压调用线程）。
	 */
	void append(RecordedEvent event);

	/** 终态推进：幂等——第二次 finish 是 no-op。 */
	void finish(RecordingOutcome outcome);

	/** 未显式 finish 时的安全关闭 = finish(INCOMPLETE)。幂等。 */
	@Override
	void close();

}
