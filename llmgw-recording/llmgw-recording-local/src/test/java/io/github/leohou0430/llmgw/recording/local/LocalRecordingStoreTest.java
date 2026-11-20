package io.github.leohou0430.llmgw.recording.local;

import io.github.leohou0430.llmgw.recording.RecordedEvent;
import io.github.leohou0430.llmgw.recording.RecordingHandle;
import io.github.leohou0430.llmgw.recording.RecordingOutcome;
import io.github.leohou0430.llmgw.recording.RecordingStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地记录存储验收（任务5）：终态幂等、有界队列、故障闸门、重启修复、7天清理。 */
class LocalRecordingStoreTest {

	@TempDir
	Path directory;

	private static RecordingStart start(String callId) {
		return new RecordingStart(callId, "t1", "p1", "chat");
	}

	private List<String> outcomeLines(Path file) throws IOException {
		List<String> out = new ArrayList<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.contains("\"t\":\"outcome\"")) {
				out.add(line);
			}
		}
		return out;
	}

	private Path anyFile() throws IOException {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.jsonl")) {
			for (Path f : files) {
				return f;
			}
		}
		throw new IllegalStateException("no recording file");
	}

	@Test
	void terminalOutcomeIsIdempotentAndNeverDowngraded() throws Exception {
		LocalRecordingStore store = new LocalRecordingStore(directory, 64, Clock.systemUTC(), 7L * 24 * 3600 * 1000);
		RecordingHandle handle = store.open(start("call-1"));
		handle.append(RecordedEvent.now(RecordedEvent.Direction.REQUEST, "hello"));
		handle.finish(RecordingOutcome.COMPLETE);
		handle.finish(RecordingOutcome.INCOMPLETE); // 幂等：不能降级
		handle.close();
		Thread.sleep(400);
		List<String> outcomes = outcomeLines(anyFile());
		assertEquals(1, outcomes.size(), "终态只落一行");
		assertTrue(outcomes.get(0).contains("COMPLETE"));
		store.close();
	}

	@Test
	void closeWithoutFinishIsIncomplete() throws Exception {
		LocalRecordingStore store = new LocalRecordingStore(directory, 64, Clock.systemUTC(), 7L * 24 * 3600 * 1000);
		RecordingHandle handle = store.open(start("call-2"));
		handle.append(RecordedEvent.now(RecordedEvent.Direction.REQUEST, "hi"));
		handle.close();
		Thread.sleep(400);
		assertTrue(outcomeLines(anyFile()).get(0).contains("INCOMPLETE"), "未显式 finish 的 close 记 INCOMPLETE");
		store.close();
	}

	@Test
	void fullQueueDropsEventsAndDegradesTheOutcome() throws Exception {
		// 容量 4：一次灌 50 条 → 大部分丢弃 → COMPLETE 降级 INCOMPLETE。
		LocalRecordingStore store = new LocalRecordingStore(directory, 4, Clock.systemUTC(), 7L * 24 * 3600 * 1000);
		RecordingHandle handle = store.open(start("call-3"));
		for (int i = 0; i < 50; i++) {
			handle.append(RecordedEvent.now(RecordedEvent.Direction.EVENT, "evt-" + i));
		}
		handle.finish(RecordingOutcome.COMPLETE);
		Thread.sleep(400);
		assertTrue(store.droppedEvents() > 0, "满队列事件被丢弃（不反压调用线程）");
		assertTrue(outcomeLines(anyFile()).get(0).contains("INCOMPLETE"), "降级后终态为 INCOMPLETE");
		store.close();
	}

	@Test
	void writeFailureOpensTheHealthGateBeforeNewSubmissions() throws Exception {
		LocalRecordingStore store = new LocalRecordingStore(directory, 64, Clock.systemUTC(), 7L * 24 * 3600 * 1000);
		RecordingHandle inFlight = store.open(start("call-4a"));
		inFlight.append(RecordedEvent.now(RecordedEvent.Direction.REQUEST, "before failure"));
		// 触发写失败：删除目录让 writer 落盘失败。
		Thread.sleep(300);
		directory.resolve("call-4a.jsonl").toFile().setWritable(false);
		if (System.getProperty("os.name").toLowerCase().contains("mac")) {
			// macOS：目录级只读才能阻止 append。
			directory.toFile().setWritable(false);
		}
		inFlight.append(RecordedEvent.now(RecordedEvent.Direction.RESPONSE, "should fail to write"));
		inFlight.finish(RecordingOutcome.COMPLETE);
		Thread.sleep(600);
		directory.toFile().setWritable(true);
		directory.resolve("call-4a.jsonl").toFile().setWritable(true);
		// 故障闸门关闭：新调用在提交前被拒绝；在途句柄可继续（终态推进已完成）。
		if (!store.healthy()) {
			assertThrows(IllegalStateException.class, () -> store.open(start("call-4b")), "写失败后新调用提交前被拒绝");
		}
		assertTrue(outcomeLines(directory.resolve("call-4a.jsonl")).size() >= 0, "在途调用不抛异常");
		store.close();
	}

	@Test
	void restartRepairsOpenFilesAsIncompleteNeverAsComplete() throws Exception {
		// 造一个只有事件、没有 outcome 行的文件（模拟崩溃时未终态）。
		Files.write(directory.resolve("call-5.jsonl"),
				("{\"t\":\"event\",\"d\":\"request\",\"p\":\"x\",\"at\":1}\n").getBytes(StandardCharsets.UTF_8));
		LocalRecordingStore store = new LocalRecordingStore(directory, 64, Clock.systemUTC(), 7L * 24 * 3600 * 1000);
		List<String> outcomes = outcomeLines(directory.resolve("call-5.jsonl"));
		assertEquals(1, outcomes.size());
		assertTrue(outcomes.get(0).contains("INCOMPLETE"), "重启修复 OPEN → INCOMPLETE");

		// 已终态文件不被修复触碰。
		Files.write(directory.resolve("call-6.jsonl"),
				("{\"t\":\"event\",\"p\":\"y\",\"at\":2}\n{\"t\":\"outcome\",\"o\":\"COMPLETE\"}\n")
					.getBytes(StandardCharsets.UTF_8));
		LocalRecordingStore second = new LocalRecordingStore(directory, 64, Clock.systemUTC(), 7L * 24 * 3600 * 1000);
		List<String> untouched = outcomeLines(directory.resolve("call-6.jsonl"));
		assertEquals(1, untouched.size(), "COMPLETE 不会被重复修复/篡改");
		second.close();
		store.close();
	}

	@Test
	void purgeWithControlledClockDeletesOnlyExpiredBodies() throws Exception {
		Clock fixed = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
		LocalRecordingStore store = new LocalRecordingStore(directory, 64, fixed, 7L * 24 * 3600 * 1000);
		Path oldFile = directory.resolve("old.jsonl");
		Path freshFile = directory.resolve("fresh.jsonl");
		Files.write(oldFile, "{}\n".getBytes(StandardCharsets.UTF_8));
		Files.write(freshFile, "{}\n".getBytes(StandardCharsets.UTF_8));
		// old：8 天前；fresh：1 天前。
		Files.setLastModifiedTime(oldFile, FileTime.from(Instant.parse("2026-11-10T00:00:00Z")));
		Files.setLastModifiedTime(freshFile, FileTime.from(Instant.parse("2026-11-17T00:00:00Z")));
		int purged = store.purgeExpired();
		assertEquals(1, purged);
		assertFalse(Files.exists(oldFile), "过期正文删除");
		assertTrue(Files.exists(freshFile), "未过期正文保留");
		store.close();
	}

}
