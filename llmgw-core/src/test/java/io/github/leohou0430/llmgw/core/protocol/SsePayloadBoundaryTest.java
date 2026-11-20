package io.github.leohou0430.llmgw.core.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SsePayloadBoundaryTest {

	@Test
	void whitespaceExtractionPreservesDeltasAndOriginalWireBytes() {
		String[] payloads = { "{\"choices\":[{\"delta\":{\"content\":\"你好\\nworld\"}}]}",
				"{\"type\":\"response.output_text.delta\",\"delta\":\"你好\\nworld\"}",
				"{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"你好\\nworld\"}}" };
		var kinds = new ProtocolKind[] { ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES };
		for (int i = 0; i < kinds.length; i++) {
			for (String prefix : new String[] { "data:", "data: ", "\t data:\t", "\0 data: " }) {
				for (String suffix : new String[] { "", "\n", "\r\n\r\n", "\0" }) {
					String raw = prefix + payloads[i] + suffix;
					var result = ChunkParser.parse(kinds[i], raw);
					assertEquals(raw, result.raw());
					assertEquals("你好\nworld", result.delta());
					assertFalse(result.done());
				}
			}
		}
	}

	@Test
	void commentEmptyMalformedAndNonAsciiPrefixStayPassthrough() {
		for (var kind : new ProtocolKind[] { ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES }) {
			for (String raw : new String[] { "", " \t\r\n", ": keep-alive\n\n", "event: delta\n", "data:\n",
					"data: {broken}\n", "\u2003data: [DONE]\n" }) {
				var result = ChunkParser.parse(kind, raw);
				assertEquals(raw, result.raw());
				assertEquals("", result.delta());
				assertFalse(result.done());
				assertFalse(result.hasUsage());
			}
		}
	}

	@Test
	void usageAndTerminatorsSurviveOuterWhitespace() {
		String chat = "\tdata: {\"usage\":{\"completion_tokens\":12}}\r\n";
		var usage = ChunkParser.parse(ProtocolKind.CHAT, chat);
		assertTrue(usage.hasUsage());
		assertEquals(12, usage.completionTokens());
		assertEquals(chat, usage.raw());
		assertTrue(ChunkParser.parse(ProtocolKind.CHAT, " data: [DONE]\r\n").done());
		assertTrue(ChunkParser.parse(ProtocolKind.RESPONSES, " data: {\"type\":\"response.completed\"}\n").done());
		assertTrue(ChunkParser.parse(ProtocolKind.MESSAGES, " data: {\"type\":\"message_stop\"}\n").done());
	}

	/**
	 * The conversion transcoder emits a whole SSE event (an {@code event:} field line
	 * plus its {@code data:} line) as ONE string, unlike the transport which splits
	 * lines. Accounting must still find the data payload so the entry terminal and its
	 * usage are recognized; otherwise a successfully converted responses/messages stream
	 * is mistaken for a truncation.
	 */
	@Test
	void wholeEventWithAnEventLineStillYieldsItsDataTerminalAndUsage() {
		String responsesTerminal = "event: response.completed\n"
				+ "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"output_tokens\":7}}}\n\n";
		var responses = ChunkParser.parse(ProtocolKind.RESPONSES, responsesTerminal);
		assertEquals(responsesTerminal, responses.raw());
		assertTrue(responses.done(), "responses terminal carried in a whole event must be recognized");
		assertTrue(responses.hasUsage());
		assertEquals(7, responses.completionTokens());

		String messagesTerminal = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
		var messages = ChunkParser.parse(ProtocolKind.MESSAGES, messagesTerminal);
		assertEquals(messagesTerminal, messages.raw());
		assertTrue(messages.done(), "messages terminal carried in a whole event must be recognized");

		String responsesDelta = "event: response.output_text.delta\n"
				+ "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n";
		assertEquals("hi", ChunkParser.parse(ProtocolKind.RESPONSES, responsesDelta).delta());

		String messagesDelta = "event: content_block_delta\n"
				+ "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}\n\n";
		assertEquals("hi", ChunkParser.parse(ProtocolKind.MESSAGES, messagesDelta).delta());
	}

	/** An event field line with no data line carries no payload and stays passthrough. */
	@Test
	void eventLineWithoutDataStaysPassthrough() {
		for (var kind : new ProtocolKind[] { ProtocolKind.CHAT, ProtocolKind.RESPONSES, ProtocolKind.MESSAGES }) {
			var result = ChunkParser.parse(kind, "event: response.completed\n");
			assertFalse(result.done());
			assertEquals("", result.delta());
			assertFalse(result.hasUsage());
		}
	}

	/**
	 * A data-prefixed event whose first data line is followed by another field line must
	 * yield ONLY the first line's payload, exactly like the event-line scan branch. The
	 * fast path must stop at the first line boundary instead of merging the remainder
	 * into the JSON payload.
	 */
	@Test
	void fastPathDataPayloadStopsAtTheFirstLineBoundary() {
		String twoData = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\"bye\"}}]}\n\n";
		var chat = ChunkParser.parse(ProtocolKind.CHAT, twoData);
		assertEquals(twoData, chat.raw());
		assertEquals("hi", chat.delta(), "fast path must stop at the first line boundary");
		assertFalse(chat.done());

		String doneThenData = "data: [DONE]\ndata: {\"usage\":{\"completion_tokens\":9}}\n\n";
		assertTrue(ChunkParser.parse(ProtocolKind.CHAT, doneThenData).done(),
				"[DONE] on the first data line is the terminal even when another line follows");
	}

	/**
	 * A whole event separated by CR-only line endings must still yield its data
	 * terminal/usage.
	 */
	@Test
	void wholeEventWithCrOnlySeparatorsStillYieldsItsDataTerminal() {
		String responsesTerminal = "event: response.completed\r"
				+ "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"output_tokens\":7}}}\r\r";
		var responses = ChunkParser.parse(ProtocolKind.RESPONSES, responsesTerminal);
		assertTrue(responses.done(), "CR-only separated whole event must still yield its data terminal");
		assertTrue(responses.hasUsage());
		assertEquals(7, responses.completionTokens());
	}

}
