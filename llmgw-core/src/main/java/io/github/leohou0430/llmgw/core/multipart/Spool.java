package io.github.leohou0430.llmgw.core.multipart;

import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T2.6 one bounded on-disk transfer. Bytes are written under a per-transfer cap
 * and the manager's per-process quota; the spooled body can be replayed as a
 * {@link ByteStream.Source} or written through while forwarding upstream
 * ({@link #tee}). Every terminal path — {@link #close()}, replay completion,
 * cancel or error — deletes the temp file and releases the reserved quota
 * exactly once (CAS-guarded, idempotent).
 */
public final class Spool implements AutoCloseable {

    private static final int CHUNK_BYTES = 16 * 1024;

    private final SpoolManager manager;
    private final Path path;
    private final OutputStream out;
    private final AtomicLong size = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    Spool(SpoolManager manager, Path path) throws IOException {
        this.manager = manager;
        this.path = path;
        this.out = Files.newOutputStream(path);
    }

    public Path path() {
        return path;
    }

    public long size() {
        return size.get();
    }

    /** Append bytes, enforcing the per-transfer cap then reserving process quota. */
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed.get()) {
            throw new IOException("spool closed");
        }
        if (size.get() + len > manager.config().maxTransferBytes()) {
            throw new SpoolLimitException(SpoolLimitException.Reason.TRANSFER,
                    "transfer cap " + manager.config().maxTransferBytes() + " exceeded");
        }
        manager.reserve(len);
        try {
            out.write(b, off, len);
            size.addAndGet(len);
        } catch (IOException e) {
            manager.release(len);
            throw e;
        }
    }

    /** Replay the spooled body in bounded chunks; completion closes this spool. */
    public ByteStream.Source source() throws IOException {
        out.flush();
        InputStream in = Files.newInputStream(path);
        return new ByteStream.Source() {
            @Override public ByteChunk next() throws IOException {
                byte[] buf = new byte[CHUNK_BYTES];
                int n = in.read(buf);
                if (n < 0) {
                    return null;
                }
                return new ByteChunk(ByteBuffer.wrap(buf, 0, n));
            }
            @Override public void close() {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Closing the replay handle is best-effort; the spool still deletes.
                }
                Spool.this.close();
            }
        };
    }

    /**
     * Write-through: pull each upstream chunk, persist a copy to this spool and
     * forward the original buffer downstream unchanged. Closing (normal, cancel
     * or error) closes upstream and this spool.
     */
    public ByteStream.Source tee(ByteStream.Source upstream) {
        return new ByteStream.Source() {
            @Override public ByteChunk next() throws Exception {
                ByteChunk chunk = upstream.next();
                if (chunk == null) {
                    return null;
                }
                ByteBuffer b = chunk.transfer();
                ByteBuffer copy = b.duplicate();
                byte[] tmp = new byte[copy.remaining()];
                copy.get(tmp);
                write(tmp, 0, tmp.length);
                return new ByteChunk(b);
            }
            @Override public void close() {
                upstream.close();
                Spool.this.close();
            }
        };
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                out.close();
            } catch (IOException ignored) {
                // Best-effort handle close; the file is still deleted below.
            }
            manager.release(size.get());
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best-effort temp-file cleanup.
            }
        }
    }
}