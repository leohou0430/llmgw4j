package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.AdminTokenDecision;
import io.github.leohou0430.llmgw.control.AdminTokenMode;
import io.github.leohou0430.llmgw.control.local.DefaultAdminTokenPolicy;
import io.github.leohou0430.llmgw.control.local.RestrictedNetworks;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;

/**
 * Wires the T4.8 legacy-token policy into both HTTP engines. Only /admin management calls
 * that actually present a legacy token are gated; the new /control surface uses sessions and
 * RBAC and is untouched. Accepted migration-mode calls are recorded as emergency platform
 * audit events before the request is allowed through.
 */
@Configuration(proxyBeanMethods = false)
public class AdminTokenMigrationConfiguration {

    private static final String ADMIN_TOKEN = "X-Admin-Token";
    private static final String ADMIN_READ_TOKEN = "X-Admin-Read-Token";

    @Bean
    AdminTokenGuard adminTokenGuard(GatewayProperties properties) {
        AdminTokenMode mode = AdminTokenMode.valueOf(
                properties.getAdminTokenMode().toUpperCase(Locale.ROOT));
        boolean configured = !isBlank(properties.getAdminToken())
                || !isBlank(properties.getAdminReadToken());
        return new AdminTokenGuard(new DefaultAdminTokenPolicy(), mode,
                new RestrictedNetworks(properties.getAdminRestrictedNetworks()), configured);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    ServletAdminTokenFilter servletAdminTokenFilter(AdminTokenGuard guard,
                                                    ObjectProvider<AuditLog> audit) {
        return new ServletAdminTokenFilter(guard, audit);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    ReactiveAdminTokenFilter reactiveAdminTokenFilter(AdminTokenGuard guard,
                                                      ObjectProvider<AuditLog> audit) {
        return new ReactiveAdminTokenFilter(guard, audit);
    }

    static boolean guarded(String path) {
        return path != null && path.startsWith("/admin/") && !path.equals("/admin/console");
    }

    static boolean presentsLegacyToken(HttpServletRequest request) {
        return !isBlank(request.getHeader(ADMIN_TOKEN)) || !isBlank(request.getHeader(ADMIN_READ_TOKEN));
    }

    static boolean presentsLegacyToken(ServerHttpRequest request) {
        return !isBlank(request.getHeaders().getFirst(ADMIN_TOKEN))
                || !isBlank(request.getHeaders().getFirst(ADMIN_READ_TOKEN));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static final class ServletAdminTokenFilter extends OncePerRequestFilter {
        private final AdminTokenGuard guard;
        private final ObjectProvider<AuditLog> audit;

        ServletAdminTokenFilter(AdminTokenGuard guard, ObjectProvider<AuditLog> audit) {
            this.guard = guard;
            this.audit = audit;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI();
            if (!guarded(path) || !presentsLegacyToken(request) || !guard.legacyTokenConfigured()) {
                chain.doFilter(request, response);
                return;
            }
            String client = request.getRemoteAddr();
            AdminTokenDecision decision = guard.evaluate(client);
            if (!decision.allowed()) {
                response.sendError(HttpStatus.FORBIDDEN.value(), decision.reason());
                return;
            }
            if (decision.emergencyPlatform()
                    && !recordEmergency(audit, request.getMethod(), path, client)) {
                response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "migration-mode audit is unavailable");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    static final class ReactiveAdminTokenFilter implements WebFilter {
        private final AdminTokenGuard guard;
        private final ObjectProvider<AuditLog> audit;

        ReactiveAdminTokenFilter(AdminTokenGuard guard, ObjectProvider<AuditLog> audit) {
            this.guard = guard;
            this.audit = audit;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            String path = exchange.getRequest().getPath().value();
            if (!guarded(path) || !presentsLegacyToken(exchange.getRequest())
                    || !guard.legacyTokenConfigured()) {
                return chain.filter(exchange);
            }
            String client = remoteAddress(exchange.getRequest());
            AdminTokenDecision decision = guard.evaluate(client);
            if (!decision.allowed()) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            if (decision.emergencyPlatform() && !recordEmergency(
                    audit, exchange.getRequest().getMethod().name(), path, client)) {
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        }

        private static String remoteAddress(ServerHttpRequest request) {
            InetSocketAddress remote = request.getRemoteAddress();
            return remote == null || remote.getAddress() == null
                    ? null : remote.getAddress().getHostAddress();
        }
    }

    private static boolean recordEmergency(ObjectProvider<AuditLog> audit, String method,
                                           String path, String client) {
        AuditLog log = audit.getIfAvailable();
        if (log == null) {
            return false;
        }
        try {
            log.record("platform", "admin.legacy-token",
                    method + " " + path + " client=" + client);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
