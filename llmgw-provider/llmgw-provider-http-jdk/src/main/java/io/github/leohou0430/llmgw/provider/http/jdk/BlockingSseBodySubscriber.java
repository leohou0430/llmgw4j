package io.github.leohou0430.llmgw.provider.http.jdk;

import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** One requested line plus a terminal signal; only the consuming thread writes heartbeats. */
final class BlockingSseBodySubscriber implements BodySubscriber<Stream<String>> {
    private static final Object END = new Object();
    private record Failure(Throwable cause) {}
    private final ArrayBlockingQueue<Object> signals = new ArrayBlockingQueue<>(2);
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Flow.Subscription lines;
    private final BodySubscriber<Void> decoder;
    private final CompletionStage<Stream<String>> body;

    BlockingSseBodySubscriber(int heartbeatMs, ChunkSink sink) {
        decoder = new SseLineDecoder(new Flow.Subscriber<String>() {
            public void onSubscribe(Flow.Subscription subscription) {
                lines = subscription;
                if (terminal.get()) subscription.cancel(); else subscription.request(1);
            }
            public void onNext(String line) {
                if (!terminal.get()) signals.add(line);
            }
            public void onError(Throwable error) {
                if (terminal.compareAndSet(false, true)) signals.add(new Failure(error));
            }
            public void onComplete() {
                if (terminal.compareAndSet(false, true)) signals.add(END);
            }
        });
        var reader = new Spliterators.AbstractSpliterator<String>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            public boolean tryAdvance(Consumer<? super String> action) {
                Object signal;
                try {
                    do {
                        signal = heartbeatMs > 0 ? signals.poll(heartbeatMs, TimeUnit.MILLISECONDS) : signals.take();
                        if (signal == null) sink.onChunk(": keep-alive\n\n");
                    } while (signal == null);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                if (signal == END) return false;
                if (signal instanceof Failure failure) throw new IllegalStateException(failure.cause());
                action.accept((String) signal);
                // Do not read ahead while the caller is blocked writing this line.
                if (!terminal.get()) lines.request(1);
                return true;
            }
        };
        body = CompletableFuture.completedFuture(StreamSupport.stream(reader, false).onClose(this::close));
    }

    private void close() {
        if (terminal.compareAndSet(false, true)) {
            Flow.Subscription active = lines;
            if (active != null) active.cancel();
        }
    }

    @Override public CompletionStage<Stream<String>> getBody() { return body; }
    @Override public void onSubscribe(Flow.Subscription subscription) { decoder.onSubscribe(subscription); }
    @Override public void onNext(List<ByteBuffer> buffers) { decoder.onNext(buffers); }
    @Override public void onError(Throwable error) { decoder.onError(error); }
    @Override public void onComplete() { decoder.onComplete(); }
}
