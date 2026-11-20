package io.github.leohou0430.llmgw.billing;

/**
 * 计量单位（任务4八类协议）：计价公式按单位选择。FREE 表示网关自身服务 （文件/批任务管理等不经过上游生成），内置免费可调用。
 */
public enum Unit {

	/** token 对（CHAT/RESPONSES/MESSAGES/EMBEDDINGS/RERANK）。 */
	TOKENS,
	/** 图片张数（IMAGES：n 参数）。 */
	IMAGES,
	/** 异步任务数（VIDEOS：创建一次计费，轮询不重复）。 */
	TASKS,
	/** 音频时长（SPEECH/TRANSCRIPTIONS，秒）。 */
	SECONDS,
	/** 字节（文件存储类）。 */
	BYTES,
	/** 网关内置免费（FILES/BATCHES 管理面）。 */
	FREE

}
