package io.github.leohou0430.llmgw.conversion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityPolicyTest {

	private static final String BUDGET_TO_EFFORT = "thinking.budget_to_effort";

	private static final String FORCE_TO_AUTO = "tool_choice.force_to_auto";

	private static final String HEADER = "X-LLMGW-Allow-Degradation";

	private static FieldRule degradable(String fieldPath, String degradationId, String reason) {
		return new FieldRule(fieldPath, Classification.DEGRADABLE, degradationId, reason);
	}

	private static Violation violation() {
		return new Violation("capability_invalid_value", "seed", "越界");
	}

	@Test
	void equivalentFieldIsAcceptedForPassthrough() {
		FieldRule rule = new FieldRule("messages[0].content", Classification.EQUIVALENT, null, null);
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());

		Decision decision = CapabilityPolicy.evaluate(rule, audit);

		Decision.Accept accept = assertInstanceOf(Decision.Accept.class, decision);
		assertEquals("messages[0].content", accept.fieldPath());
		assertTrue(audit.applied().isEmpty());
	}

	@Test
	void ignorableFieldIsDroppedNotPassedThrough() {
		FieldRule rule = new FieldRule("stream_options.include_usage", Classification.IGNORABLE, null, null);
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());

		Decision decision = CapabilityPolicy.evaluate(rule, audit);

		Decision.Ignore ignore = assertInstanceOf(Decision.Ignore.class, decision);
		assertEquals("stream_options.include_usage", ignore.fieldPath());
		assertTrue(audit.applied().isEmpty());
	}

	@Test
	void rejectFieldFailsWithNotSupportedPathAndReason() {
		String reason = "上游无会话状态，previous_response_id 不可表达";
		FieldRule rule = new FieldRule("previous_response_id", Classification.REJECT, null, reason);
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());

		CapabilityException error = assertThrows(CapabilityException.class,
				() -> CapabilityPolicy.evaluate(rule, audit));

		assertEquals(400, error.status());
		assertEquals(List.of(new Violation("capability_not_supported", "previous_response_id", reason)),
				error.violations());
		assertTrue(audit.applied().isEmpty());
	}

	@Test
	void rejectStaysRejectedEvenWhenEveryDegradationIsAuthorized() {
		DegradationAudit audit = new DegradationAudit(new DegradationGrant(DegradationGrant.KNOWN_IDS));
		FieldRule rule = new FieldRule("previous_response_id", Classification.REJECT, null, "上游无会话状态");

		CapabilityException error = assertThrows(CapabilityException.class,
				() -> CapabilityPolicy.evaluate(rule, audit));

		assertEquals(400, error.status());
		assertEquals("capability_not_supported", error.violations().get(0).code());
		assertEquals(DegradationGrant.KNOWN_IDS, audit.requested());
		assertTrue(audit.applied().isEmpty());
	}

	@Test
	void degradableWithoutGrantIsRejectedAndNotAudited() {
		FieldRule rule = degradable("thinking.budget_tokens", BUDGET_TO_EFFORT, "精确预算→粗 effort 档");
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());

		CapabilityException error = assertThrows(CapabilityException.class,
				() -> CapabilityPolicy.evaluate(rule, audit));

		assertEquals(400, error.status());
		Violation violation = error.violations().get(0);
		assertEquals("capability_degradation_not_authorized", violation.code());
		assertEquals("thinking.budget_tokens", violation.fieldPath());
		assertTrue(violation.reason().contains(BUDGET_TO_EFFORT), violation.reason());
		assertTrue(audit.applied().isEmpty());
	}

	@Test
	void grantOfAnotherDegradationDoesNotAuthorizeThisOne() {
		FieldRule rule = degradable("thinking.budget_tokens", BUDGET_TO_EFFORT, "精确预算→粗 effort 档");
		DegradationGrant grant = DegradationGrant.parse("thinking.display_raw");
		DegradationAudit audit = new DegradationAudit(grant);

		CapabilityException error = assertThrows(CapabilityException.class,
				() -> CapabilityPolicy.evaluate(rule, audit));

		assertEquals("capability_degradation_not_authorized", error.violations().get(0).code());
		assertTrue(audit.applied().isEmpty());
	}

	@Test
	void authorizedDegradationDegradesAndLeavesAuditTrail() {
		String reason = "精确预算→粗 effort 档";
		FieldRule rule = degradable("thinking.budget_tokens", BUDGET_TO_EFFORT, reason);
		DegradationGrant grant = DegradationGrant.parse(BUDGET_TO_EFFORT + "," + FORCE_TO_AUTO);
		DegradationAudit audit = new DegradationAudit(grant);

		Decision decision = CapabilityPolicy.evaluate(rule, audit);

		Decision.Degrade degrade = assertInstanceOf(Decision.Degrade.class, decision);
		assertEquals("thinking.budget_tokens", degrade.fieldPath());
		assertEquals(BUDGET_TO_EFFORT, degrade.degradationId());
		assertEquals(reason, degrade.reason());
		assertEquals(List.of(new AppliedDegradation("thinking.budget_tokens", BUDGET_TO_EFFORT, reason)),
				audit.applied());
		assertEquals(Set.of(BUDGET_TO_EFFORT, FORCE_TO_AUTO), audit.requested());
	}

	@Test
	void degradableWithoutReasonStillLeavesNonBlankAuditTrail() {
		for (String reason : new String[] { null, "   " }) {
			DegradationGrant grant = DegradationGrant.parse(BUDGET_TO_EFFORT);
			DegradationAudit audit = new DegradationAudit(grant);

			Decision decision = CapabilityPolicy
				.evaluate(degradable("thinking.budget_tokens", BUDGET_TO_EFFORT, reason), audit);

			Decision.Degrade degrade = assertInstanceOf(Decision.Degrade.class, decision);
			assertNotNull(degrade.reason(), "reason=" + reason);
			assertFalse(degrade.reason().isBlank(), "reason=" + reason);
			assertTrue(degrade.reason().contains(BUDGET_TO_EFFORT), degrade.reason());
			assertEquals(degrade.reason(), audit.applied().get(0).reason());
		}
	}

	@Test
	void appliedDegradationsAccumulateInEvaluationOrder() {
		DegradationGrant grant = DegradationGrant.parse(BUDGET_TO_EFFORT + "," + FORCE_TO_AUTO);
		DegradationAudit audit = new DegradationAudit(grant);

		CapabilityPolicy.evaluate(degradable("thinking.budget_tokens", BUDGET_TO_EFFORT, "预算降档"), audit);
		CapabilityPolicy.evaluate(degradable("tool_choice", FORCE_TO_AUTO, "强制调用退化为 auto"), audit);

		assertEquals(List.of(new AppliedDegradation("thinking.budget_tokens", BUDGET_TO_EFFORT, "预算降档"),
				new AppliedDegradation("tool_choice", FORCE_TO_AUTO, "强制调用退化为 auto")), audit.applied());
	}

	@Test
	void evaluateRejectsNullRuleAndNullAudit() {
		FieldRule rule = new FieldRule("seed", Classification.EQUIVALENT, null, null);
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());

		assertThrows(NullPointerException.class, () -> CapabilityPolicy.evaluate(null, audit));
		assertThrows(NullPointerException.class, () -> CapabilityPolicy.evaluate(rule, null));
	}

	@Test
	void unknownFieldUsesUnknownFieldCodeNotNotSupported() {
		CapabilityException error = assertThrows(CapabilityException.class,
				() -> CapabilityPolicy.rejectUnknown("messages[0].audio"));

		assertEquals(400, error.status());
		Violation violation = error.violations().get(0);
		assertEquals("capability_unknown_field", violation.code());
		assertEquals("messages[0].audio", violation.fieldPath());
		assertFalse(violation.reason().isBlank());
	}

	@Test
	void nullBlankAndSeparatorOnlyHeadersGrantNothing() {
		for (String header : new String[] { null, "", "   ", " , , " }) {
			DegradationGrant grant = DegradationGrant.parse(header);
			assertTrue(grant.allowed().isEmpty(), "header=" + header);
			assertFalse(grant.allows(BUDGET_TO_EFFORT), "header=" + header);
			assertEquals(DegradationGrant.empty(), grant);
		}
	}

	@Test
	void headerParsesMultipleIdsTrimmingSpacesAndEmptySegments() {
		DegradationGrant grant = DegradationGrant.parse(" " + BUDGET_TO_EFFORT + " , ," + FORCE_TO_AUTO + ", ");

		assertEquals(Set.of(BUDGET_TO_EFFORT, FORCE_TO_AUTO), grant.allowed());
		assertTrue(grant.allows(BUDGET_TO_EFFORT));
		assertTrue(grant.allows(FORCE_TO_AUTO));
		assertFalse(grant.allows("thinking.display_raw"));
		assertFalse(grant.allows(""));
	}

	@Test
	void repeatedHeaderIdIsDeduplicated() {
		DegradationGrant grant = DegradationGrant.parse("thinking.display_raw, thinking.display_raw");

		assertEquals(Set.of("thinking.display_raw"), grant.allowed());
	}

	@Test
	void unknownDegradationIdInHeaderIsRejectedWithHeaderPath() {
		CapabilityException error = assertThrows(CapabilityException.class,
				() -> DegradationGrant.parse(BUDGET_TO_EFFORT + ",thinking.free_lunch"));

		assertEquals(400, error.status());
		Violation violation = error.violations().get(0);
		assertEquals("capability_degradation_not_authorized", violation.code());
		assertEquals(HEADER, violation.fieldPath());
		assertTrue(violation.reason().contains("thinking.free_lunch"), violation.reason());
	}

	@Test
	void degradationIdsAreCaseSensitiveAndWildcardsAreRejected() {
		for (String header : new String[] { "THINKING.BUDGET_TO_EFFORT", "Thinking.Display_Raw", "all", "*" }) {
			CapabilityException error = assertThrows(CapabilityException.class, () -> DegradationGrant.parse(header));
			assertEquals(400, error.status(), header);
			assertEquals("capability_degradation_not_authorized", error.violations().get(0).code(), header);
			assertEquals(HEADER, error.violations().get(0).fieldPath(), header);
		}
	}

	@Test
	void knownIdsAreExactlyTheMatrixListAndImmutable() {
		Set<String> matrixIds = Set.of("thinking.budget_to_effort", "thinking.display_raw", "tool_choice.force_to_auto",
				"response_format.schema_to_object", "tool_call.legacy_function");

		assertEquals(matrixIds, DegradationGrant.KNOWN_IDS);
		assertEquals(HEADER, DegradationGrant.HEADER);
		for (String id : matrixIds) {
			assertTrue(DegradationGrant.parse(id).allows(id), id);
		}
		assertThrows(UnsupportedOperationException.class, () -> DegradationGrant.KNOWN_IDS.add("thinking.new"));
	}

	@Test
	void grantCopiesSourceSetAndExposesImmutableView() {
		Set<String> source = new HashSet<>(Set.of("thinking.display_raw"));
		DegradationGrant grant = new DegradationGrant(source);

		source.add(FORCE_TO_AUTO);

		assertEquals(Set.of("thinking.display_raw"), grant.allowed());
		assertFalse(grant.allows(FORCE_TO_AUTO));
		assertThrows(UnsupportedOperationException.class, () -> grant.allowed().add(FORCE_TO_AUTO));
	}

	@Test
	void grantCannotBeBuiltWithUnknownIdEvenWithoutHeader() {
		CapabilityException error = assertThrows(CapabilityException.class,
				() -> new DegradationGrant(Set.of("thinking.unknown")));

		assertEquals(400, error.status());
		Violation violation = error.violations().get(0);
		assertEquals("capability_degradation_not_authorized", violation.code());
		assertEquals("allowed", violation.fieldPath());
		assertTrue(violation.reason().contains("thinking.unknown"), violation.reason());
	}

	@Test
	void degradableRuleWithoutDegradationIdFailsConstruction() {
		assertThrows(IllegalArgumentException.class,
				() -> new FieldRule("thinking.budget_tokens", Classification.DEGRADABLE, null, "预算降档"));
		assertThrows(IllegalArgumentException.class,
				() -> new FieldRule("thinking.budget_tokens", Classification.DEGRADABLE, "  ", "预算降档"));
	}

	@Test
	void degradableRuleWithUnknownDegradationIdFailsConstruction() {
		for (String id : new String[] { "thinking.budget_to_effortt", "thinking.budget_to_effort ",
				"THINKING.BUDGET_TO_EFFORT", "thinking.free_lunch", "*" }) {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> new FieldRule("thinking.budget_tokens", Classification.DEGRADABLE, id, "预算降档"), "id=" + id);
			assertTrue(error.getMessage().contains(id), error.getMessage());
			assertTrue(error.getMessage().contains("thinking.budget_tokens"), error.getMessage());
		}
	}

	@Test
	void everyKnownDegradationIdIsAcceptedByFieldRule() {
		for (String id : DegradationGrant.KNOWN_IDS) {
			assertEquals(id, degradable("thinking.budget_tokens", id, "语义损失").degradationId(), id);
		}
	}

	@Test
	void rejectRuleWithoutReasonFailsConstruction() {
		assertThrows(IllegalArgumentException.class, () -> new FieldRule("seed", Classification.REJECT, null, null));
		assertThrows(IllegalArgumentException.class, () -> new FieldRule("seed", Classification.REJECT, null, " "));
	}

	@Test
	void blankFieldPathAndNullClassificationFailConstruction() {
		for (String path : new String[] { null, "", "   " }) {
			assertThrows(IllegalArgumentException.class,
					() -> new FieldRule(path, Classification.EQUIVALENT, null, null), "path=" + path);
		}
		assertThrows(NullPointerException.class, () -> new FieldRule("seed", null, null, null));
	}

	@Test
	void violationRejectsNullAndBlankFieldsAndNamesTheField() {
		for (String blank : new String[] { null, "", "   " }) {
			assertTrue(assertThrows(IllegalArgumentException.class, () -> new Violation(blank, "max_tokens", "冲突"))
				.getMessage()
				.contains("code"), "code=" + blank);
			assertTrue(assertThrows(IllegalArgumentException.class,
					() -> new Violation("capability_invalid_value", blank, "冲突"))
				.getMessage()
				.contains("fieldPath"), "fieldPath=" + blank);
			assertTrue(assertThrows(IllegalArgumentException.class,
					() -> new Violation("capability_invalid_value", "max_tokens", blank))
				.getMessage()
				.contains("reason"), "reason=" + blank);
		}
	}

	@Test
	void appliedDegradationRejectsNullAndBlankFieldsAndNamesTheField() {
		for (String blank : new String[] { null, "", "   " }) {
			assertTrue(assertThrows(IllegalArgumentException.class,
					() -> new AppliedDegradation(blank, BUDGET_TO_EFFORT, "预算降档"))
				.getMessage()
				.contains("fieldPath"), "fieldPath=" + blank);
			assertTrue(assertThrows(IllegalArgumentException.class,
					() -> new AppliedDegradation("thinking.budget_tokens", blank, "预算降档"))
				.getMessage()
				.contains("degradationId"), "degradationId=" + blank);
			assertTrue(assertThrows(IllegalArgumentException.class,
					() -> new AppliedDegradation("thinking.budget_tokens", BUDGET_TO_EFFORT, blank))
				.getMessage()
				.contains("reason"), "reason=" + blank);
		}
	}

	@Test
	void exceptionCopiesViolationListAndExposesImmutableView() {
		List<Violation> source = new ArrayList<>();
		source.add(new Violation("capability_invalid_value", "messages[0].image.data", "损坏 base64"));
		CapabilityException error = new CapabilityException(400, source);

		source.clear();

		assertEquals(List.of(new Violation("capability_invalid_value", "messages[0].image.data", "损坏 base64")),
				error.violations());
		assertThrows(UnsupportedOperationException.class,
				() -> error.violations().add(new Violation("capability_unknown_field", "extra", "x")));
		assertThrows(IllegalArgumentException.class, () -> new CapabilityException(400, List.of()));
	}

	@Test
	void exceptionRejectsNullViolationList() {
		assertThrows(IllegalArgumentException.class, () -> new CapabilityException(400, null));
	}

	@Test
	void exceptionStatusMustBeAnHttpErrorStatus() {
		for (int status : new int[] { -1, 0, 200, 399, 600, 999 }) {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> new CapabilityException(status, List.of(violation())), "status=" + status);
			assertTrue(error.getMessage().contains("status"), error.getMessage());
		}
		assertThrows(IllegalArgumentException.class,
				() -> new CapabilityException("capability_invalid_value", 200, "seed", "越界"));
		for (int status : new int[] { 400, 404, 422, 500, 599 }) {
			assertEquals(status, new CapabilityException(status, List.of(violation())).status(), "status=" + status);
		}
	}

	@Test
	void exceptionNeverRendersNullViolationFields() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new CapabilityException(CapabilityException.INVALID_VALUE, 400, "max_tokens", null));
		assertTrue(error.getMessage().contains("reason"), error.getMessage());

		CapabilityException valid = new CapabilityException(CapabilityException.INVALID_VALUE, 400, "max_tokens", "越界");
		assertFalse(valid.getMessage().contains("null"), valid.getMessage());
	}

	@Test
	void exceptionKeepsViolationOrderAndSingleViolationConvenience() {
		Violation first = new Violation("capability_invalid_value", "max_tokens", "与 max_completion_tokens 冲突");
		Violation second = new Violation("capability_continuation_invalid", "messages[1].signature", "信封完整性失败");

		CapabilityException multi = new CapabilityException(400, List.of(first, second));
		assertEquals(List.of(first, second), multi.violations());

		CapabilityException single = new CapabilityException("capability_continuation_invalid", 400,
				"messages[1].signature", "信封完整性失败");
		assertEquals(400, single.status());
		assertEquals(List.of(second), single.violations());
		assertTrue(single.getMessage().contains("capability_continuation_invalid"), single.getMessage());
		assertTrue(single.getMessage().contains("messages[1].signature"), single.getMessage());
	}

	@Test
	void exceptionPreservesCauseWithoutLeakingItIntoTheClientMessage() {
		IllegalStateException cause = new IllegalStateException("upstream socket reset");
		CapabilityException single = new CapabilityException(CapabilityException.INVALID_VALUE, 400, "request",
				"invalid request JSON", cause);
		assertSame(cause, single.getCause(), "解析/IO 失败不得吞栈");
		assertEquals(400, single.status());
		assertEquals(List.of(new Violation(CapabilityException.INVALID_VALUE, "request", "invalid request JSON")),
				single.violations());
		assertEquals("capability_invalid_value request: invalid request JSON", single.getMessage());
		assertFalse(single.getMessage().contains("upstream socket reset"), "客户端消息不得拼接底层异常文本");

		CapabilityException multi = new CapabilityException(502, List.of(violation()), cause);
		assertSame(cause, multi.getCause());
		assertEquals(502, multi.status());
		assertEquals(List.of(violation()), multi.violations());

		assertNull(new CapabilityException(400, List.of(violation())).getCause());
		assertNull(new CapabilityException(CapabilityException.INVALID_VALUE, 400, "seed", "越界").getCause());
		assertNull(new CapabilityException(CapabilityException.INVALID_VALUE, 400, "seed", "越界", null).getCause());
		assertThrows(IllegalArgumentException.class,
				() -> new CapabilityException(CapabilityException.INVALID_VALUE, 400, "seed", null, cause));
	}

	@Test
	void stableCodesMatchMatrix() {
		assertEquals("capability_unknown_field", CapabilityException.UNKNOWN_FIELD);
		assertEquals("capability_not_supported", CapabilityException.NOT_SUPPORTED);
		assertEquals("capability_degradation_not_authorized", CapabilityException.DEGRADATION_NOT_AUTHORIZED);
		assertEquals("capability_invalid_value", CapabilityException.INVALID_VALUE);
		assertEquals("capability_continuation_invalid", CapabilityException.CONTINUATION_INVALID);
	}

	@Test
	void auditInstancesDoNotShareState() {
		DegradationAudit first = new DegradationAudit(DegradationGrant.parse("thinking.display_raw"));
		DegradationAudit second = new DegradationAudit(DegradationGrant.parse("tool_call.legacy_function"));

		first.recordApplied("thinking.display", "thinking.display_raw", "固定回传原文推理");

		assertEquals(List.of(new AppliedDegradation("thinking.display", "thinking.display_raw", "固定回传原文推理")),
				first.applied());
		assertTrue(second.applied().isEmpty());
		assertEquals(Set.of("thinking.display_raw"), first.requested());
		assertEquals(Set.of("tool_call.legacy_function"), second.requested());

		second.recordApplied("functions", "tool_call.legacy_function", "legacy→tool_calls");

		assertEquals(1, first.applied().size());
		assertEquals(List.of(new AppliedDegradation("functions", "tool_call.legacy_function", "legacy→tool_calls")),
				second.applied());
	}

	@Test
	void auditAppliedSnapshotIsImmutableAndDetached() {
		DegradationAudit audit = new DegradationAudit(DegradationGrant.parse(BUDGET_TO_EFFORT));
		audit.recordApplied("thinking.budget_tokens", BUDGET_TO_EFFORT, "预算降档");

		List<AppliedDegradation> snapshot = audit.applied();
		assertThrows(UnsupportedOperationException.class,
				() -> snapshot.add(new AppliedDegradation("tool_choice", FORCE_TO_AUTO, "退化为 auto")));

		audit.recordApplied("tool_choice", FORCE_TO_AUTO, "退化为 auto");

		assertEquals(1, snapshot.size());
		assertEquals(2, audit.applied().size());
	}

	@Test
	void auditRequestedSnapshotIsImmutable() {
		DegradationAudit audit = new DegradationAudit(DegradationGrant.parse(BUDGET_TO_EFFORT));

		assertThrows(UnsupportedOperationException.class, () -> audit.requested().add(FORCE_TO_AUTO));
	}

	@Test
	void auditRejectsUnknownDegradationIdAndKeepsTrailEmpty() {
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> audit.recordApplied("thinking.budget_tokens", "thinking.free_lunch", "预算降档"));

		assertTrue(error.getMessage().contains("thinking.free_lunch"), error.getMessage());
		assertTrue(error.getMessage().contains("thinking.budget_tokens"), error.getMessage());
		assertTrue(audit.applied().isEmpty());
	}

	@Test
	void auditRecordsKnownIdWithoutDecidingAuthorization() {
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());

		audit.recordApplied("thinking.budget_tokens", BUDGET_TO_EFFORT, "预算降档");

		assertTrue(audit.requested().isEmpty());
		assertEquals(List.of(new AppliedDegradation("thinking.budget_tokens", BUDGET_TO_EFFORT, "预算降档")),
				audit.applied());
	}

	@Test
	void auditRejectsNullGrant() {
		assertThrows(NullPointerException.class, () -> new DegradationAudit(null));
	}

	@Test
	void policyCarriesNoStateBetweenRequests() {
		FieldRule rule = degradable("thinking.budget_tokens", BUDGET_TO_EFFORT, "预算降档");
		DegradationGrant authorized = DegradationGrant.parse(BUDGET_TO_EFFORT);
		DegradationAudit first = new DegradationAudit(authorized);
		DegradationAudit second = new DegradationAudit(DegradationGrant.empty());

		assertInstanceOf(Decision.Degrade.class, CapabilityPolicy.evaluate(rule, first));
		assertThrows(CapabilityException.class, () -> CapabilityPolicy.evaluate(rule, second));

		assertEquals(1, first.applied().size());
		assertTrue(second.applied().isEmpty());
	}

}