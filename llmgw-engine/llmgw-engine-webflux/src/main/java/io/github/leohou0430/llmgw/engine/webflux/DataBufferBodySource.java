package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T2.4 push&rarr;pull bridge: adapts the reactive client body to the core
 * {@link ByteStream.Source} contract so the multipart scanner+spool can drain it
 * in bounded chunks. Each client {@link DataBuffer} is copied and released before
 * the next is requested (publishOn prefetch 1 + a bounded hand-off queue), so the
 * whole body is never retained in memory. Blocking hand-off runs on boundedElastic,
 * never on the event loop. close() cancels the subscription exactly once and
 * unblocks a waiting {@link #next()}.
 */
final class DataBufferBodySource implements ByteStream.Source {

    private static final Object EOF = new Object();

    private final ArrayBlockingQueue<Object> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BaseSubscriber<DataBuffer> subscriber;
    private volatile boolean done;

    DataBufferBodySource(Flux<DataBuffer> body, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.subscriber = body.publishOn(Schedulers.boundedElastic(), 1)
                .subscribeWith(new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
                        request(1);
                    }

                    @Override
                    protected void hookOnNext(DataBuffer buffer) {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.toByteBuffer().get(bytes);
                        DataBufferUtils.release(buffer);
                        if (put(bytes)) {
                            request(1);
                        }
                    }

                    @Override
                    protected void hookOnError(Throwable error) {
                        put(new ErrorSignal(error));
                    }

                    @Override
                    protected void hookOnComplete() {
                        put(EOF);
                    }
                });
    }

    private boolean put(Object item) {
        try {
            queue.put(item);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public ByteChunk next() throws IOException {
        if (closed.get() || done) {
            return null;
        }
        Object item;
        try {
            item = queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading client body", e);
        }
        if (item == EOF) {
            done = true;
            return null;
        }
        if (item instanceof ErrorSignal error) {
            done = true;
            throw new IOException("client body failed: " + error.cause().getMessage(), error.cause());
        }
        return new ByteChunk(ByteBuffer.wrap((byte[]) item));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subscriber.cancel();
            queue.clear();
            queue.offer(EOF);
        }
    }

    private record ErrorSignal(Throwable cause) {
    }
}