package io.github.leohou0430.llmgw.recording.local;

import io.github.leohou0430.llmgw.recording.RecordedEvent;
import io.github.leohou0430.llmgw.recording.RecordingHandle;
import io.github.leohou0430.llmgw.recording.RecordingOutcome;
import io.github.leohou0430.llmgw.recording.RecordingStart;
import io.github.leohou0430.llmgw.recording.RecordingStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地（单节点）正文记录存储（任务5）：每调用一个 JSONL 文件，单写线程 + 有界队列（满即丢弃事件、终态降级 INCOMPLETE，绝不反压数据面）。
 *
 * <p>
 * 故障闸门（显式）：写线程落盘失败 → {@code healthy=false}，新调用 {@link #open} 在提交前拒绝；在途句柄继续记录、终态
 * INCOMPLETE。进程重启 时修复无终态行的 OPEN 文件为 INCOMPLETE（绝不误标 COMPLETE）。
 *
 * <p>
 * 保留策略：{@code purgeExpired()} 按 {@code now-retentionMs} 只删正文 文件，不触碰账务/审计/未知预留。
 */
public final class LocalRecordingStore implements RecordingStore, AutoCloseable {

	private final Path directory;

	private final BlockingQueue<Line> queue;

	private final ExecutorService writer;

	private final Clock clock;

	private final long retentionMs;

	private final AtomicBoolean healthy = new AtomicBoolean(true);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicLong dropped = new AtomicLong();

	/** 待写行：null payload 表示终态行（outcome 非空）。 */
	private record Line(String file, String payload, RecordingOutcome outcome) {
	}

	public LocalRecordingStore(Path directory, int queueCapacity, Clock clock, long retentionMs) {
		this.directory = directory;
		this.queue = new ArrayBlockingQueue<>(Math.max(16, queueCapacity));
		this.clock = clock == null ? Clock.systemUTC() : clock;
		this.retentionMs = retentionMs;
		try {
			Files.createDirectories(directory);
		}
		catch (IOException e) {
			throw new IllegalStateException("cannot create recording directory " + directory, e);
		}
		repairOpenFiles();
		this.writer = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "llmgw-recording-writer");
			t.setDaemon(true);
			return t;
		});
		this.writer.submit(this::drainLoop);
	}

	/** 重启修复：无终态行的 OPEN 文件补 INCOMPLETE（不能错误改 COMPLETE）。 */
	private void repairOpenFiles() {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.jsonl")) {
			for (Path file : files) {
				List<String> lines;
				try {
					lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				}
				catch (IOException unreadable) {
					// 半写文件：按 INCOMPLETE 修复。
					appendOutcomeLine(file, RecordingOutcome.INCOMPLETE);
					continue;
				}
				boolean finished = lines.stream().anyMatch(l -> l.contains("\"t\":\"outcome\""));
				if (!finished) {
					appendOutcomeLine(file, RecordingOutcome.INCOMPLETE);
				}
			}
		}
		catch (IOException scanFailed) {
			healthy.set(false);
		}
	}

	private void drainLoop() {
		while (true) {
			Line line;
			try {
				line = queue.poll(500, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			if (line == null) {
				if (closed.get()) {
					return;
				}
				continue;
			}
			try {
				Path file = directory.resolve(line.file());
				if (line.outcome() != null) {
					appendOutcomeLine(file, line.outcome());
				}
				else {
					Files.write(file, (line.payload() + "\n").getBytes(StandardCharsets.UTF_8),
							StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			}
			catch (IOException failed) {
				// 显式健康闸门：写失败 → 新调用提交前拒绝；队列内事件丢弃（INCOMPLETE）。
				healthy.set(false);
				queue.clear();
			}
		}
	}

	private static void appendOutcomeLine(Path file, RecordingOutcome outcome) {
		try {
			Files.write(file,
					("{\"t\":\"outcome\",\"o\":\"" + outcome.name() + "\"}\n").getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException ignored) {
			// 闸门由 drainLoop 的失败路径统一关闭。
		}
	}

	@Override
	public RecordingHandle open(RecordingStart start) {
		if (closed.get()) {
			throw new IllegalStateException("recording store is closed");
		}
		if (!healthy.get()) {
			// 故障闸门：新调用提交前拒绝（不能静默吞写失败）。
			throw new IllegalStateException("recording store is unhealthy after a write failure");
		}
		String file = sanitizeFileStem(start.callId()) + ".jsonl";
		return new LocalHandle(file, start);
	}

	private static String sanitizeFileStem(String callId) {
		return callId.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private final class LocalHandle implements RecordingHandle {

		private final String file;

		private final RecordingStart start;

		private final AtomicBoolean finished = new AtomicBoolean(false);

		private volatile boolean degraded;

		LocalHandle(String file, RecordingStart start) {
			this.file = file;
			this.start = start;
		}

		@Override
		public void append(RecordedEvent event) {
			if (finished.get() || closed.get()) {
				return; // 终态后事件丢弃（幂等终态）
			}
			String sanitized = RecordingSanitizer.sanitize(event.payload());
			String line = "{\"t\":\"event\",\"d\":\"" + event.direction().name().toLowerCase() + "\",\"p\":"
					+ jsonString(sanitized) + ",\"at\":" + event.atMs() + "}";
			if (!queue.offer(new Line(file, line, null))) {
				// 有界：满队列丢弃事件并降级终态，绝不反压数据面。
				degraded = true;
				dropped.incrementAndGet();
			}
		}

		@Override
		public void finish(RecordingOutcome outcome) {
			if (!finished.compareAndSet(false, true)) {
				return; // 终态幂等
			}
			RecordingOutcome effective = degraded && outcome == RecordingOutcome.COMPLETE ? RecordingOutcome.INCOMPLETE
					: outcome;
			if (!queue.offer(new Line(file, null, effective))) {
				// 队列满：终态行同步落盘（终态行绝不能丢）。
				appendOutcomeLine(directory.resolve(file), effective);
			}
		}

		@Override
		public void close() {
			finish(RecordingOutcome.INCOMPLETE);
		}

	}

	private static String jsonString(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 16);
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
		return sb.toString();
	}

	@Override
	public boolean healthy() {
		return healthy.get() && !closed.get();
	}

	@Override
	public int purgeExpired() {
		long threshold = clock.millis() - retentionMs;
		int purged = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.jsonl")) {
			for (Path file : files) {
				try {
					if (Files.getLastModifiedTime(file).toMillis() < threshold) {
						Files.delete(file);
						purged++;
					}
				}
				catch (IOException ignored) {
					// 单个文件删除失败不中断清理。
				}
			}
		}
		catch (IOException scanFailed) {
			healthy.set(false);
		}
		return purged;
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		writer.shutdown();
		try {
			writer.awaitTermination(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 测试与运维可见：有界丢弃计数。 */
	public long droppedEvents() {
		return dropped.get();
	}

}
