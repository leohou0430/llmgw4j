package io.github.leohou0430.llmgw.persistence;

/**
 * 正文存储不可用（磁盘满/IO 失败/写入超限）。按第十七项确认，调用方据此 拒绝新调用并告警；在途调用继续转发，不做提前删除或静默丢弃。
 */
public class PayloadStoreUnavailableException extends RuntimeException {

	public PayloadStoreUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
