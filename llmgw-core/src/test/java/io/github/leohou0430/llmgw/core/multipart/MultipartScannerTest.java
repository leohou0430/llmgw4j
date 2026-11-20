package io.github.leohou0430.llmgw.core.multipart;

import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.5 seam: a pure-JDK streaming multipart scanner over a pull source. Bodies are
 * emitted in tiny chunks so every boundary/header separator splits across chunks; the
 * scanner must still extract fields, count files, measure sizes and enforce bounds
 * without holding the whole body.
 */
class MultipartScannerTest {

	private static final String B = "----llmgwBoundary7ma4d9";

	private static final MultipartScanner.Limits BIG = new MultipartScanner.Limits(64 << 20, 66 << 20, 64 << 10);

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

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] join(byte[]... segments) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] s : segments) {
			out.writeBytes(s);
		}
		return out.toByteArray();
	}

	/** Fields first, then one file part — the stream-through (no-spool) shape. */
	private static byte[] body(byte[] fileContent) {
		return join(ascii("--" + B + "\r\n"), ascii("Content-Disposition: form-data; name=\"model\"\r\n\r\n"),
				ascii("gpt-x"), ascii("\r\n--" + B + "\r\n"),
				ascii("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"), ascii("assistants"),
				ascii("\r\n--" + B + "\r\n"),
				ascii("Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
						+ "Content-Type: application/octet-stream\r\n\r\n"),
				fileContent, ascii("\r\n--" + B + "--\r\n"));
	}

	@Test
	void extractsFieldsAndCountsFilesAcrossSplitBoundaries() throws Exception {
		byte[] fileContent = new byte[1000];
		for (int i = 0; i < fileContent.length; i++) {
			fileContent[i] = (byte) (i % 251);
		}
		byte[] whole = body(fileContent);
		MultipartScanner.Result r = MultipartScanner.scan(chunked(whole, 7), B, BIG);
		assertEquals("gpt-x", r.fields().get("model"));
		assertEquals("assistants", r.fields().get("purpose"));
		assertEquals(1, r.fileCount());
		assertEquals(1000, r.fileBytes());
		assertEquals(whole.length, r.totalBytes());
		assertFalse(r.trailingFieldAfterFile(), "all fields precede the file part");
	}

	@Test
	void rejectsOversizedFile() {
		byte[] whole = body(new byte[200]);
		MultipartScanner.Limits limits = new MultipartScanner.Limits(100, 66 << 20, 64 << 10);
		MultipartLimitException ex = assertThrows(MultipartLimitException.class,
				() -> MultipartScanner.scan(chunked(whole, 7), B, limits));
		assertEquals(MultipartLimitException.Limit.FILE, ex.limit());
	}

	@Test
	void rejectsOversizedMultipart() {
		byte[] whole = body(new byte[1000]);
		MultipartScanner.Limits limits = new MultipartScanner.Limits(64 << 20, 50, 64 << 10);
		MultipartLimitException ex = assertThrows(MultipartLimitException.class,
				() -> MultipartScanner.scan(chunked(whole, 7), B, limits));
		assertEquals(MultipartLimitException.Limit.MULTIPART, ex.limit());
	}

	@Test
	void rejectsOversizedMetadata() {
		byte[] whole = join(ascii("--" + B + "\r\n"), ascii("Content-Disposition: form-data; name=\"x\"\r\n\r\n"),
				new byte[100], ascii("\r\n--" + B + "--\r\n"));
		MultipartScanner.Limits limits = new MultipartScanner.Limits(64 << 20, 66 << 20, 64);
		MultipartLimitException ex = assertThrows(MultipartLimitException.class,
				() -> MultipartScanner.scan(chunked(whole, 7), B, limits));
		assertEquals(MultipartLimitException.Limit.METADATA, ex.limit());
	}

	@Test
	void detectsTrailingFieldAfterFile() throws Exception {
		byte[] whole = join(ascii("--" + B + "\r\n"),
				ascii("Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n\r\n"), new byte[10],
				ascii("\r\n--" + B + "\r\n"), ascii("Content-Disposition: form-data; name=\"model\"\r\n\r\n"),
				ascii("gpt-x"), ascii("\r\n--" + B + "--\r\n"));
		MultipartScanner.Result r = MultipartScanner.scan(chunked(whole, 7), B, BIG);
		assertTrue(r.trailingFieldAfterFile(), "a routing field after the file forces spooling");
		assertEquals("gpt-x", r.fields().get("model"));
		assertEquals(1, r.fileCount());
		assertEquals(10, r.fileBytes());
	}

	@Test
	void countsMultipleFiles() throws Exception {
		byte[] whole = join(ascii("--" + B + "\r\n"),
				ascii("Content-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\n"), new byte[10],
				ascii("\r\n--" + B + "\r\n"),
				ascii("Content-Disposition: form-data; name=\"file\"; filename=\"b\"\r\n\r\n"), new byte[20],
				ascii("\r\n--" + B + "--\r\n"));
		MultipartScanner.Result r = MultipartScanner.scan(chunked(whole, 7), B, BIG);
		assertEquals(2, r.fileCount());
		assertEquals(30, r.fileBytes());
		assertFalse(r.trailingFieldAfterFile());
	}

}