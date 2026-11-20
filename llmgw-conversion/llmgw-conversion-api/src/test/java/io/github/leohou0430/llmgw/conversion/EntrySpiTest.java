package io.github.leohou0430.llmgw.conversion;

import io.github.leohou0430.llmgw.core.extension.ExtensionPoint;
import io.github.leohou0430.llmgw.core.extension.Extensions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntrySpiTest {

	private static final ContinuationEnvelope ENVELOPE = new ContinuationEnvelope(
			"entry-spi-test-key".getBytes(StandardCharsets.UTF_8));

	@Test
	void capabilityContextCarriesTenantProjectAuditAndEnvelope() {
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());
		CapabilityContext ctx = new CapabilityContext("tenant-1", "project-1", audit, ENVELOPE);
		assertEquals("tenant-1", ctx.tenantId());
		assertEquals("project-1", ctx.projectId());
		assertSame(audit, ctx.audit());
		assertSame(ENVELOPE, ctx.envelope());
	}

	@Test
	void capabilityContextRejectsMissingIdentityAuditOrEnvelope() {
		DegradationAudit audit = new DegradationAudit(DegradationGrant.empty());
		assertThrows(NullPointerException.class, () -> new CapabilityContext(null, "project-1", audit, ENVELOPE));
		assertThrows(NullPointerException.class, () -> new CapabilityContext("tenant-1", null, audit, ENVELOPE));
		assertThrows(NullPointerException.class, () -> new CapabilityContext("tenant-1", "project-1", null, ENVELOPE));
		assertThrows(NullPointerException.class, () -> new CapabilityContext("tenant-1", "project-1", audit, null));
		assertThrows(IllegalArgumentException.class, () -> new CapabilityContext(" ", "project-1", audit, ENVELOPE));
		assertThrows(IllegalArgumentException.class, () -> new CapabilityContext("tenant-1", " ", audit, ENVELOPE));
	}

	@Test
	void entryCodecIsNamedExtensionPointWithoutDefaultImplementation() {
		assertTrue(EntryCodec.class.isAnnotationPresent(ExtensionPoint.class));
		Extensions<EntryCodec> extensions = Extensions.load(EntryCodec.class);
		assertTrue(extensions.getSupportedExtensions().isEmpty(), "api jar 不得自带入口实现");
		assertThrows(IllegalStateException.class, extensions::getDefaultExtension);
		assertThrows(IllegalStateException.class, () -> extensions.getExtension("chat"));
	}

}