package io.github.leohou0430.llmgw.core.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T2.1 native byte-stream contract: a cold, single-subscription
 * {@link Flow.Publisher} of {@link ByteBuffer} with bounded demand, shared by
 * streaming upload bodies and download responses. Pure JDK (no HTTP, no Reactor).
 *
 * <p>Ownership: each {@link ByteChunk} the {@link Source} produces is transferred
 * out exactly once and handed to the subscriber; this stream keeps no reference
 * and never reuses an emitted buffer. The source is released exactly once on
 * normal completion, error, cancellation or {@link #close()} (idempotent).
 */
public final class ByteStream implements Flow.Publisher<ByteBuffer>, AutoCloseable {

    /** Pull-based chunk producer. {@code next()} returns null at EOF and transfers ownership out. */
    public interface Source extends AutoCloseable {
        ByteChunk next() throws Exception;
        @Override void close();
    }

    private static final Flow.Subscription NOOP = new Flow.Subscription() {
        @Override public void request(long n) { }
        @Override public void cancel() { }
    };

    private final Source source;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();

    private ByteStream(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public static ByteStream of(Source source) {
        return new ByteStream(source);
    }

    /**
     * Cold stream over an in-memory array, emitted in bounded chunks (no copy:
     * each chunk wraps a slice). Interim bridge for callers that still hold a
     * whole byte[]; the streaming reading paths replace it with a spool source.
     */
    public static ByteStream ofByteArray(byte[] bytes) {
        return of(new ByteArraySource(bytes));
    }

    /**
     * T2.4 upload-direction transport: a cold pull {@link Source} over a client
     * {@link InputStream}, emitted in bounded chunks (a fresh buffer per read,
     * never reused). EOF is null; {@link Source#close()} releases the underlying
     * stream exactly once. No cumulative byte cap here — the multipart scanner
     * and spool enforce limits downstream. The servlet/reactive upload bridge
     * feeds this into {@code MultipartUpload.prepare}.
     */
    public static Source inputStreamSource(InputStream in, int chunkBytes) {
        Objects.requireNonNull(in, "in");
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("chunkBytes must be positive: " + chunkBytes);
        }
        return new InputStreamSource(in, chunkBytes);
    }

    private static final class InputStreamSource implements Source {
        private final InputStream in;
        private final int chunkBytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        InputStreamSource(InputStream in, int chunkBytes) {
            this.in = in;
            this.chunkBytes = chunkBytes;
        }

        @Override
        public ByteChunk next() throws IOException {
            byte[] buf = new byte[chunkBytes];
            int n = in.read(buf);
            if (n < 0) {
                return null;
            }
            return new ByteChunk(ByteBuffer.wrap(buf, 0, n));
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // releasing the client body; nothing actionable on close failure
                }
            }
        }
    }

    private static final class ByteArraySource implements Source {
        private static final int CHUNK_BYTES = 16 * 1024;
        private final byte[] bytes;
        private int offset;

        ByteArraySource(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public ByteChunk next() {
            if (offset >= bytes.length) {
                return null;
            }
            int len = Math.min(CHUNK_BYTES, bytes.length - offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, len);
            offset += len;
            return new ByteChunk(buffer);
        }

        @Override
        public void close() { }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(NOOP);
            subscriber.onError(new IllegalStateException("ByteStream supports a single subscription"));
            return;
        }
        subscriber.onSubscribe(new Drain(subscriber));
    }

    /** Release the source exactly once and stop emission. Safe to call repeatedly. */
    @Override
    public void close() {
        cancelled.set(true);
        release();
    }

    private void release() {
        if (closed.compareAndSet(false, true)) {
            source.close();
        }
    }

    private final class Drain implements Flow.Subscription {
        private final Flow.Subscriber<? super ByteBuffer> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicInteger wip = new AtomicInteger();
        // At most one chunk of lookahead, pulled only to discover EOF at an exact
        // demand boundary; owned by this drain (not yet transferred) until emitted.
        private ByteChunk pending;

        Drain(Flow.Subscriber<? super ByteBuffer> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (cancelled.get() || terminated.get()) {
                return;
            }
            if (n <= 0) {
                fail(new IllegalArgumentException("non-positive demand: " + n));
                return;
            }
            demand.accumulateAndGet(n, (cur, add) -> {
                long sum = cur + add;
                return sum < 0 ? Long.MAX_VALUE : sum;
            });
            drain();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            release();
        }

        private void drain() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            for (;;) {
                if (!terminated.get() && !cancelled.get()) {
                    long d = demand.get();
                    long emitted = 0;
                    while (emitted < d) {
                        if (cancelled.get()) {
                            break;
                        }
                        ByteChunk chunk = pending;
                        if (chunk != null) {
                            pending = null;
                        } else {
                            try {
                                chunk = source.next();
                            } catch (Throwable t) {
                                fail(t);
                                break;
                            }
                        }
                        if (chunk == null) {
                            complete();
                            break;
                        }
                        ByteBuffer buffer;
                        try {
                            buffer = chunk.transfer();
                        } catch (Throwable t) {
                            fail(t);
                            break;
                        }
                        subscriber.onNext(buffer);
                        emitted++;
                    }
                    if (emitted > 0) {
                        demand.addAndGet(-emitted);
                    }
                    // Demand satisfied exactly: probe once so a source that ends on
                    // this boundary still completes (a body publisher must signal EOF
                    // even when the consumer requested exactly the remaining bytes).
                    if (emitted == d && pending == null && !terminated.get() && !cancelled.get()) {
                        ByteChunk probe;
                        try {
                            probe = source.next();
                        } catch (Throwable t) {
                            fail(t);
                            probe = null;
                        }
                        if (probe == null) {
                            complete();
                        } else {
                            pending = probe;
                        }
                    }
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
        }

        private void complete() {
            if (terminated.compareAndSet(false, true)) {
                release();
                subscriber.onComplete();
            }
        }

        private void fail(Throwable t) {
            if (terminated.compareAndSet(false, true)) {
                release();
                subscriber.onError(t);
            }
        }
    }
}