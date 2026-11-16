package io.github.leohou0430.llmgw.core.multipart;

import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.3/T2.4 seam: the pure-JDK streaming-upload coordinator. It scans a multipart
 * client body while writing it through to a bounded disk spool, enforces the
 * file/multipart/metadata limits, exposes the captured fields for governance and
 * replays the body byte-for-byte for the upstream POST. Every terminal path must
 * release the spool (file deleted + quota returned) and close the client source.
 */
class MultipartUploadTest {

    private static final String B = "----llmgwBoundary7ma4d9";
    private static final MultipartScanner.Limits BIG =
            new MultipartScanner.Limits(64 << 20, 66 << 20, 64 << 10);

    @TempDir
    Path temp;

    private SpoolManager manager(Path dir) throws Exception {
        return new SpoolManager(new SpoolManager.Config(dir, 66 << 20, 264 << 20, 0));
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

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    /** model + purpose fields first, then one file part. */
    private static byte[] body(byte[] fileContent) {
        return join(
                ascii("--" + B + "\r\n"),
                ascii("Content-Disposition: form-data; name=\"model\"\r\n\r\n"),
                ascii("gpt-x"),
                ascii("\r\n--" + B + "\r\n"),
                ascii("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"),
                ascii("assistants"),
                ascii("\r\n--" + B + "\r\n"),
                ascii("Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n"),
                fileContent,
                ascii("\r\n--" + B + "--\r\n"));
    }

    /** A client source emitting tiny chunks (split boundaries) and counting close. */
    private static final class Client implements ByteStream.Source {
        private final byte[] data;
        private final int chunk;
        private int off;
        final AtomicInteger closes = new AtomicInteger();
        Client(byte[] data, int chunk) { this.data = data; this.chunk = chunk; }
        @Override public ByteChunk next() {
            if (off >= data.length) {
                return null;
            }
            int n = Math.min(chunk, data.length - off);
            ByteBuffer b = ByteBuffer.wrap(data, off, n);
            off += n;
            return new ByteChunk(b);
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static byte[] drain(ByteStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger error = new AtomicInteger();
        stream.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) { sub = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] a = new byte[item.remaining()];
                item.get(a);
                out.writeBytes(a);
            }
            @Override public void onError(Throwable t) { error.incrementAndGet(); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "replay must terminate");
        assertEquals(0, error.get(), "replay must not error");
        return out.toByteArray();
    }

    private static long fileCount(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void scansSpoolsAndReplaysByteFaithful() throws Exception {
        Path dir = temp.resolve("a");
        try (SpoolManager mgr = manager(dir)) {
            byte[] whole = body(pattern(40000));
            Client client = new Client(whole, 7);
            MultipartUpload.Prepared prepared = MultipartUpload.prepare(client, B, BIG, mgr);
            assertEquals("gpt-x", prepared.fields().get("model"));
            assertEquals("assistants", prepared.fields().get("purpose"));
            assertEquals(1, prepared.fileCount());
            assertEquals(40000, prepared.fileBytes());
            assertFalse(prepared.trailingFieldAfterFile());
            assertEquals(whole.length, prepared.bodyLength());
            assertEquals(whole.length, mgr.totalBytes(), "body is reserved on disk, not heap");
            assertEquals(1, client.closes.get(), "client source closed once after scanning");
            assertArrayEquals(whole, drain(prepared.body()), "replay must be byte-for-byte");
            assertEquals(0, mgr.totalBytes(), "replay completion releases the quota");
            assertEquals(0, fileCount(dir), "replay completion deletes the temp file");
        }
    }

    @Test
    void fileLimitRejectsAndCleansSpool() throws Exception {
        Path dir = temp.resolve("b");
        try (SpoolManager mgr = manager(dir)) {
            Client client = new Client(body(pattern(200)), 7);
            var limits = new MultipartScanner.Limits(100, 66 << 20, 64 << 10);
            MultipartLimitException ex = assertThrows(MultipartLimitException.class,
                    () -> MultipartUpload.prepare(client, B, limits, mgr));
            assertEquals(MultipartLimitException.Limit.FILE, ex.limit());
            assertEquals(0, mgr.totalBytes());
            assertEquals(0, fileCount(dir));
            assertEquals(1, client.closes.get());
        }
    }

    @Test
    void multipartLimitRejectsAndCleansSpool() throws Exception {
        Path dir = temp.resolve("c");
        try (SpoolManager mgr = manager(dir)) {
            Client client = new Client(body(pattern(1000)), 7);
            var limits = new MultipartScanner.Limits(64 << 20, 50, 64 << 10);
            MultipartLimitException ex = assertThrows(MultipartLimitException.class,
                    () -> MultipartUpload.prepare(client, B, limits, mgr));
            assertEquals(MultipartLimitException.Limit.MULTIPART, ex.limit());
            assertEquals(0, mgr.totalBytes());
            assertEquals(0, fileCount(dir));
            assertEquals(1, client.closes.get());
        }
    }

    @Test
    void metadataLimitRejectsAndCleansSpool() throws Exception {
        Path dir = temp.resolve("d");
        try (SpoolManager mgr = manager(dir)) {
            byte[] whole = join(
                    ascii("--" + B + "\r\n"),
                    ascii("Content-Disposition: form-data; name=\"x\"\r\n\r\n"),
                    new byte[100],
                    ascii("\r\n--" + B + "--\r\n"));
            Client client = new Client(whole, 7);
            var limits = new MultipartScanner.Limits(64 << 20, 66 << 20, 64);
            MultipartLimitException ex = assertThrows(MultipartLimitException.class,
                    () -> MultipartUpload.prepare(client, B, limits, mgr));
            assertEquals(MultipartLimitException.Limit.METADATA, ex.limit());
            assertEquals(0, mgr.totalBytes());
            assertEquals(0, fileCount(dir));
            assertEquals(1, client.closes.get());
        }
    }

    @Test
    void trailingFieldAfterFileIsReportedAndReplayed() throws Exception {
        Path dir = temp.resolve("e");
        try (SpoolManager mgr = manager(dir)) {
            byte[] whole = join(
                    ascii("--" + B + "\r\n"),
                    ascii("Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n\r\n"),
                    pattern(10),
                    ascii("\r\n--" + B + "\r\n"),
                    ascii("Content-Disposition: form-data; name=\"model\"\r\n\r\n"),
                    ascii("gpt-x"),
                    ascii("\r\n--" + B + "--\r\n"));
            Client client = new Client(whole, 7);
            MultipartUpload.Prepared prepared = MultipartUpload.prepare(client, B, BIG, mgr);
            assertTrue(prepared.trailingFieldAfterFile());
            assertEquals("gpt-x", prepared.fields().get("model"));
            assertArrayEquals(whole, drain(prepared.body()));
            assertEquals(0, mgr.totalBytes());
        }
    }

    @Test
    void closeWithoutConsumingBodyReleasesSpool() throws Exception {
        Path dir = temp.resolve("f");
        try (SpoolManager mgr = manager(dir)) {
            Client client = new Client(body(pattern(5000)), 7);
            MultipartUpload.Prepared prepared = MultipartUpload.prepare(client, B, BIG, mgr);
            assertEquals(5000 + body(new byte[0]).length, mgr.totalBytes());
            prepared.close();
            assertEquals(0, mgr.totalBytes(), "governance-reject path must release the spool");
            assertEquals(0, fileCount(dir));
            prepared.close();
            assertEquals(0, mgr.totalBytes(), "close is idempotent");
        }
    }

    @Test
    void spoolQuotaRejectionCleansUp() throws Exception {
        Path dir = temp.resolve("g");
        try (SpoolManager mgr = new SpoolManager(new SpoolManager.Config(dir, 66 << 20, 50, 0))) {
            Client client = new Client(body(pattern(1000)), 7);
            SpoolLimitException ex = assertThrows(SpoolLimitException.class,
                    () -> MultipartUpload.prepare(client, B, BIG, mgr));
            assertEquals(SpoolLimitException.Reason.TOTAL, ex.reason());
            assertEquals(0, mgr.totalBytes());
            assertEquals(0, fileCount(dir));
            assertEquals(1, client.closes.get());
        }
    }

    @Test
    void instanceIsolationAcrossManagers() throws Exception {
        Path dirA = temp.resolve("isoA");
        Path dirB = temp.resolve("isoB");
        try (SpoolManager a = manager(dirA); SpoolManager b = manager(dirB)) {
            MultipartUpload.Prepared pa = MultipartUpload.prepare(new Client(body(pattern(3000)), 7), B, BIG, a);
            MultipartUpload.Prepared pb = MultipartUpload.prepare(new Client(body(pattern(4000)), 7), B, BIG, b);
            long expectA = 3000 + body(new byte[0]).length;
            long expectB = 4000 + body(new byte[0]).length;
            assertEquals(expectA, a.totalBytes());
            assertEquals(expectB, b.totalBytes());
            pa.close();
            assertEquals(0, a.totalBytes());
            assertEquals(expectB, b.totalBytes(), "closing one instance must not touch the other");
            assertEquals(0, fileCount(dirA));
            assertEquals(1, fileCount(dirB));
            pb.close();
            assertEquals(0, b.totalBytes());
        }
    }
}