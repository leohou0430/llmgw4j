package io.github.leohou0430.llmgw.conversion.gemini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务7安全合同：仅白名单操作可转发；model 注入一律拒绝。 */
class GeminiPathsTest {

	@Test
	void generateContentParsesModelAndOperation() {
		var parsed = GeminiPaths.parse("/v1beta/models/gemini-2.0-flash:generateContent");
		assertEquals("gemini-2.0-flash", parsed.model());
		assertEquals("generateContent", parsed.operation());
		assertEquals(false, parsed.streaming());
	}

	@Test
	void streamGenerateContentAndCountTokensAreAllowed() {
		assertEquals("streamGenerateContent",
				GeminiPaths.parse("/v1beta/models/gemini-2.5-pro:streamGenerateContent").operation());
		assertTrue(GeminiPaths.parse("/v1beta/models/gemini-2.5-pro:streamGenerateContent").streaming());
		assertEquals("countTokens", GeminiPaths.parse("/v1beta/models/gemini-2.0-flash:countTokens").operation());
	}

	@Test
	void unknownOperationsAndArbitraryPathsAreRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> GeminiPaths.parse("/v1beta/models/gemini-2.0-flash:embedContent"), "未配置操作拒绝");
		assertThrows(IllegalArgumentException.class,
				() -> GeminiPaths.parse("/v1/models/gemini-2.0-flash:generateContent"), "未配置 API 版本拒绝");
		assertThrows(IllegalArgumentException.class, () -> GeminiPaths.parse("/v1beta/models"), "无操作后缀拒绝");
	}

	@Test
	void modelPathInjectionsAreRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> GeminiPaths.parse("/v1beta/models/org1/gemini:generateContent"), "路径分隔注入拒绝");
		assertThrows(IllegalArgumentException.class, () -> GeminiPaths.parse("/v1beta/models/../admin:generateContent"),
				"相对段注入拒绝");
		assertThrows(IllegalArgumentException.class,
				() -> GeminiPaths.parse("/v1beta/models/gemini%2Fsecret:generateContent"), "编码斜杠拒绝");
		// 查询参数不参与 model 解析：model 只来自路径（查询里的 model=evil 被忽略）。
		var queryProbe = GeminiPaths.parse("/v1beta/models/x:generateContent?model=evil");
		assertEquals("x", queryProbe.model());
	}

	@Test
	void queryStringsDoNotBreakTheOperationSuffix() {
		var parsed = GeminiPaths.parse("/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse");
		assertEquals("gemini-2.0-flash", parsed.model());
		assertEquals("streamGenerateContent", parsed.operation());
	}

}
