package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.engine.EngineHeaders;
import io.github.leohou0430.llmgw.engine.EngineReasons;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Reject after route selection, before argument resolution; release after the response, not HandlerResult. */
public final class WebfluxCapacityAdmission implements HandlerAdapter, WebFilter, Ordered {
    private static final String LEASE = WebfluxCapacityAdmission.class.getName() + ".lease";
    private final Semaphore requests;
    private final RequestMappingHandlerAdapter delegate;

    public WebfluxCapacityAdmission(GatewayProperties properties, RequestMappingHandlerAdapter delegate) {
        this.requests = new Semaphore(properties.getMaxConcurrentRequests());
        this.delegate = delegate;
    }

    /** Explicitly precedes the framework's default annotated-controller adapter. */
    @Override public int getOrder() { return -1; }

    @Override public boolean supports(Object handler) {
        return handler instanceof HandlerMethod method
                && WebfluxGatewayController.class.isAssignableFrom(method.getBeanType());
    }

    @Override public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
        return Mono.defer(() -> {
            if (exchange.getAttribute(LEASE) != null) return delegate.handle(exchange, handler);
            if (!requests.tryAcquire()) {
                var response = exchange.getResponse();
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                response.getHeaders().set(EngineHeaders.RETRY_AFTER, "1");
                byte[] body = ProtocolCodec.errorJson(EngineReasons.CAPACITY_EXCEEDED)
                        .getBytes(StandardCharsets.UTF_8);
                return response.writeWith(Mono.just(response.bufferFactory().wrap(body))).then(Mono.empty());
            }
            exchange.getAttributes().put(LEASE, requests);
            return delegate.handle(exchange, handler);
        });
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).doFinally(signal -> {
            if (exchange.getAttributes().remove(LEASE, requests)) requests.release();
        });
    }
}
