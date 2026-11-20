package io.github.leohou0430.llmgw.core.multipart;

import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.6 seam: a bounded on-disk spool with a per-process quota and disk safety margin.
 * Every lifecycle path (normal close, cancel, error) must delete the temp file and
 * release the reserved quota exactly once; two managers over independent directories must
 * not share state. Pure JDK, no engine.
 */
class SpoolManagerTest {

	@TempDir
	Path temp;

	private SpoolManager.Config config(Path dir) {
		return new SpoolManager.Config(dir, 66 << 20, 264 << 20, 0);
	}

	private static byte[] pattern(int n) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) {
			b[i] = (byte) (i % 251);
		}
		return b;
	}

	private static ByteStream.Source chunked(byte[] data, int chunkSize) {
		return new ByteStream.Source() {
			private int off;

			@Override
			public ByteChunk next() {
				if (off >= data.length) {
					return null;
				}
				int n = Math.min(chunkSize, data.length - off);
				ByteBuffer b = ByteBuffer.wrap(data, off, n);
				off += n;
				return new ByteChunk(b);
			}

			@Override
			public void close() {
			}
		};
	}

	/** Drains a ByteStream synchronously, recording every chunk and terminal signal. */
	private static final class Recorder implements Flow.Subscriber<ByteBuffer> {

		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		final List<byte[]> chunks = new ArrayList<>();

		final CountDownLatch done = new CountDownLatch(1);

		volatile Throwable error;

		@Override
		public void onSubscribe(Flow.Subscription s) {
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(ByteBuffer b) {
			byte[] a = new byte[b.remaining()];
			b.get(a);
			chunks.add(a);
			out.writeBytes(a);
		}

		@Override
		public void onError(Throwable t) {
			error = t;
			done.countDown();
		}

		@Override
		public void onComplete() {
			done.countDown();
		}

	}

	private static Recorder drain(ByteStream stream) throws InterruptedException {
		Recorder r = new Recorder();
		stream.subscribe(r);
		assertTrue(r.done.await(5, TimeUnit.SECONDS), "stream did not terminate");
		return r;
	}

	public static void main(String[] args) throws Exception {
		var manager = new SpoolManager(new SpoolManager.Config(Path.of(args[0]), 1000, 1000, 0));
		var upload = manager.create();
		upload.write(new byte[100], 0, 100);
		Files.writeString(Path.of(args[0]).resolve("ready"), upload.path().toString());
		new CountDownLatch(1).await();
	}

	@Test
	void forcedProcessDeathReleasesOwnershipAndRestartReclaimsRawUpload() throws Exception {
		Path directory = temp.resolve("killed");
		var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("java.class.path"), SpoolManagerTest.class.getName(), directory.toString())
			.redirectErrorStream(true)
			.redirectOutput(temp.resolve("child.log").toFile())
			.start();
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (!Files.exists(directory.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			assertTrue(Files.exists(directory.resolve("ready")), "child must create upload before kill");
			Path raw = Path.of(Files.readString(directory.resolve("ready")));
			assertEquals(100, Files.size(raw));
			child.destroyForcibly();
			assertTrue(child.waitFor(5, TimeUnit.SECONDS));
			assertTrue(Files.exists(raw));
			try (var restarted = new SpoolManager(config(directory))) {
				assertFalse(Files.exists(raw));
			}
		}
		finally {
			child.destroyForcibly();
			child.waitFor(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void simultaneousOwnersCannotDeleteLiveUploads() throws Exception {
		Path directory = temp.resolve("owned");
		try (var first = new SpoolManager(config(directory))) {
			var upload = first.create();
			upload.write(pattern(20), 0, 20);
			assertThrows(IOException.class, () -> new SpoolManager(config(directory)));
			assertTrue(Files.exists(upload.path()));
		}
		Files.writeString(directory.resolve("orphan.tmp"), "crashed upload");
		try (var restarted = new SpoolManager(config(directory))) {
			assertFalse(Files.exists(directory.resolve("orphan.tmp")));
		}
	}

	@Test
	void defaultsHavePlanLimits() {
		SpoolManager.Config c = SpoolManager.Config.defaults(temp);
		assertEquals(66 << 20, c.maxTransferBytes());
		assertEquals(264 << 20, c.maxTotalBytes());
		assertEquals(64 << 20, c.diskSafetyMarginBytes());
	}

	@Test
	void writeAndReplayInBoundedChunks() throws Exception {
		byte[] data = pattern(40000);
		try (SpoolManager m = new SpoolManager(config(temp.resolve("a")))) {
			Spool spool = m.create();
			spool.write(data, 0, data.length);
			assertEquals(data.length, spool.size());
			Recorder r = drain(ByteStream.of(spool.source()));
			assertArrayEquals(data, r.out.toByteArray());
			assertTrue(r.chunks.size() >= 2, "replay must be chunked, not one whole array");
			assertFalse(Files.exists(spool.path()), "replay completion cleans the spool");
			assertEquals(0, m.totalBytes());
		}
	}

	@Test
	void closeDeletesFileAndReleasesQuota() throws Exception {
		try (SpoolManager m = new SpoolManager(config(temp.resolve("a")))) {
			Spool spool = m.create();
			spool.write(pattern(100), 0, 100);
			assertTrue(Files.exists(spool.path()));
			assertEquals(100, m.totalBytes());
			spool.close();
			assertFalse(Files.exists(spool.path()));
			assertEquals(0, m.totalBytes());
		}
	}

	@Test
	void closeIsIdempotentAndReleasesOnce() throws Exception {
		try (SpoolManager m = new SpoolManager(config(temp.resolve("a")))) {
			Spool s1 = m.create();
			Spool s2 = m.create();
			s1.write(pattern(50), 0, 50);
			s2.write(pattern(30), 0, 30);
			assertEquals(80, m.totalBytes());
			s1.close();
			s1.close();
			assertEquals(30, m.totalBytes(), "quota released exactly once, never negative");
			s2.close();
			assertEquals(0, m.totalBytes());
		}
	}

	@Test
	void perTransferQuotaReject() throws Exception {
		SpoolManager.Config c = new SpoolManager.Config(temp.resolve("a"), 100, 264 << 20, 0);
		try (SpoolManager m = new SpoolManager(c)) {
			Spool spool = m.create();
			SpoolLimitException ex = assertThrows(SpoolLimitException.class, () -> spool.write(pattern(200), 0, 200));
			assertEquals(SpoolLimitException.Reason.TRANSFER, ex.reason());
			assertEquals(0, m.totalBytes(), "rejected write reserves nothing");
			spool.close();
		}
	}

	@Test
	void totalQuotaRejectRollsBack() throws Exception {
		SpoolManager.Config c = new SpoolManager.Config(temp.resolve("a"), 66 << 20, 100, 0);
		try (SpoolManager m = new SpoolManager(c)) {
			Spool s1 = m.create();
			s1.write(pattern(60), 0, 60);
			Spool s2 = m.create();
			SpoolLimitException ex = assertThrows(SpoolLimitException.class, () -> s2.write(pattern(60), 0, 60));
			assertEquals(SpoolLimitException.Reason.TOTAL, ex.reason());
			assertEquals(60, m.totalBytes(), "failed reservation rolled back, s1 intact");
			s1.close();
			s2.close();
			assertEquals(0, m.totalBytes());
		}
	}

	@Test
	void diskMarginReject() throws Exception {
		SpoolManager.Config c = new SpoolManager.Config(temp.resolve("a"), 66 << 20, 264 << 20, Long.MAX_VALUE);
		try (SpoolManager m = new SpoolManager(c)) {
			SpoolLimitException ex = assertThrows(SpoolLimitException.class, m::create);
			assertEquals(SpoolLimitException.Reason.DISK, ex.reason());
		}
	}

	@Test
	void startupLeftoverCleanup() throws Exception {
		Path dir = temp.resolve("a");
		Files.createDirectories(dir);
		Path leftover = dir.resolve("stale.tmp");
		Files.writeString(leftover, "old");
		try (SpoolManager m = new SpoolManager(config(dir))) {
			assertFalse(Files.exists(leftover), "constructor must clear prior-run leftovers");
		}
	}

	@Test
	void instanceIsolationAcrossDirectories() throws Exception {
		Path dirA = temp.resolve("a");
		Path dirB = temp.resolve("b");
		SpoolManager a = new SpoolManager(config(dirA));
		SpoolManager b = new SpoolManager(config(dirB));
		Spool sa = a.create();
		Spool sb = b.create();
		sa.write(pattern(40), 0, 40);
		sb.write(pattern(70), 0, 70);
		assertEquals(40, a.totalBytes());
		assertEquals(70, b.totalBytes());
		a.close();
		assertFalse(Files.exists(sa.path()));
		assertTrue(Files.exists(sb.path()), "closing one manager must not touch the other");
		assertEquals(70, b.totalBytes());
		sb.close();
		b.close();
		assertEquals(0, b.totalBytes());
	}

	@Test
	void teeWritesThroughAndCleansOnClose() throws Exception {
		byte[] data = pattern(5000);
		try (SpoolManager m = new SpoolManager(config(temp.resolve("a")))) {
			Spool spool = m.create();
			ByteStream.Source tee = spool.tee(chunked(data, 512));
			ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
			ByteChunk chunk;
			while ((chunk = tee.next()) != null) {
				ByteBuffer buf = chunk.transfer();
				byte[] a = new byte[buf.remaining()];
				buf.get(a);
				forwarded.writeBytes(a);
				assertEquals(forwarded.size(), spool.size(), "each forwarded chunk is persisted");
			}
			assertArrayEquals(data, forwarded.toByteArray());
			assertEquals(data.length, spool.size());
			assertTrue(Files.exists(spool.path()));
			tee.close();
			assertFalse(Files.exists(spool.path()));
			assertEquals(0, m.totalBytes());
		}
	}

	@Test
	void cancelCleansSpool() throws Exception {
		byte[] data = pattern(5000);
		try (SpoolManager m = new SpoolManager(config(temp.resolve("a")))) {
			Spool spool = m.create();
			ByteStream stream = ByteStream.of(spool.tee(chunked(data, 512)));
			CountDownLatch got = new CountDownLatch(1);
			Flow.Subscription[] sub = new Flow.Subscription[1];
			stream.subscribe(new Flow.Subscriber<>() {
				@Override
				public void onSubscribe(Flow.Subscription s) {
					sub[0] = s;
					s.request(1);
				}

				@Override
				public void onNext(ByteBuffer b) {
					got.countDown();
				}

				@Override
				public void onError(Throwable t) {
				}

				@Override
				public void onComplete() {
				}
			});
			assertTrue(got.await(5, TimeUnit.SECONDS));
			assertTrue(spool.size() > 0, "first chunk was spooled");
			sub[0].cancel();
			assertFalse(Files.exists(spool.path()), "cancel deletes the spool");
			assertEquals(0, m.totalBytes(), "cancel releases the quota");
		}
	}

	@Test
	void upstreamErrorCleansSpool() throws Exception {
		ByteStream.Source failing = new ByteStream.Source() {
			private int calls;

			@Override
			public ByteChunk next() throws IOException {
				if (calls++ == 0) {
					return new ByteChunk(ByteBuffer.wrap(pattern(100)));
				}
				throw new IOException("upstream blew up");
			}

			@Override
			public void close() {
			}
		};
		try (SpoolManager m = new SpoolManager(config(temp.resolve("a")))) {
			Spool spool = m.create();
			Recorder r = drain(ByteStream.of(spool.tee(failing)));
			assertTrue(r.error instanceof IOException);
			assertFalse(Files.exists(spool.path()), "error deletes the spool");
			assertEquals(0, m.totalBytes(), "error releases the quota");
		}
	}

}
