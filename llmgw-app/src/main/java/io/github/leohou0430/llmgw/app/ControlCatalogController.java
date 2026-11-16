package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
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
 * Project resource catalog directory (list/create). Reads require CATALOG_READ; writes
 * require CATALOG_WRITE (viewers denied). Successful creates are audited. The catalog
 * itself is a local store behind the shared {@link ResourceCatalog} SPI.
 */
@RestController
@RequestMapping("/control")
public class ControlCatalogController {
    private final RequestAuthorizer authorizer;
    private final ResourceCatalog catalog;
    private final AuditLog audit;
    private final io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage;

    public ControlCatalogController(RequestAuthorizer authorizer, ResourceCatalog catalog, AuditLog audit,
                                    io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage) {
        this.authorizer = authorizer;
        this.catalog = catalog;
        this.audit = audit;
        this.storage = storage;
    }

    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> list(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId) {
        authorize(sessionId, null, false, Action.CATALOG_READ);
        return ResponseEntity.ok(Map.of("entries", catalog.list("p1")));
    }

    @PostMapping("/catalog")
    public ResponseEntity<Map<String, Object>> create(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody EntryBody body) {
        if (body.gatewayId() == null || body.gatewayId().isBlank() || body.type() == null || body.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gatewayId and type required");
        }
        authorize(sessionId, csrfToken, true, Action.CATALOG_WRITE);
        WriteGate.check(storage);
        ResourceEntry entry = new ResourceEntry(body.gatewayId(), "p1",
                body.type(),
                body.upstreamId() == null ? "" : body.upstreamId(),
                body.sourceRoute() == null ? "" : body.sourceRoute(),
                body.provider() == null ? "" : body.provider(),
                body.configVersion() == null ? "" : body.configVersion(),
                System.currentTimeMillis(), "pending", Map.of(), 0L, false);
        catalog.create(entry);
        audit.record("p1", "catalog.create", "id=" + body.gatewayId());
        return ResponseEntity.ok(Map.of("id", body.gatewayId()));
    }

    private void authorize(String sessionId, String csrfToken, boolean mutating, Action action) {
        AuthOutcome outcome = authorizer.authorize(
                new AuthenticatedRequest(sessionId, csrfToken, mutating, action, new Target("t1", "p1")));
        if (outcome != AuthOutcome.ALLOW) {
            HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, outcome.name());
        }
    }

    public record EntryBody(String gatewayId, String type, String upstreamId, String sourceRoute,
                            String provider, String configVersion) {
    }
}