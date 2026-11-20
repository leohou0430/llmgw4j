package io.github.leohou0430.llmgw.recording.local;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 消毒器验收（任务5）：凭证/令牌 URL/Base64 与二进制不落盘，合法文本可记录。 */
class RecordingSanitizerTest {

	@Test
	void authorizationHeadersAreMasked() {
		String out = RecordingSanitizer.sanitize("Authorization: Bearer sk-live-abc123\nX-Api-Key: secret-value-999\n");
		assertFalse(out.contains("sk-live-abc123"));
		assertFalse(out.contains("secret-value-999"));
		assertTrue(out.contains("Authorization: ***"));
		assertTrue(out.contains("X-Api-Key: ***"));
	}

	@Test
	void jsonCredentialFieldsAreMasked() {
		String out = RecordingSanitizer
			.sanitize("{\"authorization\":\"Bearer xyz\",\"api-key\":\"k-777\",\"model\":\"m\"}");
		assertFalse(out.contains("Bearer xyz"));
		assertFalse(out.contains("k-777"));
		assertTrue(out.contains("\"model\":\"m\""), "非凭证字段保持原样");
	}

	@Test
	void tokenizedUrlsAreMasked() {
		String out = RecordingSanitizer.sanitize("GET https://up.example/v1/files?token=abc123&dl=1 HTTP/1.1");
		assertFalse(out.contains("abc123"));
		assertTrue(out.contains("dl=1"), "非令牌参数保持原样");
	}

	@Test
	void dataUrisAndLongBase64BlocksAreOmitted() {
		String dataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==";
		String out = RecordingSanitizer.sanitize("payload " + dataUri + " tail");
		assertFalse(out.contains("iVBORw0KGgo"), "data URI 整段省略");
		assertTrue(out.contains("[data-uri"));
		assertTrue(out.contains("tail"), "合法文本保留");
	}

	@Test
	void binaryFragmentsAreOmitted() {
		char[] binary = new char[256];
		for (int i = 0; i < binary.length; i++) {
			binary[i] = (char) (0x01 + (i % 7)); // 高密度控制字符
		}
		String out = RecordingSanitizer.sanitize(new String(binary));
		assertTrue(out.startsWith("[binary"), "二进制片段整段省略");
		assertTrue(out.contains("bytes omitted]"));
	}

	@Test
	void legitimateToolTextIsRecordedVerbatim() {
		String text = "{\"tool\":\"fs.read\",\"args\":{\"path\":\"/data/report.txt\"},\"result\":\"2026-11-18 revenue: 42,000\"}";
		assertEquals(text, RecordingSanitizer.sanitize(text), "合法工具文本完全一致");
	}

	@Test
	void plainTextPassesThroughByteForByte() {
		String text = "普通中文段落 with ASCII 123 !@# mixed\nsecond line\n";
		assertEquals(text, RecordingSanitizer.sanitize(text), "消毒不影响普通文本（转发字节一致性）");
	}

}
