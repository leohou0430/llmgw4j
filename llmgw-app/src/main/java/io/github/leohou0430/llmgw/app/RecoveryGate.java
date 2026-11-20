package io.github.leohou0430.llmgw.app;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务9 恢复闸门：恢复（备份还原）后默认 maintenance（关闸）——数据面拒绝新 调用，直到操作者完成清单核验（备份摘要 + 缺口核对结果显式记录）并调用
 * {@link #open(reason)}。闸门状态不可回退（重开后再关闸走恢复流程而非运行期 开关）。存内存态：进程重启后由恢复流程显式设置（不持久——闸门是流程状态，
 * 不是配置）。
 */
@Component
public final class RecoveryGate {

	private final AtomicBoolean maintenance = new AtomicBoolean(false);

	private volatile String openReason = "";

	private volatile String closedReason = "";

	/** 恢复流程开始：关闸（恢复默认 maintenance）。幂等。 */
	public void close(String reason) {
		Objects.requireNonNull(reason, "reason");
		closedReason = reason;
		maintenance.set(true);
	}

	/** 开闸：仅当缺口核对结果显式提供（非空证据）才允许。 */
	public void open(String reconciliationEvidence) {
		if (reconciliationEvidence == null || reconciliationEvidence.isBlank()) {
			throw new IllegalArgumentException("gate opens only with explicit reconciliation evidence");
		}
		openReason = reconciliationEvidence;
		maintenance.set(false);
	}

	/** 是否处于 maintenance（关闸）。 */
	public boolean isMaintenance() {
		return maintenance.get();
	}

	public String openReason() {
		return openReason;
	}

	public String closedReason() {
		return closedReason;
	}

}
