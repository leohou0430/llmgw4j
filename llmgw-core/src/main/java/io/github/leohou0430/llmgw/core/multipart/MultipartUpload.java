package io.github.leohou0430.llmgw.core.multipart;

import io.github.leohou0430.llmgw.core.upstream.ByteStream;

import java.io.IOException;
import java.util.Map;

/**
 * T2.3/T2.4 streaming-upload coordinator (pure JDK). Scans a multipart client
 * body while writing it through to a bounded disk {@link Spool}, enforcing the
 * file / multipart / metadata {@link MultipartScanner.Limits limits}, then
 * exposes the captured form fields for governance and a byte-faithful replay
 * {@link ByteStream} for the upstream POST. The whole body never sits in heap.
 *
 * <p>Lifecycle: {@link #prepare} fully consumes and closes the client source.
 * On any scan/limit/quota failure it releases the spool before propagating, so
 * the caller only owns a spool on success. The returned {@link Prepared} releases
 * the spool exactly once — either when its replay stream terminates (normal,
 * cancel or error) or via {@link Prepared#close()} when governance rejects before
 * the body is ever forwarded.
 */
public final class MultipartUpload {

    private MultipartUpload() { }

    public static Prepared prepare(ByteStream.Source client, String boundary,
                                   MultipartScanner.Limits limits, SpoolManager spools) throws IOException {
        Spool spool = spools.create();
        boolean ok = false;
        try {
            MultipartScanner.Result result;
            try {
                result = MultipartScanner.scan(spool.tee(client), boundary, limits);
            } finally {
                client.close();
            }
            Prepared prepared = new Prepared(spool, result);
            ok = true;
            return prepared;
        } finally {
            if (!ok) {
                spool.close();
            }
        }
    }

    /** A scanned, spooled upload ready for governance then byte-faithful forwarding. */
    public static final class Prepared implements AutoCloseable {
        private final Spool spool;
        private final MultipartScanner.Result result;

        Prepared(Spool spool, MultipartScanner.Result result) {
            this.spool = spool;
            this.result = result;
        }

        public Map<String, String> fields() {
            return result.fields();
        }

        public int fileCount() {
            return result.fileCount();
        }

        public long fileBytes() {
            return result.fileBytes();
        }

        public long totalBytes() {
            return result.totalBytes();
        }

        public boolean trailingFieldAfterFile() {
            return result.trailingFieldAfterFile();
        }

        public long bodyLength() {
            return spool.size();
        }

        /** Byte-faithful replay for the upstream POST; termination releases the spool. */
        public ByteStream body() throws IOException {
            return ByteStream.of(spool.source());
        }

        @Override
        public void close() {
            spool.close();
        }
    }
}