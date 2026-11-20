package io.github.leohou0430.llmgw.conversion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpstreamSpiTest {

	@Test
	void payloadRequiresJsonAndPath() {
		UpstreamPayload payload = new UpstreamPayload("{}", "/chat/completions");
		assertEquals("{}", payload.rawJson());
		assertEquals("/chat/completions", payload.path());

		assertThrows(NullPointerException.class, () -> new UpstreamPayload(null, "/chat/completions"));
		assertThrows(NullPointerException.class, () -> new UpstreamPayload("{}", null));
		assertThrows(IllegalArgumentException.class, () -> new UpstreamPayload(" ", "/chat/completions"));
		assertThrows(IllegalArgumentException.class, () -> new UpstreamPayload("{}", " "));
	}

	@Test
	void upstreamExceptionCarriesStableCodeAndStatus() {
		UpstreamException error = new UpstreamException("upstream terminated abnormally: finish_reason=aborted");
		assertEquals("upstream_error", error.code());
		assertEquals(502, error.status());
		assertEquals("upstream terminated abnormally: finish_reason=aborted", error.getMessage());
		assertThrows(NullPointerException.class, () -> new UpstreamException(null));
	}

	@Test
	void upstreamExceptionPreservesCauseAndOptionalFieldPath() {
		IllegalStateException cause = new IllegalStateException("socket closed");
		UpstreamException withCause = new UpstreamException("upstream chunk decode failed", cause);
		assertEquals("upstream_error", withCause.code());
		assertEquals(502, withCause.status());
		assertEquals("upstream chunk decode failed", withCause.getMessage());
		assertSame(cause, withCause.getCause(), "底层异常栈必须保留，不得吞栈");
		assertNull(withCause.fieldPath(), "fieldPath 可空");

		UpstreamException located = new UpstreamException("upstream response has no message", "choices[0].message",
				cause);
		assertEquals("choices[0].message", located.fieldPath());
		assertSame(cause, located.getCause());
		assertEquals("upstream_error", located.code());
		assertEquals(502, located.status());

		UpstreamException noCause = new UpstreamException("upstream response has no choices", "choices", null);
		assertEquals("choices", noCause.fieldPath());
		assertNull(noCause.getCause());
		assertNull(new UpstreamException("upstream terminated abnormally").fieldPath());

		assertThrows(NullPointerException.class, () -> new UpstreamException(null, cause));
		assertThrows(NullPointerException.class, () -> new UpstreamException(null, "choices", cause));
	}

}