package io.github.leohou0430.llmgw.core.multipart;

import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * T2.5 streaming multipart/form-data scanner (pure JDK). Reads a pull
 * {@link ByteStream.Source} chunk by chunk and extracts the routing-relevant
 * form fields, counts file parts and measures sizes WITHOUT holding the whole
 * body: file content is counted and discarded, only a boundary-length overlap
 * window is retained between chunks so a delimiter split across chunks is still
 * found. Non-file field values are collected under a bounded metadata budget.
 *
 * <p>The body always forwards byte-for-byte elsewhere; this scanner only
 * pre-validates. It reports {@code trailingFieldAfterFile} so the engine knows
 * whether a routing field appears after the file part (the bounded-spool trigger
 * handled in T2.6) or every field precedes it (stream-through fast path).
 *
 * <p>Ownership: the caller owns the source lifecycle; {@code scan} does not close
 * it (a spooling tee must stay open for forwarding). On a limit violation a
 * {@link MultipartLimitException} is thrown and the caller closes the source.
 */
public final class MultipartScanner {

    /** Per-transfer bounds: single file, whole multipart, cumulative non-file metadata. */
    public record Limits(long maxFileBytes, long maxMultipartBytes, long maxMetadataBytes) {
        public static Limits defaults() {
            return new Limits(64L << 20, 66L << 20, 64L << 10);
        }
    }

    public record Result(Map<String, String> fields, int fileCount, long fileBytes,
                         long totalBytes, boolean trailingFieldAfterFile) { }

    private static final byte CR = 13;
    private static final byte LF = 10;
    private static final byte[] HEADER_SEP = {CR, LF, CR, LF};

    private MultipartScanner() { }

    public static Result scan(ByteStream.Source source, String boundary, Limits limits) throws IOException {
        if (boundary == null || boundary.isBlank()) {
            throw new IOException("missing multipart boundary");
        }
        return new Scanner(source, boundary, limits).run();
    }

    private interface Sink {
        void write(byte[] b, int off, int n) throws IOException;
    }

    private static final Sink DISCARD = (b, off, n) -> { };

    private static final class Count implements Sink {
        private final long max;
        private long count;
        Count(long max) { this.max = max; }
        @Override public void write(byte[] b, int off, int n) throws IOException {
            count += n;
            if (count > max) {
                throw new MultipartLimitException(MultipartLimitException.Limit.FILE,
                        "file part exceeds " + max + " bytes");
            }
        }
    }

    private static final class Collect implements Sink {
        private final long max;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Collect(long max) { this.max = Math.max(0, max); }
        @Override public void write(byte[] b, int off, int n) throws IOException {
            if (out.size() + n > max) {
                throw new MultipartLimitException(MultipartLimitException.Limit.METADATA,
                        "non-file metadata exceeds " + max + " bytes");
            }
            out.write(b, off, n);
        }
        int size() { return out.size(); }
        @Override public String toString() { return out.toString(StandardCharsets.UTF_8); }
    }

    private static final class Scanner {
        private final ByteStream.Source source;
        private final Limits limits;
        private final byte[] first;
        private final byte[] delim;

        private byte[] buf = new byte[8192];
        private int len;
        private boolean eof;
        private long totalPulled;

        private final Map<String, String> fields = new LinkedHashMap<>();
        private int fileCount;
        private long fileBytes;
        private long metadataBytes;
        private boolean seenFile;
        private boolean trailingFieldAfterFile;

        Scanner(ByteStream.Source source, String boundary, Limits limits) {
            this.source = source;
            this.limits = limits;
            this.first = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
            this.delim = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        }

        Result run() throws IOException {
            if (!readUntil(first, DISCARD)) {
                throw new IOException("multipart: opening boundary not found");
            }
            for (;;) {
                ensure(2);
                if (len >= 2 && buf[0] == '-' && buf[1] == '-') {
                    consume(2);
                    break;
                }
                if (len >= 2 && buf[0] == CR && buf[1] == LF) {
                    consume(2);
                } else {
                    throw new IOException("multipart: malformed boundary terminator");
                }
                Collect headers = new Collect(limits.maxMetadataBytes());
                if (!readUntil(HEADER_SEP, headers)) {
                    throw new IOException("multipart: part headers not terminated");
                }
                String[] disposition = parseDisposition(headers.toString());
                String name = disposition[0];
                boolean isFile = disposition[1] != null;
                if (isFile) {
                    fileCount++;
                    Count content = new Count(limits.maxFileBytes());
                    if (!readUntil(delim, content)) {
                        throw new IOException("multipart: file part not terminated");
                    }
                    fileBytes += content.count;
                    seenFile = true;
                } else {
                    Collect value = new Collect(limits.maxMetadataBytes() - metadataBytes);
                    if (!readUntil(delim, value)) {
                        throw new IOException("multipart: field part not terminated");
                    }
                    metadataBytes += value.size();
                    if (name != null && !name.isEmpty()) {
                        fields.put(name, value.toString().strip());
                    }
                    if (seenFile) {
                        trailingFieldAfterFile = true;
                    }
                }
            }
            // Account any epilogue after the closing boundary toward the total size.
            while (!eof) {
                pull();
                len = 0;
            }
            return new Result(Map.copyOf(fields), fileCount, fileBytes, totalPulled, trailingFieldAfterFile);
        }

        /**
         * Stream bytes to {@code sink} up to (excluding) {@code needle}, then consume
         * the needle. Returns false at EOF without the needle (remaining bytes still
         * sinked). Retains a needle-length overlap so a split delimiter is found.
         */
        private boolean readUntil(byte[] needle, Sink sink) throws IOException {
            int keep = needle.length - 1;
            for (;;) {
                int at = indexOf(needle);
                if (at >= 0) {
                    sink.write(buf, 0, at);
                    consume(at + needle.length);
                    return true;
                }
                if (eof) {
                    sink.write(buf, 0, len);
                    consume(len);
                    return false;
                }
                int emit = len - keep;
                if (emit > 0) {
                    sink.write(buf, 0, emit);
                    consume(emit);
                }
                pull();
            }
        }

        private void ensure(int n) throws IOException {
            while (len < n && !eof) {
                pull();
            }
        }

        private void pull() throws IOException {
            if (eof) {
                return;
            }
            ByteChunk chunk;
            try {
                chunk = source.next();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
            if (chunk == null) {
                eof = true;
                return;
            }
            ByteBuffer b = chunk.transfer();
            int n = b.remaining();
            totalPulled += n;
            if (totalPulled > limits.maxMultipartBytes()) {
                throw new MultipartLimitException(MultipartLimitException.Limit.MULTIPART,
                        "multipart body exceeds " + limits.maxMultipartBytes() + " bytes");
            }
            if (buf.length < len + n) {
                buf = Arrays.copyOf(buf, Math.max(len + n, buf.length * 2));
            }
            b.get(buf, len, n);
            len += n;
        }

        private void consume(int n) {
            if (n <= 0) {
                return;
            }
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }

        private int indexOf(byte[] needle) {
            if (needle.length == 0 || len < needle.length) {
                return -1;
            }
            outer:
            for (int i = 0; i <= len - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (buf[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        private static String[] parseDisposition(String headers) {
            String name = null;
            String filename = null;
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                if (!line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-disposition")) {
                    continue;
                }
                for (String token : line.substring(colon + 1).split(";")) {
                    String t = token.trim();
                    int eq = t.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String val = t.substring(eq + 1).trim();
                    if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }
                    if (key.equals("name")) {
                        name = val;
                    } else if (key.equals("filename")) {
                        filename = val;
                    }
                }
            }
            return new String[]{name, filename};
        }
    }
}