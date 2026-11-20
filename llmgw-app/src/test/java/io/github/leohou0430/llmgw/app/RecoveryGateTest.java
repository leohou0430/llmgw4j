package io.github.leohou0430.llmgw.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务9 恢复闸门合同：恢复默认关闸；无缺口核对证据不开闸；开闸需显式证据。 */
class RecoveryGateTest {

	@Test
	void closeThenOpenRequiresExplicitEvidence() {
		var gate = new RecoveryGate();
		assertFalse(gate.isMaintenance(), "正常运行不关闸");
		gate.close("restore in progress");
		assertTrue(gate.isMaintenance(), "恢复默认 maintenance");
		assertThrows(IllegalArgumentException.class, () -> gate.open(""), "空证据不开闸");
		assertThrows(IllegalArgumentException.class, () -> gate.open(null));
		gate.open("reconciliation evidence: watermark digest 9f2c... matches; 0 pending gaps");
		assertFalse(gate.isMaintenance(), "缺口核对证据显式记录后开闸");
		assertEquals("restore in progress", gate.closedReason());
		assertTrue(gate.openReason().contains("watermark digest"));
	}

	@Test
	void reClosingAfterOpenGoesThroughTheRestoreFlowAgain() {
		var gate = new RecoveryGate();
		gate.close("restore 1");
		gate.open("evidence-1");
		gate.close("restore 2");
		assertTrue(gate.isMaintenance());
		gate.open("evidence-2");
		assertFalse(gate.isMaintenance());
	}

}
