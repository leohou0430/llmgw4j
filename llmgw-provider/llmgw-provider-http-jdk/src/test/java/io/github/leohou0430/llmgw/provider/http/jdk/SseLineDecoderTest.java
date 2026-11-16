package io.github.leohou0430.llmgw.provider.http.jdk;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class SseLineDecoderTest {
    private static final class Lines implements Flow.Subscriber<String> {
        final List<String> values = new ArrayList<>();
        Flow.Subscription subscription;
        Throwable error;
        boolean complete;
        boolean reentrant;
        public void onSubscribe(Flow.Subscription s) { subscription = s; }
        public void onNext(String s) { values.add(s); if (reentrant) subscription.request(1); }
        public void onError(Throwable t) { error = t; }
        public void onComplete() { complete = true; }
    }
    private HttpResponse.BodySubscriber<Void> decoder(Lines lines) throws Exception {
        var type = Class.forName("io.github.leohou0430.llmgw.provider.http.jdk.SseLineDecoder");
        var ctor = type.getDeclaredConstructor(Flow.Subscriber.class);
        ctor.setAccessible(true);
        @SuppressWarnings("unchecked") var body = (HttpResponse.BodySubscriber<Void>) ctor.newInstance(lines);
        return body;
    }
    @Test void preservesUtf8AndMalformedInputAcrossRandomChunks() throws Exception {
        var random = new Random(91723);
        for (int run = 0; run < 200; run++) {
            byte[] input = new byte[random.nextInt(4096)]; random.nextBytes(input);
            // Include Unicode, CRLF and a final unterminated line in every run.
            var prefix = "中文🙂\r\n\nα\rβ\n".getBytes(StandardCharsets.UTF_8);
            var all = new ByteArrayOutputStream(); all.write(prefix); all.write(input); all.write('x');
            input = all.toByteArray();
            List<String> expected;
            try (var reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8))) {
                expected = reader.lines().toList();
            }
            var lines = new Lines(); lines.reentrant = true;
            var body = decoder(lines);
            body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() {} });
            lines.subscription.request(1);
            for (int offset = 0; offset < input.length;) {
                int size = Math.min(input.length - offset, 1 + random.nextInt(31));
                var buffer = ByteBuffer.wrap(input, offset, size).asReadOnlyBuffer();
                int original = buffer.position(); body.onNext(List.of(buffer));
                assertEquals(original, buffer.position()); offset += size;
            }
            body.onComplete();
            assertEquals(expected, lines.values, "seeded case " + run);
            assertTrue(lines.complete); assertNull(lines.error);
            body.getBody().toCompletableFuture().join();
        }
    }
    @Test void finiteDemandDrainsCachedLinesAfterRawEof() throws Exception {
        var lines = new Lines(); var body = decoder(lines); int[] requested = {0};
        body.onSubscribe(new Flow.Subscription() { public void request(long n) { requested[0] += (int)n; } public void cancel() {} });
        assertEquals(0, requested[0]); lines.subscription.request(1); assertEquals(1, requested[0]);
        body.onNext(List.of(ByteBuffer.wrap("one\r\ntwo\nlast".getBytes(StandardCharsets.UTF_8))));
        body.onComplete();
        assertEquals(List.of("one"), lines.values); assertFalse(lines.complete);
        assertTrue(body.getBody().toCompletableFuture().isDone());
        lines.subscription.request(1); assertEquals(List.of("one", "two"), lines.values);
        lines.subscription.request(1); assertEquals(List.of("one", "two", "last"), lines.values);
        assertTrue(lines.complete); assertEquals(1, requested[0]);
    }
    @Test void cancellationAndErrorDiscardPartialLines() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            var lines = new Lines(); var body = decoder(lines); boolean[] cancelled = {false};
            body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } });
            lines.subscription.request(1);
            body.onNext(List.of(ByteBuffer.wrap("partial".getBytes(StandardCharsets.UTF_8))));
            if (cancel) lines.subscription.cancel(); else body.onError(new IOException("broken"));
            body.onComplete(); lines.subscription.request(1);
            assertTrue(lines.values.isEmpty()); assertFalse(lines.complete);
            if (cancel) { assertTrue(cancelled[0]); assertNull(lines.error); }
            else assertInstanceOf(IOException.class, lines.error);
        }
    }
    @Test void reentrantSourceHonorsOneBatchAtATime() throws Exception {
        var lines = new Lines(); lines.reentrant = true; var body = decoder(lines);
        int[] requests = {0};
        body.onSubscribe(new Flow.Subscription() {
            public void request(long n) {
                assertEquals(1, n); requests[0]++;
                if (requests[0] <= 20000) body.onNext(List.of(ByteBuffer.wrap(new byte[]{'x','\n'})));
                else body.onComplete();
            }
            public void cancel() { fail("normal completion must not cancel"); }
        });
        lines.subscription.request(1);
        assertEquals(20000, lines.values.size()); assertTrue(lines.complete);
    }
    @Test void concurrentDemandDoesNotLoseOrDuplicateLines() throws Exception {
        var lines = new Lines(); var body = decoder(lines);
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() {} });
        body.onNext(List.of(ByteBuffer.wrap("x\n".repeat(8000).getBytes(StandardCharsets.UTF_8))));
        body.onComplete();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 8; thread++) tasks.add(executor.submit(() -> {
                for (int i = 0; i < 1000; i++) lines.subscription.request(1);
            }));
            for (var task : tasks) task.get();
        }
        assertEquals(8000, lines.values.size()); assertTrue(lines.complete); assertNull(lines.error);
    }
    @Test void invalidDemandCancelsSourceBeforeError() throws Exception {
        var lines = new Lines(); var body = decoder(lines); boolean[] cancelled = {false};
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled[0] = true; } });
        lines.subscription.request(0);
        assertTrue(cancelled[0]); assertInstanceOf(IllegalArgumentException.class, lines.error);
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

}
