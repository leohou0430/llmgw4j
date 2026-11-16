package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.PlatformPolicy;
import io.github.leohou0430.llmgw.control.RouteVersion;
import io.github.leohou0430.llmgw.control.VersionConflictException;
import io.github.leohou0430.llmgw.control.local.RouteCandidateValidator;
import io.github.leohou0430.llmgw.control.local.RouteVersionService;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.AuthOutcome;
import io.github.leohou0430.llmgw.identity.local.AuthenticatedRequest;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Authenticated route-version publishing: draft → immutable publish → this-process
 * activation, plus rollback that creates a new version referencing the target. Every call
 * is authorized server-side (session + CSRF + RBAC on ROUTE_PUBLISH/ROUTE_ROLLBACK).
 */
@RestController
@RequestMapping("/control")
public class ControlRouteController {

    private final RequestAuthorizer authorizer;
    private final RouteVersionService routeVersions;
    private final RouteCandidateValidator candidateValidator;
    private final PlatformPolicy platformPolicy;
    private final AuditLog audit;
    private final io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage;

    public ControlRouteController(RequestAuthorizer authorizer, RouteVersionService routeVersions,
                                  RouteCandidateValidator candidateValidator, PlatformPolicy platformPolicy,
                                  AuditLog audit,
                                  io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage) {
        this.authorizer = authorizer;
        this.routeVersions = routeVersions;
        this.candidateValidator = candidateValidator;
        this.platformPolicy = platformPolicy;
        this.audit = audit;
        this.storage = storage;
    }

    @GetMapping("/routes")
    public Map<String, Object> status() {
        return Map.of("activeVersion", routeVersions.activeVersion());
    }

    @PostMapping("/routes/publish")
    public ResponseEntity<Map<String, Object>> publish(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody PublishBody body) {
        authorize(sessionId, csrfToken, Action.ROUTE_PUBLISH, body.tenantId(), body.projectId());
        if (body.upstream() != null) {
            var violations = candidateValidator.check(body.upstream(), platformPolicy);
            if (!violations.isEmpty()) {
                audit.record(body.projectId(), "route.publish.rejected", String.join("; ", violations));
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid upstream: " + violations);
            }
        }
        WriteGate.check(storage);
        routeVersions.saveDraft(body.config());
        RouteVersion published = tryConflict(() -> routeVersions.publish(body.expectedVersion()));
        audit.record(body.projectId(), "route.publish", "version=" + published.version());
        return ResponseEntity.ok(Map.of("version", published.version(), "activeVersion", routeVersions.activeVersion()));
    }

    @PostMapping("/routes/rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody RollbackBody body) {
        authorize(sessionId, csrfToken, Action.ROUTE_ROLLBACK, body.tenantId(), body.projectId());
        WriteGate.check(storage);
        RouteVersion rolledBack = tryConflict(() -> routeVersions.rollback(body.targetVersion(), body.expectedVersion()));
        audit.record(body.projectId(), "route.rollback", "version=" + rolledBack.version() + " base=" + rolledBack.baseVersion());
        return ResponseEntity.ok(Map.of("version", rolledBack.version(), "baseVersion", rolledBack.baseVersion()));
    }

    private void authorize(String sessionId, String csrfToken, Action action, String tenantId, String projectId) {
        AuthOutcome outcome = authorizer.authorize(
                new AuthenticatedRequest(sessionId, csrfToken, true, action, new Target(tenantId, projectId)));
        if (outcome != AuthOutcome.ALLOW) {
            HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, outcome.name());
        }
    }

    private static RouteVersion tryConflict(java.util.concurrent.Callable<RouteVersion> action) {
        try {
            return action.call();
        } catch (VersionConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        } catch (io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException full) {
            throw full;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    public record PublishBody(String tenantId, String projectId, long expectedVersion, String config,
                              Map<String, Object> upstream) {
    }

    public record RollbackBody(String tenantId, String projectId, long expectedVersion, long targetVersion) {
    }
}