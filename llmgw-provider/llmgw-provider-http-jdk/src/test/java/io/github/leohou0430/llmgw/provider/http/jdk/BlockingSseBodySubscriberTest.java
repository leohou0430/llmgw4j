package io.github.leohou0430.llmgw.provider.http.jdk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockingSseBodySubscriberTest {
    static class Subscription implements Flow.Subscription {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        public void request(long n) { requests.addAndGet((int) n); }
        public void cancel() { cancels.incrementAndGet(); }
    }

    @Test void preservesUtf8CrLfAndEofAndNeverCancelsNormalCompletion() {
        var subscriber = new SseLineLimitSubscriber<>(new BlockingSseBodySubscriber(0, line -> fail("heartbeat disabled")), 1024);
        var transport = new Subscription(); subscriber.onSubscribe(transport);
        var bytes = "中🙂\r\nx\r\n\nlast".getBytes(StandardCharsets.UTF_8);
        subscriber.onNext(List.of(ByteBuffer.wrap(bytes, 0, 2), ByteBuffer.wrap(bytes, 2, bytes.length - 2)));
        subscriber.onComplete();
        try (var body = subscriber.getBody().toCompletableFuture().join()) {
            assertEquals(List.of("中🙂", "x", "", "last"), body.toList());
        }
        assertEquals(0, transport.cancels.get());
    }

    @Test void slowConsumerDoesNotRequestMoreInputUntilItsWriteReturns() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var subscriber = new SseLineLimitSubscriber<>(new BlockingSseBodySubscriber(0, line -> {}), 1024);
        var transport = new Subscription(); subscriber.onSubscribe(transport);
        subscriber.onNext(List.of(ByteBuffer.wrap("data: one\n".getBytes(StandardCharsets.UTF_8))));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = workers.submit(() -> {
                try (var body = subscriber.getBody().toCompletableFuture().join()) {
                    body.forEach(line -> {
                        entered.countDown();
                        try { release.await(2, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                    });
                }
            });
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals(1, transport.requests.get(), "no read-ahead while the consumer is blocked");
            } finally { subscriber.onComplete(); release.countDown(); task.get(2, TimeUnit.SECONDS); }
        }
        assertEquals(0, transport.cancels.get());
    }

    @Test void eofNormalizationPreservesEmptyCrLfAndMalformedUtf8() {
        byte[][] inputs = {new byte[0], "a\n".getBytes(StandardCharsets.UTF_8),
                "a\r".getBytes(StandardCharsets.UTF_8), "a\r\n".getBytes(StandardCharsets.UTF_8),
                "a".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xe4, (byte) 0xb8}};
        List<List<String>> expected = List.of(List.of(), List.of("a"), List.of("a"),
                List.of("a"), List.of("a"), List.of("\ufffd"));
        for (int i = 0; i < inputs.length; i++) {
            var subscriber = new SseLineLimitSubscriber<>(new BlockingSseBodySubscriber(0, line -> fail("heartbeat disabled")), 1024);
            var transport = new Subscription(); subscriber.onSubscribe(transport);
            if (inputs[i].length > 0) subscriber.onNext(List.of(ByteBuffer.wrap(inputs[i])));
            subscriber.onComplete();
            try (var body = subscriber.getBody().toCompletableFuture().join()) {
                assertEquals(expected.get(i), body.toList(), "input " + i);
            }
            assertEquals(0, transport.cancels.get());
        }
    }

    @Test void failedWriteCancelsOnlyItsSubscriptionAndDoesNotRequestAnotherLine() {
        var one = new Subscription(); var two = new Subscription();
        var first = new SseLineLimitSubscriber<>(new BlockingSseBodySubscriber(0, line -> {}), 1024);
        var second = new SseLineLimitSubscriber<>(new BlockingSseBodySubscriber(0, line -> {}), 1024);
        first.onSubscribe(one); second.onSubscribe(two);
        first.onNext(List.of(ByteBuffer.wrap("one\n".getBytes(StandardCharsets.UTF_8))));
        var failure = new IllegalStateException("downstream write failed");
        try (var body = first.getBody().toCompletableFuture().join()) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> body.forEach(line -> { throw failure; })));
        }
        assertEquals(1, one.cancels.get()); assertEquals(1, one.requests.get());
        assertEquals(0, two.cancels.get());
        second.onNext(List.of(ByteBuffer.wrap("two\n".getBytes(StandardCharsets.UTF_8))));
        second.onComplete();
        try (var body = second.getBody().toCompletableFuture().join()) { assertEquals(List.of("two"), body.toList()); }
        assertEquals(0, two.cancels.get());
    }
}
