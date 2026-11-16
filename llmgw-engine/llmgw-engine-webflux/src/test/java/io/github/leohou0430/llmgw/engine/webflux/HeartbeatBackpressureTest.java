package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class HeartbeatBackpressureTest {
    @Test void slowDownstreamDropsHeartbeatTicksInsteadOfFailingTheStream() throws Exception {
        var properties = new GatewayProperties(); properties.getUpstream().setHeartbeatIntervalMs(1);
        var cancelled = new AtomicInteger();
        var provider = new UpstreamProvider() {
            public String id() { return "idle"; }
            public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        public void request(long n) {}
                        public void cancel() { cancelled.incrementAndGet(); }
                    });
                    listener.onHeaders(200, Map.of());
                };
            }
            public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) { throw new UnsupportedOperationException(); }
            public String completeRaw(UpstreamRequest request, HeaderListener listener) { throw new UnsupportedOperationException(); }
        };
        var controller = new WebfluxGatewayController(null, null, provider, null, properties, null, null);
        var method = WebfluxGatewayController.class.getDeclaredMethod("upstreamLines", UpstreamRequest.class, HeaderListener.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        var lines = (Flux<String>) method.invoke(controller, new UpstreamRequest("{}", "http://unused", "", "m", true, 5000), HeaderListener.none());
        var errors = new LinkedBlockingQueue<Throwable>();
        var values = new LinkedBlockingQueue<String>();
        var consumer = new BaseSubscriber<String>() {
            @Override protected void hookOnSubscribe(Subscription subscription) {}
            @Override protected void hookOnNext(String line) { values.add(line); }
            @Override protected void hookOnError(Throwable error) { errors.add(error); }
        };
        lines.subscribe(consumer);
        try {
            assertNull(errors.poll(1, TimeUnit.SECONDS), "lack of downstream demand must not turn a timer tick into a stream error");
            consumer.request(1);
            assertEquals(": keep-alive\n\n", values.poll(1, TimeUnit.SECONDS));
        } finally { consumer.cancel(); }
        assertEquals(1, cancelled.get());
    }
    @Test void heartbeatMergePrefetchesOnlyOneUpstreamLineWithoutSocketDemand() throws Exception {
        var properties = new GatewayProperties(); properties.getUpstream().setHeartbeatIntervalMs(1);
        var requested = new java.util.concurrent.atomic.AtomicLong();
        var provider = new UpstreamProvider() {
            public String id() { return "demand-probe"; }
            public Flow.Publisher<String> streamRaw(UpstreamRequest request, HeaderListener listener) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        public void request(long n) { requested.addAndGet(n); }
                        public void cancel() {}
                    });
                    listener.onHeaders(200, Map.of());
                };
            }
            public void streamRawBlocking(UpstreamRequest request, ChunkSink sink) { throw new UnsupportedOperationException(); }
            public String completeRaw(UpstreamRequest request, HeaderListener listener) { throw new UnsupportedOperationException(); }
        };
        var controller = new WebfluxGatewayController(null, null, provider, null, properties, null, null);
        var method = WebfluxGatewayController.class.getDeclaredMethod("upstreamLines", UpstreamRequest.class, HeaderListener.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") var lines = (Flux<String>)method.invoke(controller,
                new UpstreamRequest("{}", "http://unused", "", "m", true, 5000), HeaderListener.none());
        var consumer = new BaseSubscriber<String>() {
            protected void hookOnSubscribe(Subscription subscription) {}
        };
        lines.subscribe(consumer);
        try { assertEquals(1, requested.get()); }
        finally { consumer.cancel(); }
    }

}
