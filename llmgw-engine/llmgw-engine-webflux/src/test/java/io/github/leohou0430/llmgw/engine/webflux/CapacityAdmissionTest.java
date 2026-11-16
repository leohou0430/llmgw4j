package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.core.upstream.*;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.*;
import org.springframework.web.reactive.*;
import org.springframework.web.reactive.config.WebFluxConfigurationSupport;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CapacityAdmissionTest {
    static final String BODY="{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
    static final String OK="{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"completion_tokens\":1}}";

    @Test void saturatedRequestsSkipArgumentResolutionAndKeepTheSlotUntilBodyCompletion() throws Exception {
        try(var fixture=new Fixture()) {
            var first=fixture.start();
            assertTrue(fixture.provider.started.await(2,TimeUnit.SECONDS));
            assertFalse(first.isDone(),"handler result must not release the slot while the upstream body is pending");
            var denied=fixture.request("/v1/chat/completions",BODY);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,denied.getStatusCode());
            assertEquals("1",denied.getHeaders().getFirst("Retry-After"));
            assertEquals(ProtocolCodec.errorJson("gateway request capacity exceeded"),denied.getBodyAsString().block());
            assertEquals(1,fixture.adapter().calls.get(),"capacity rejection must precede Spring argument resolution");
            assertEquals(1,fixture.provider.calls.get());
            fixture.provider.pending.complete(OK);first.get(2,TimeUnit.SECONDS);
            assertEquals(HttpStatus.OK,fixture.request("/v1/chat/completions",BODY).getStatusCode());
            assertEquals(2,fixture.provider.calls.get());
        }
    }

    @Test void cancellationReleasesTheSlotAndCancelsTheUpstream() throws Exception {
        try(var fixture=new Fixture()) {
            var first=fixture.start();assertTrue(fixture.provider.started.await(2,TimeUnit.SECONDS));
            first.cancel(true);
            assertTrue(fixture.provider.pending.isCancelled());
            assertEquals(HttpStatus.OK,fixture.request("/v1/chat/completions",BODY).getStatusCode());
        }
    }

    @Test void invalidBodyReleasesCapacityAndUnknownRoutesStay404() {
        try(var fixture=new Fixture()) {
            assertEquals(HttpStatus.BAD_REQUEST,fixture.request("/v1/chat/completions","{bad").getStatusCode());
            var first=fixture.start();
            try {
                assertTrue(fixture.provider.started.await(2,TimeUnit.SECONDS));
                assertEquals(HttpStatus.NOT_FOUND,fixture.request("/v1/not-an-endpoint",BODY).getStatusCode());
                assertEquals(HttpStatus.OK,fixture.request(HttpMethod.GET,"/admin/capacity-probe","").getStatusCode());
                assertEquals(HttpStatus.METHOD_NOT_ALLOWED,fixture.request("/v1/models",BODY).getStatusCode());
            }catch(InterruptedException error){Thread.currentThread().interrupt();fail(error);}
            finally{first.cancel(true);}
            assertEquals(HttpStatus.OK,fixture.request("/v1/chat/completions",BODY).getStatusCode());
        }
    }

    @Test void upstreamFailureDoesNotAdvertiseCapacityRetry() {
        try(var fixture=new Fixture()) {
            fixture.provider.pending.completeExceptionally(new UpstreamStatusException(503,"unavailable"));
            var response=fixture.request("/v1/chat/completions",BODY);
            assertEquals(HttpStatus.BAD_GATEWAY,response.getStatusCode());
            assertNull(response.getHeaders().getFirst("Retry-After"));
            assertEquals(HttpStatus.OK,fixture.request("/v1/chat/completions",BODY).getStatusCode());
        }
    }

    @Test void synchronousAdapterFailureDoesNotLeakCapacity() {
        try(var fixture=new Fixture()) {
            fixture.adapter().failNext=true;
            assertEquals(HttpStatus.BAD_REQUEST,fixture.request("/v1/chat/completions",BODY).getStatusCode());
            fixture.provider.pending.complete(OK);
            assertEquals(HttpStatus.OK,fixture.request("/v1/chat/completions",BODY).getStatusCode());
        }
    }

    @Test void applicationContextsHaveIndependentCapacity() throws Exception {
        try(var one=new Fixture();var two=new Fixture()) {
            var first=one.start();assertTrue(one.provider.started.await(2,TimeUnit.SECONDS));
            try {
                two.provider.pending.complete(OK);
                assertEquals(HttpStatus.OK,two.request("/v1/chat/completions",BODY).getStatusCode());
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE,one.request("/v1/chat/completions",BODY).getStatusCode());
            }finally{first.cancel(true);}
        }
    }

    @RestController
    static class ProbeAdminController {
        @GetMapping("/admin/capacity-probe") @ResponseStatus(HttpStatus.OK)
        public Mono<String> state(){return Mono.just("up");}
    }

    @Configuration(proxyBeanMethods=false)
    static class WebConfig extends WebFluxConfigurationSupport {
        @Override protected RequestMappingHandlerAdapter createRequestMappingHandlerAdapter(){return new CountingAdapter();}
    }
    static class CountingAdapter extends RequestMappingHandlerAdapter {
        final AtomicInteger calls=new AtomicInteger();
        volatile boolean failNext;
        @Override public Mono<HandlerResult> handle(ServerWebExchange exchange,Object handler){calls.incrementAndGet();if(failNext){failNext=false;throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}return super.handle(exchange,handler);}
    }
    static class PendingProvider implements UpstreamProvider {
        final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch started=new CountDownLatch(1);
        final CompletableFuture<String> pending=new CompletableFuture<>();
        public String id(){return "pending";}
        public Flow.Publisher<String> streamRaw(UpstreamRequest request,HeaderListener listener){throw new UnsupportedOperationException();}
        public void streamRawBlocking(UpstreamRequest request,ChunkSink sink){throw new UnsupportedOperationException();}
        public String completeRaw(UpstreamRequest request,HeaderListener listener){throw new UnsupportedOperationException();}
        public CompletableFuture<String> completeRawAsync(UpstreamRequest request,HeaderListener listener){
            int count=calls.incrementAndGet();started.countDown();return count==1?pending:CompletableFuture.completedFuture(OK);
        }
    }
    static class Fixture implements AutoCloseable {
        final PendingProvider provider=new PendingProvider();
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final HttpHandler handler;
        Fixture(){
            var properties=new GatewayProperties();properties.setMaxConcurrentRequests(1);
            var route=new Route("test","*","","http://unused","",1,List.of());
            var router=new Router(){
                public Route resolve(String model){return route;}
                public Route resolve(ProtocolKind kind,String model){return route;}
                public Route byId(String id){return route;}
                public List<Route> fallbacks(Route primary){return List.of();}
                public List<Route> all(){return List.of(route);}
            };
            var chain=new GovernanceChain(){
                public GatewayDecision enter(String token,ParsedRequest request,Route target){return GatewayDecision.allow("tenant",request,target);}
                public Optional<String> lookupCache(String key,ParsedRequest request){return Optional.empty();}
                public void cache(String key,ParsedRequest request,String body){}
                public void finish(GatewayContext context,boolean success){}
                public Map<String,Object> snapshot(){return Map.of();}
            };
            var headers=new HeaderPolicy(){
                public Map<String,String> toUpstream(ProtocolKind kind,Map<String,String> values){return Map.of();}
                public List<String> toClient(Map<String,List<String>> values){return List.of();}
            };
            var tasks=new TaskRouteSticky(){public void bind(String task,String routeId){}public String resolve(String task){return null;}};
            context.getEnvironment().setActiveProfiles("webflux");
            context.registerBean(GatewayProperties.class,()->properties);
            context.register(WebConfig.class,WebfluxEngineConfiguration.class);
            context.registerBean(ProbeAdminController.class);
            context.registerBean(WebfluxGatewayController.class,()->new WebfluxGatewayController(chain,router,provider,CircuitBreaker.noop(),properties,headers,tasks));
            context.refresh();handler=WebHttpHandlerBuilder.applicationContext(context).build();
        }
        CountingAdapter adapter(){return (CountingAdapter)context.getBean(RequestMappingHandlerAdapter.class);}
        CompletableFuture<Void> start(){return handler.handle(MockServerHttpRequest.post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).body(BODY),new MockServerHttpResponse()).toFuture();}
        MockServerHttpResponse request(String path,String body){return request(HttpMethod.POST,path,body);}
        MockServerHttpResponse request(HttpMethod method,String path,String body){
            var response=new MockServerHttpResponse();handler.handle(MockServerHttpRequest.method(method,path).contentType(MediaType.APPLICATION_JSON).body(body),response).block(Duration.ofSeconds(2));return response;
        }
        public void close(){provider.pending.cancel(true);context.close();}
    }
}
