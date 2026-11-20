package io.github.leohou0430.llmgw.core.upstream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.4 upload-direction seam: a reusable, cap-free {@link ByteStream.Source} over a
 * client {@link InputStream}. The servlet/reactive upload bridge pulls this into
 * {@code MultipartUpload.prepare}; bounded chunks keep memory flat, EOF is null, close is
 * idempotent and releases the underlying stream once, and a read failure propagates. Byte
 * limits are enforced downstream by the scanner and spool, so this transport adapter
 * deliberately carries no cumulative cap.
 */
class InputStreamSourceTest {

	/** InputStream that counts close() calls and can be told to fail reads. */
	private static final class TrackingStream extends InputStream {

		private final byte[] data;

		private int off;

		final AtomicInteger closes = new AtomicInteger();

		volatile boolean failReads;

		TrackingStream(byte[] data) {
			this.data = data;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int n = read(one, 0, 1);
			return n < 0 ? -1 : (one[0] & 0xff);
		}

		@Override
		public int read(byte[] b, int o, int len) throws IOException {
			if (failReads) {
				throw new IOException("read boom");
			}
			if (off >= data.length) {
				return -1;
			}
			int n = Math.min(len, data.length - off);
			System.arraycopy(data, off, b, o, n);
			off += n;
			return n;
		}

		@Override
		public void close() {
			closes.incrementAndGet();
		}

	}

	private static byte[] pattern(int n) {
		byte[] data = new byte[n];
		for (int i = 0; i < n; i++) {
			data[i] = (byte) (i % 251);
		}
		return data;
	}

	@Test
	void emitsBoundedChunksAndReachesEof() throws Exception {
		byte[] data = pattern(40000);
		ByteStream.Source source = ByteStream.inputStreamSource(new ByteArrayInputStream(data), 7);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int chunks = 0;
		for (;;) {
			ByteChunk chunk = source.next();
			if (chunk == null) {
				break;
			}
			ByteBuffer b = chunk.transfer();
			assertTrue(b.remaining() > 0 && b.remaining() <= 7, "each chunk is bounded and non-empty");
			byte[] tmp = new byte[b.remaining()];
			b.get(tmp);
			out.writeBytes(tmp);
			chunks++;
		}
		assertArrayEquals(data, out.toByteArray(), "reassembled bytes equal the input");
		assertEquals((40000 + 6) / 7, chunks, "one chunk per bounded read");
		assertNull(source.next(), "EOF stays null after the stream is drained");
		source.close();
	}

	@Test
	void streamsThroughByteStreamWithBoundedDemand() throws Exception {
		byte[] data = pattern(100000);
		TrackingStream in = new TrackingStream(data);
		ByteStream stream = ByteStream.of(ByteStream.inputStreamSource(in, 16 * 1024));
		List<ByteBuffer> received = new ArrayList<>();
		CountDownLatch done = new CountDownLatch(1);
		AtomicInteger errors = new AtomicInteger();
		stream.subscribe(new Flow.Subscriber<>() {
			Flow.Subscription s;

			@Override
			public void onSubscribe(Flow.Subscription s) {
				this.s = s;
				s.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ByteBuffer item) {
				received.add(item);
			}

			@Override
			public void onError(Throwable t) {
				errors.incrementAndGet();
				done.countDown();
			}

			@Override
			public void onComplete() {
				done.countDown();
			}
		});
		assertTrue(done.await(5, TimeUnit.SECONDS), "stream completes");
		assertEquals(0, errors.get());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (ByteBuffer b : received) {
			byte[] tmp = new byte[b.remaining()];
			b.get(tmp);
			out.writeBytes(tmp);
		}
		assertArrayEquals(data, out.toByteArray(), "publisher reassembles the input byte-for-byte");
		assertEquals(1, in.closes.get(), "normal completion releases the underlying stream once");
	}

	@Test
	void closeIsIdempotentAndClosesUnderlyingOnce() {
		TrackingStream in = new TrackingStream(pattern(10));
		ByteStream.Source source = ByteStream.inputStreamSource(in, 4);
		source.close();
		source.close();
		source.close();
		assertEquals(1, in.closes.get(), "close releases the underlying stream exactly once");
	}

	@Test
	void emptyStreamReturnsEofImmediately() throws Exception {
		ByteStream.Source source = ByteStream.inputStreamSource(new ByteArrayInputStream(new byte[0]), 8);
		assertNull(source.next(), "an empty body is immediate EOF");
		source.close();
	}

	@Test
	void readErrorPropagatesFromNext() {
		TrackingStream in = new TrackingStream(pattern(10));
		in.failReads = true;
		ByteStream.Source source = ByteStream.inputStreamSource(in, 4);
		assertThrows(IOException.class, source::next, "a read failure surfaces to the puller");
		source.close();
	}

	@Test
	void rejectsNullStreamAndNonPositiveChunkBytes() {
		assertThrows(NullPointerException.class, () -> ByteStream.inputStreamSource(null, 8));
		assertThrows(IllegalArgumentException.class,
				() -> ByteStream.inputStreamSource(new ByteArrayInputStream(new byte[0]), 0));
		assertThrows(IllegalArgumentException.class,
				() -> ByteStream.inputStreamSource(new ByteArrayInputStream(new byte[0]), -1));
	}

}