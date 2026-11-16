package io.github.leohou0430.llmgw.provider.http.jdk;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/** Linear UTF-8 line decoding; the outer byte guard bounds each accumulated line. */
final class SseLineDecoder implements BodySubscriber<Void>, Flow.Subscription {
    private final Flow.Subscriber<? super String> downstream;
    private final CompletableFuture<Void> body = new CompletableFuture<>();
    private final ArrayDeque<ByteBuffer> buffers = new ArrayDeque<>();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final AtomicInteger draining = new AtomicInteger();
    private Flow.Subscription upstream;
    private long demand;
    private boolean outstanding;
    private boolean eof;
    private boolean cancelled;
    private boolean terminated;
    private boolean skipLf;
    private Throwable failure;

    SseLineDecoder(Flow.Subscriber<? super String> downstream) {
        this.downstream = java.util.Objects.requireNonNull(downstream);
    }

    @Override public CompletionStage<Void> getBody() { return body; }

    @Override public void onSubscribe(Flow.Subscription subscription) {
        synchronized (this) {
            if (upstream != null || cancelled) { subscription.cancel(); return; }
            upstream = subscription;
        }
        downstream.onSubscribe(this);
        drain();
    }

    @Override public void request(long n) {
        if (n <= 0) {
            Flow.Subscription source;
            synchronized (this) { source = upstream; }
            if (source != null) source.cancel();
            onError(new IllegalArgumentException("Flow demand must be positive"));
            return;
        }
        synchronized (this) {
            if (cancelled || terminated) return;
            demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
        }
        drain();
    }

    @Override public void cancel() {
        Flow.Subscription source;
        synchronized (this) {
            if (cancelled || terminated) return;
            cancelled = true;
            source = upstream;
            buffers.clear(); line.reset();
        }
        // Abort the real source before notifying dependent futures.
        if (source != null) source.cancel();
        body.cancel(false);
    }

    @Override public void onNext(List<ByteBuffer> input) {
        synchronized (this) {
            if (cancelled || terminated || eof) return;
            outstanding = false;
            for (var buffer : input) {
                if (!buffer.hasRemaining()) continue;
                // One bounded transport batch copy avoids per-byte direct-buffer access in the cold path.
                byte[] bytes = new byte[buffer.remaining()];
                buffer.duplicate().get(bytes);
                buffers.add(ByteBuffer.wrap(bytes));
            }
        }
        drain();
    }

    @Override public void onError(Throwable error) {
        synchronized (this) {
            if (cancelled || terminated || eof) return;
            failure = java.util.Objects.requireNonNull(error);
            eof = true;
        }
        body.completeExceptionally(error);
        drain();
    }

    @Override public void onComplete() {
        synchronized (this) {
            if (cancelled || terminated || eof) return;
            eof = true;
        }
        // HTTP completion must not wait for demand to drain already received lines.
        body.complete(null);
        drain();
    }

    private void drain() {
        if (draining.getAndIncrement() != 0) return;
        int missed = 1;
        for (;;) {
            for (;;) {
                String next = null;
                Throwable error = null;
                boolean complete = false;
                Flow.Subscription request = null;
                synchronized (this) {
                    if (cancelled || terminated) break;
                    if (failure != null) {
                        error = failure; terminated = true; buffers.clear(); line.reset();
                    } else {
                        // CR already emitted its line; swallow its optional LF even at zero demand.
                        if (skipLf && !buffers.isEmpty()) {
                            var buffer = buffers.peek();
                            if (buffer.get(buffer.position()) == '\n') buffer.get();
                            skipLf = false;
                            if (!buffer.hasRemaining()) buffers.remove();
                        }
                        while (demand > 0 && !buffers.isEmpty() && next == null) {
                            var buffer = buffers.peek();
                            int start = buffer.position();
                            int end = lineEnd(buffer.array(), start, buffer.limit());
                            if (end > start) {
                                // Copy only newly received bytes; never rescan an accumulated prefix.
                                line.write(buffer.array(), start, end - start);
                                buffer.position(end);
                            }
                            if (buffer.hasRemaining()) {
                                skipLf = buffer.get() == '\r';
                                next = line.toString(StandardCharsets.UTF_8); line.reset(); demand--;
                            }
                            if (!buffer.hasRemaining()) buffers.remove();
                        }
                        if (next == null && buffers.isEmpty()) {
                            if (eof && line.size() == 0) { terminated = true; complete = true; }
                            else if (eof && demand > 0) {
                                next = line.toString(StandardCharsets.UTF_8); line.reset(); demand--;
                            } else if (!eof && demand > 0 && !outstanding && upstream != null) {
                                outstanding = true; request = upstream;
                            }
                        }
                    }
                }
                try {
                    if (error != null) { downstream.onError(error); break; }
                    if (complete) { downstream.onComplete(); break; }
                    if (next != null) downstream.onNext(next);
                    else if (request != null) request.request(1);
                    else break;
                } catch (Throwable broken) {
                    cancel();
                    if (broken instanceof Error fatal) throw fatal;
                    break;
                }
            }
            missed = draining.addAndGet(-missed);
            if (missed == 0) return;
        }
    }
    private static int lineEnd(byte[] bytes, int start, int limit) {
        int end = start;
        while (end < limit && bytes[end] != '\r' && bytes[end] != '\n') end++;
        return end;
    }

}
