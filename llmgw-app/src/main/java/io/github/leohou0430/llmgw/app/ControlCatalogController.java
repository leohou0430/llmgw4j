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
    private final OrgStore orgs;
    private final ResourceCatalog catalog;
    private final AuditLog audit;
    private final io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage;

    public ControlCatalogController(RequestAuthorizer authorizer, OrgStore orgs, ResourceCatalog catalog, AuditLog audit,
                                    io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage) {
        this.authorizer = authorizer;
        this.orgs = orgs;
        this.catalog = catalog;
        this.audit = audit;
        this.storage = storage;
    }

    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> list(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String tenantId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String projectId) {
        var target = ControlAccess.resolve(authorizer, orgs, sessionId, tenantId, projectId);
        ControlAccess.require(authorizer, sessionId, null, false, Action.CATALOG_READ, target);
        if (target.projectId().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId required");
        return ResponseEntity.ok(Map.of("entries", catalog.list(target.projectId()).stream().map(entry -> new ResourceEntry(
                entry.gatewayId(), entry.projectId(), entry.type(), entry.upstreamId(), entry.sourceRoute(),
                entry.provider(), entry.configVersion(), entry.createdAt(), entry.observableStatus(),
                entry.metadata().entrySet().stream().filter(field -> !field.getKey().startsWith("llmgw."))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                entry.lastSyncedAt(), entry.deleted())).toList()));
    }

    @PostMapping("/catalog")
    public ResponseEntity<Map<String, Object>> create(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody EntryBody body) {
        if (body.gatewayId() == null || body.gatewayId().isBlank() || body.type() == null || body.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gatewayId and type required");
        }
        var target = ControlAccess.resolve(authorizer, orgs, sessionId, body.tenantId(), body.projectId());
        ControlAccess.require(authorizer, sessionId, csrfToken, true, Action.CATALOG_WRITE, target);
        if (target.projectId().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId required");
        WriteGate.check(storage);
        ResourceEntry entry = new ResourceEntry(body.gatewayId(), target.projectId(),
                body.type(),
                body.upstreamId() == null ? "" : body.upstreamId(),
                body.sourceRoute() == null ? "" : body.sourceRoute(),
                body.provider() == null ? "" : body.provider(),
                body.configVersion() == null ? "" : body.configVersion(),
                System.currentTimeMillis(), "pending", Map.of(), 0L, false);
        audit.change(ControlAccess.account(authorizer, sessionId).id(), target.tenantId(), target.projectId(),
                "catalog.create", "id=" + body.gatewayId(), () -> { catalog.create(entry); return null; });
        return ResponseEntity.ok(Map.of("id", body.gatewayId()));
    }

    public record EntryBody(String gatewayId, String type, String upstreamId, String sourceRoute,
                            String provider, String configVersion, String tenantId, String projectId) {
    }
}
