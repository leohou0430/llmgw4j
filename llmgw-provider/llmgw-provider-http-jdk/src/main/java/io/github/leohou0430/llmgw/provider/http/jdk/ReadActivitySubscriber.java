package io.github.leohou0430.llmgw.provider.http.jdk;

import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/** Idle read measures incoming bytes, including partial lines and non-success bodies. */
final class ReadActivitySubscriber<T> implements BodySubscriber<T> {
    private final BodySubscriber<T> delegate;
    private final AtomicLong lastActivity;

    ReadActivitySubscriber(BodySubscriber<T> delegate, AtomicLong lastActivity) {
        this.delegate = delegate;
        this.lastActivity = lastActivity;
    }

    @Override public CompletionStage<T> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
    @Override public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
            if (buffer.hasRemaining()) { lastActivity.set(System.nanoTime()); break; }
        }
        delegate.onNext(buffers);
    }
    @Override public void onError(Throwable error) { delegate.onError(error); }
    @Override public void onComplete() { delegate.onComplete(); }
}
