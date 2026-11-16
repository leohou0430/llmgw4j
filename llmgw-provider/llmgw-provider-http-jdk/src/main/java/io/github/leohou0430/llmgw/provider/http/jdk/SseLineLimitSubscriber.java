package io.github.leohou0430.llmgw.provider.http.jdk;

import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bound and finish SSE lines before decoding; owns no body buffers. */
final class SseLineLimitSubscriber<T> implements BodySubscriber<T> {
    private final BodySubscriber<T> delegate;
    private final int limit;
    private Flow.Subscription subscription;
    private int lineBytes;
    private boolean needsFinalLf;
    private boolean done;

    SseLineLimitSubscriber(BodySubscriber<T> delegate, int limit) {
        if (limit < 1) throw new IllegalArgumentException("SSE line limit must be positive");
        this.delegate = delegate;
        this.limit = limit;
    }

    @Override public CompletionStage<T> getBody() { return delegate.getBody(); }

    @Override public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }

    @Override public void onNext(List<ByteBuffer> buffers) {
        if (done) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.hasRemaining()) needsFinalLf = buffer.get(buffer.limit() - 1) != '\n';
            // Absolute reads preserve positions for UTF-8/CRLF decoding.
            for (int i = buffer.position(); i < buffer.limit(); i++) {
                byte value = buffer.get(i);
                if (value == '\r' || value == '\n') lineBytes = 0;
                else if (lineBytes == limit) {
                    done = true;
                    subscription.cancel();
                    delegate.onError(new IOException("upstream SSE line exceeds " + limit + " bytes"));
                    return;
                } else lineBytes++;
            }
        }
        delegate.onNext(buffers);
    }

    @Override public void onError(Throwable error) {
        if (done) return;
        done = true;
        delegate.onError(error);
    }

    @Override public void onComplete() {
        if (done) return;
        done = true;
        // Under finite demand the JDK adapter can omit an unterminated EOF line
        // while removing an exhausted input buffer. Finish only that existing line.
        if (needsFinalLf) delegate.onNext(List.of(ByteBuffer.wrap(new byte[] {'\n'})));
        delegate.onComplete();
    }
}
