package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.IssuedCredential;
import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.AuthOutcome;
import io.github.leohou0430.llmgw.identity.local.AuthenticatedRequest;
import io.github.leohou0430.llmgw.identity.local.ManagedKeyService;
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
import java.util.Set;

/**
 * Authenticated key management: every call is authorized server-side (session + CSRF for
 * this mutating call + RBAC on KEY_MANAGE for the target), then the key is created with a
 * one-time plaintext response and only a masked form thereafter.
 */
@RestController
@RequestMapping("/control")
public class ControlKeyController {

    private final RequestAuthorizer authorizer;
    private final OrgStore orgs;
    private final SessionStore sessions;
    private final AccountStore accounts;
    private final ManagedKeyService keys;
    private final AuditLog audit;
    private final io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage;

    public ControlKeyController(RequestAuthorizer authorizer, OrgStore orgs, SessionStore sessions,
                                AccountStore accounts, ManagedKeyService keys, AuditLog audit,
                                io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage) {
        this.authorizer = authorizer;
        this.orgs = orgs;
        this.sessions = sessions;
        this.accounts = accounts;
        this.keys = keys;
        this.audit = audit;
        this.storage = storage;
    }

    @PostMapping("/keys")
    public ResponseEntity<Map<String, Object>> create(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @RequestBody CreateBody body) {
        Target target = ControlAccess.resolve(authorizer, orgs, sessionId, body.tenantId(), body.projectId());
        AuthOutcome outcome = authorizer.authorize(
                new AuthenticatedRequest(sessionId, csrfToken, true, Action.KEY_MANAGE, target));
        if (outcome != AuthOutcome.ALLOW) {
            HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, outcome.name());
        }
        Session session = sessions.find(sessionId, System.currentTimeMillis()).orElseThrow();
        Account account = accounts.findById(session.accountId()).orElseThrow();
        WriteGate.check(storage);
        IssuedCredential issued = audit.change(account.id(), target.tenantId(), target.projectId(), "key.create", "id=" + body.keyId(),
                () -> keys.create(new Subject(account.role(), account.scope()), target,
                        new ApiKeyRegistry.ApiKey(body.keyId(), body.name(), body.quotaTokens(), Set.of())));
        return ResponseEntity.ok(Map.of("plaintext", issued.plaintext(), "masked", issued.masked()));
    }

    @GetMapping("/keys")
    public ResponseEntity<Map<String, Object>> list(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String tenantId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String projectId) {
        Target target = ControlAccess.resolve(authorizer, orgs, sessionId, tenantId, projectId);
        AuthOutcome outcome = authorizer.authorize(
                new AuthenticatedRequest(sessionId, null, false, Action.KEY_MANAGE, target));
        if (outcome != AuthOutcome.ALLOW) {
            HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new ResponseStatusException(status, outcome.name());
        }
        Session session = sessions.find(sessionId, System.currentTimeMillis()).orElseThrow();
        Account account = accounts.findById(session.accountId()).orElseThrow();
        return ResponseEntity.ok(Map.of("keys",
                keys.list(new Subject(account.role(), account.scope()), target)));
    }

    @PostMapping("/keys/{id}/revoke")
    public Map<String, Object> revoke(
            @CookieValue(name = "llmgw_sid", required = false) String sid,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @org.springframework.web.bind.annotation.PathVariable String id, @RequestBody ScopeBody body) {
        Target target = ControlAccess.resolve(authorizer, orgs, sid, body.tenantId(), body.projectId());
        ControlAccess.require(authorizer, sid, csrf, true, Action.KEY_MANAGE, target);
        boolean revoked = audit.change(ControlAccess.account(authorizer, sid).id(), target.tenantId(), target.projectId(),
                "key.revoke", "id=" + id, () -> keys.revoke(authorizer.subject(sid), target, id));
        return Map.of("revoked", revoked);
    }

    @PostMapping("/keys/{id}/rotate")
    public Map<String, Object> rotate(
            @CookieValue(name = "llmgw_sid", required = false) String sid,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrf,
            @org.springframework.web.bind.annotation.PathVariable String id, @RequestBody ScopeBody body) {
        Target target = ControlAccess.resolve(authorizer, orgs, sid, body.tenantId(), body.projectId());
        ControlAccess.require(authorizer, sid, csrf, true, Action.KEY_MANAGE, target);
        var issued = audit.change(ControlAccess.account(authorizer, sid).id(), target.tenantId(), target.projectId(),
                "key.rotate", "id=" + id, () -> keys.rotate(authorizer.subject(sid), target, id));
        return Map.of("plaintext", issued.plaintext(), "masked", issued.masked());
    }

    public record ScopeBody(String tenantId, String projectId) { }

    public record CreateBody(String tenantId, String projectId, String keyId, String name, long quotaTokens) {
    }
}
