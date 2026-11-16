package io.github.leohou0430.llmgw.core.upstream;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.1 native byte-stream contract seam (pure JDK, no HTTP): subscribe-once,
 * bounded demand, cancel releases the source, close is idempotent, and chunk
 * ownership transfers exactly once (the original reference is unusable after).
 */
class ByteStreamTest {

    /** Pull source over a fixed list of chunks; counts close() calls and retains the envelopes. */
    private static final class ListSource implements ByteStream.Source {
        private final List<ByteChunk> chunks;
        private int cursor;
        final AtomicInteger closeCount = new AtomicInteger();
        final List<ByteChunk> handedOut = new ArrayList<>();

        ListSource(List<ByteBuffer> buffers) {
            this.chunks = new ArrayList<>();
            for (ByteBuffer b : buffers) chunks.add(new ByteChunk(b));
        }

        @Override public ByteChunk next() {
            if (cursor >= chunks.size()) return null;
            ByteChunk c = chunks.get(cursor++);
            handedOut.add(c);
            return c;
        }

        @Override public void close() { closeCount.incrementAndGet(); }
    }

    /** Recording subscriber with explicit, manual demand. */
    private static final class Recorder implements Flow.Subscriber<ByteBuffer> {
        final List<ByteBuffer> received = new ArrayList<>();
        Flow.Subscription subscription;
        volatile boolean complete;
        volatile Throwable error;
        Runnable onFirstChunk;

        @Override public void onSubscribe(Flow.Subscription s) { this.subscription = s; }
        @Override public void onNext(ByteBuffer item) {
            received.add(item);
            if (received.size() == 1 && onFirstChunk != null) onFirstChunk.run();
        }
        @Override public void onError(Throwable t) { this.error = t; }
        @Override public void onComplete() { this.complete = true; }
    }

    private static ByteBuffer buf(int tag) {
        return ByteBuffer.wrap(new byte[]{(byte) tag});
    }

    @Test
    void rejectsASecondSubscription() {
        ListSource source = new ListSource(List.of(buf(1), buf(2)));
        ByteStream stream = ByteStream.of(source);
        Recorder first = new Recorder();
        stream.subscribe(first);
        first.subscription.request(1);

        Recorder second = new Recorder();
        stream.subscribe(second);
        assertTrue(second.error instanceof IllegalStateException,
                "a cold single-subscription stream must reject a second subscriber");
        assertEquals(1, first.received.size(), "first subscription keeps its own demand");
        assertFalse(first.complete, "first subscription is mid-stream, undisturbed");
        assertEquals(null, first.error, "the rejected second subscribe must not error the first");
    }

    @Test
    void emitsOnlyUpToRequestedDemand() {
        ListSource source = new ListSource(List.of(buf(1), buf(2), buf(3), buf(4), buf(5)));
        ByteStream stream = ByteStream.of(source);
        Recorder r = new Recorder();
        stream.subscribe(r);

        r.subscription.request(2);
        assertEquals(2, r.received.size(), "no emission beyond demand");
        assertFalse(r.complete);

        r.subscription.request(3);
        assertEquals(5, r.received.size());
        assertTrue(r.complete, "EOF after the last chunk completes the stream");
        assertEquals(1, source.closeCount.get(), "normal completion releases the source once");
    }

    @Test
    void emitsNothingWithoutDemand() {
        ListSource source = new ListSource(List.of(buf(1), buf(2)));
        ByteStream stream = ByteStream.of(source);
        Recorder r = new Recorder();
        stream.subscribe(r);
        assertEquals(0, r.received.size(), "cold: nothing flows until demand is signalled");
        r.subscription.request(1);
        assertEquals(1, r.received.size());
    }

    @Test
    void cancelReleasesSourceAndStopsEmission() {
        ListSource source = new ListSource(List.of(buf(1), buf(2), buf(3)));
        ByteStream stream = ByteStream.of(source);
        Recorder r = new Recorder();
        stream.subscribe(r);
        r.subscription.request(1);
        assertEquals(1, r.received.size());

        r.subscription.cancel();
        assertEquals(1, source.closeCount.get(), "cancel releases the source exactly once");
        r.subscription.request(10);
        assertEquals(1, r.received.size(), "no emission after cancel");
        assertFalse(r.complete);
    }

    @Test
    void closeIsIdempotent() {
        ListSource source = new ListSource(List.of(buf(1)));
        ByteStream stream = ByteStream.of(source);
        stream.close();
        stream.close();
        stream.close();
        assertEquals(1, source.closeCount.get(), "close releases the source exactly once");
    }

    @Test
    void ownershipTransfersExactlyOnce() {
        ByteBuffer b1 = buf(7);
        ListSource source = new ListSource(List.of(b1));
        ByteStream stream = ByteStream.of(source);
        Recorder r = new Recorder();
        stream.subscribe(r);
        r.subscription.request(1);

        assertSame(b1, r.received.get(0), "the subscriber receives the very buffer the source produced");
        ByteChunk original = source.handedOut.get(0);
        assertTrue(original.transferred(), "ownership moved out of the source-side envelope");
        assertThrows(IllegalStateException.class, original::transfer,
                "the original reference is unusable after transfer");
    }

    @Test
    void sourceFailurePropagatesAsOnErrorAndReleases() {
        ByteStream.Source failing = new ByteStream.Source() {
            final AtomicInteger closes = new AtomicInteger();
            @Override public ByteChunk next() { throw new IllegalArgumentException("boom"); }
            @Override public void close() { closes.incrementAndGet(); }
        };
        ByteStream stream = ByteStream.of(failing);
        Recorder r = new Recorder();
        stream.subscribe(r);
        r.subscription.request(1);
        assertTrue(r.error instanceof IllegalArgumentException);
        assertFalse(r.complete);
    }
}