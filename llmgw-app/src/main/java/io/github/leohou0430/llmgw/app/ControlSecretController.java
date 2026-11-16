package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.RootKeyMissingException;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.AuthOutcome;
import io.github.leohou0430.llmgw.identity.local.AuthenticatedRequest;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Provider-secret management: secrets are stored AES-GCM encrypted and only the masked
 * suffix is ever returned. Source management is a platform action (tenant/project admins
 * are denied), every mutation is audited, and a missing root key fails closed with 503
 * rather than storing plaintext.
 */
@RestController
@RequestMapping("/control")
public class ControlSecretController {
    private final RequestAuthorizer authorizer;
    private final SecretStore secrets;
    private final AuditLog audit;

    public ControlSecretController(RequestAuthorizer authorizer, SecretStore secrets, AuditLog audit) {
        this.authorizer = authorizer;
        this.secrets = secrets;
        this.audit = audit;
    }

    @GetMapping("/secrets")
    public ResponseEntity<Map<String, Object>> list(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId) {
        authorize(sessionId, null, false, "t1", "p1");
        try {
            return ResponseEntity.ok(Map.of("secrets", secrets.list("t1", "p1")));
        } catch (RootKeyMissingException missing) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, missing.getMessage());
        }
    }

    @PostMapping("/secrets")
    public ResponseEntity<Map<String, Object>> create(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody SecretBody body) {
        if (body.name() == null || body.name().isBlank() || body.secret() == null || body.secret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and secret required");
        }
        authorize(sessionId, csrfToken, true, "t1", "p1");
        try {
            String id = secrets.create("t1", "p1", body.name(), body.secret());
            audit.record("p1", "secret.create", "id=" + id + " name=" + body.name());
            return ResponseEntity.ok(Map.of("id", id));
        } catch (RootKeyMissingException missing) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, missing.getMessage());
        } catch (io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException limit) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, limit.getMessage());
        }
    }

    @PostMapping("/secrets/{id}/rotate")
    public ResponseEntity<Map<String, Object>> rotate(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @PathVariable("id") String id,
            @RequestBody SecretBody body) {
        if (body.secret() == null || body.secret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret required");
        }
        authorize(sessionId, csrfToken, true, "t1", "p1");
        try {
            secrets.rotate("t1", "p1", id, body.secret());
            audit.record("p1", "secret.rotate", "id=" + id);
            return ResponseEntity.ok(Map.of("rotated", id));
        } catch (RootKeyMissingException missing) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, missing.getMessage());
        } catch (io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException limit) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, limit.getMessage());
        }
    }

    private void authorize(String sessionId, String csrfToken, boolean mutating, String tenantId, String projectId) {
        AuthOutcome outcome = authorizer.authorize(new AuthenticatedRequest(
                sessionId, csrfToken, mutating, Action.SOURCE_MANAGE, new Target(tenantId, projectId)));
        if (outcome != AuthOutcome.ALLOW) {
            HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, outcome.name());
        }
    }

    public record SecretBody(String name, String secret) {
    }
}
