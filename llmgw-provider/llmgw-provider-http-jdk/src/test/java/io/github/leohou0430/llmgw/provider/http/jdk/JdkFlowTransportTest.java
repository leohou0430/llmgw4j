package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JdkFlowTransportTest {
    static class Probe implements Flow.Subscriber<String> {
        final BlockingQueue<String> values = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> ended = new CompletableFuture<>();
        final long initial;
        Flow.Subscription subscription;
        Probe(long initial) { this.initial = initial; }
        public void onSubscribe(Flow.Subscription s) { subscription = s;if (initial > 0) s.request(initial); }
        public void onNext(String item) { values.add(item); }
        public void onError(Throwable error) { ended.completeExceptionally(error); }
        public void onComplete() { subscription.cancel();ended.complete(null); }
    }
    static class Fixture implements AutoCloseable {
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server;
        final JdkHttpUpstream client = new JdkHttpUpstream(new GatewayProperties());
        Fixture(com.sun.net.httpserver.HttpHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> { requests.incrementAndGet();handler.handle(exchange); });server.start();
        }
        UpstreamRequest request() { return new UpstreamRequest("{}","http://127.0.0.1:"+server.getAddress().getPort(),"","m",true,5000,"/",UpstreamMethod.POST); }
        Flow.Publisher<String> publisher() { return client.streamRaw(request(),HeaderListener.none()); }
        public void close() { server.stop(0);client.close();workers.close(); }
    }
    static void respond(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        byte[] body = "data: one\ndata: two\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","text/event-stream");
        exchange.sendResponseHeaders(200,body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
        finally { exchange.close(); }
    }
    @Test void publisherIsColdAndHonorsOneLineOfDemandAtATime() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("reactor.core.publisher.Flux"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));
        try (var fixture = new Fixture(JdkFlowTransportTest::respond)) {
            var publisher = fixture.publisher();assertEquals(0,fixture.requests.get());
            var probe = new Probe(0);publisher.subscribe(probe);
            assertNull(probe.values.poll(100,TimeUnit.MILLISECONDS));
            probe.subscription.request(1);assertEquals("data: one\n",probe.values.poll(5,TimeUnit.SECONDS));
            assertNull(probe.values.poll(100,TimeUnit.MILLISECONDS));
            probe.subscription.request(Long.MAX_VALUE);probe.ended.get(5,TimeUnit.SECONDS);
            assertEquals("data: two\n",probe.values.poll());assertEquals(1,fixture.requests.get());
        }
    }
    @Test void immediateCancelDoesNotStartAnHttpRequest() throws Exception {
        var entered = new CountDownLatch(1);
        try (var fixture = new Fixture(exchange -> { entered.countDown();respond(exchange); })) {
            fixture.publisher().subscribe(new Probe(0) {
                @Override public void onSubscribe(Flow.Subscription s) { super.onSubscribe(s);s.cancel(); }
            });
            assertFalse(entered.await(200,TimeUnit.MILLISECONDS));assertEquals(0,fixture.requests.get());
        }
    }
    @Test void cancelBeforeHeadersDoesNotDeliverLateSignals() throws Exception {
        var entered = new CountDownLatch(1);var release = new CountDownLatch(1);
        try (var fixture = new Fixture(exchange -> {
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS);respond(exchange); }
            catch (InterruptedException e) { Thread.currentThread().interrupt();exchange.close(); }
            catch (java.io.IOException cancelled) { exchange.close(); }
        })) {
            var probe = new Probe(1);fixture.publisher().subscribe(probe);
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));probe.subscription.cancel();release.countDown();
                assertNull(probe.values.poll(200,TimeUnit.MILLISECONDS));assertFalse(probe.ended.isDone());
            } finally { release.countDown(); }
        }
    }
    @Test void invalidDemandProducesOneTerminalError() throws Exception {
        try (var fixture = new Fixture(JdkFlowTransportTest::respond)) {
            var probe = new Probe(0);fixture.publisher().subscribe(probe);probe.subscription.request(0);
            var error = assertThrows(ExecutionException.class, () -> probe.ended.get(5,TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class,error.getCause());assertTrue(probe.values.isEmpty());
        }
    }
    @Test void normalCompletionDoesNotAbortSiblingRequestsOrReusePerSubscriptionState() throws Exception {
        try (var fixture = new Fixture(JdkFlowTransportTest::respond)) {
            var publisher = fixture.publisher();var probes = new ArrayList<Probe>();
            for(int i=0;i<40;i++) { var probe=new Probe(Long.MAX_VALUE);probes.add(probe);publisher.subscribe(probe); }
            for(var probe:probes) {
                probe.ended.get(10,TimeUnit.SECONDS);
                assertEquals(List.of("data: one\n","data: two\n"),List.copyOf(probe.values));
            }
            assertEquals(40,fixture.requests.get());
        }
    }
    @Test void unknownExplicitDialectFailsBeforeUncredentialedNetworkIo() throws Exception {
        try (var fixture = new Fixture(JdkFlowTransportTest::respond)) {
            var request = new UpstreamRequest("{}",fixture.request().targetUrl(),"","not-installed","m",false,1000,"/",UpstreamMethod.POST,java.util.Map.of());
            assertThrows(IllegalArgumentException.class, () -> fixture.client.completeRaw(request,HeaderListener.none()));
            assertEquals(0,fixture.requests.get());
        }
    }
}
