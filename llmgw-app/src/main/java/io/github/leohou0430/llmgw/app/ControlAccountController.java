package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.*;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/control/accounts")
public class ControlAccountController {
    private final RequestAuthorizer authorizer;
    private final AuthorizationService authorization;
    private final AccountStore accounts;
    private final PasswordHasher passwords;
    private final OrgStore orgs;
    private final AuditLog audit;
    public ControlAccountController(RequestAuthorizer authorizer, AuthorizationService authorization,
            AccountStore accounts, PasswordHasher passwords, OrgStore orgs, AuditLog audit) {
        this.authorizer = authorizer; this.authorization = authorization; this.accounts = accounts;
        this.passwords = passwords; this.orgs = orgs; this.audit = audit;
    }
    @GetMapping
    public Map<String,Object> list(@CookieValue(name="llmgw_sid",required=false) String sid) {
        var actor = ControlAccess.account(authorizer, sid);
        if (actor.role() == Role.PROJECT_VIEWER) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return Map.of("accounts", accounts.list().stream().filter(account -> actor.scope().isPlatform()
                || (actor.scope().tenantId().equals(account.scope().tenantId())
                && (actor.scope().projectId().isBlank() || actor.scope().projectId().equals(account.scope().projectId()))))
                .map(account -> Map.of("id",account.id(),"username",account.username(),"role",account.role().name(),
                        "tenantId",account.scope().tenantId(),"projectId",account.scope().projectId(),"enabled",account.enabled())).toList());
    }
    @PostMapping
    public Map<String,Object> create(@CookieValue(name="llmgw_sid",required=false) String sid,
            @RequestHeader(name="X-CSRF-Token",required=false) String csrf, @RequestBody Body body) {
        var actor = ControlAccess.account(authorizer, sid);
        if (!authorizer.verifySessionMutation(sid, csrf)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"CSRF_FAILED");
        var target = ControlAccess.resolve(authorizer, orgs, sid, body.tenantId(), body.projectId());
        if (body.role() == null || !authorization.grantRole(new Subject(actor.role(),actor.scope()),target,body.role()).allowed())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"role grant denied");
        if (body.username() == null || body.username().isBlank() || body.password() == null || body.password().length() < 12
                || body.password().length() > 4096 || body.username().length() > 256)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"username and password of at least 12 characters required");
        Scope scope = switch(body.role()) {
            case PLATFORM_ADMIN -> Scope.platform();
            case TENANT_ADMIN -> Scope.tenant(target.tenantId());
            default -> Scope.project(target.tenantId(),target.projectId());
        };
        if (body.role() != Role.PLATFORM_ADMIN && scope.tenantId().isBlank()
                || ((body.role() == Role.PROJECT_ADMIN || body.role() == Role.PROJECT_VIEWER) && scope.projectId().isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"role scope required");
        String hash = passwords.hash(body.password());
        var account = audit.change(actor.id(),scope.tenantId(),scope.projectId(),"account.create","username="+body.username()+" role="+body.role(),
                () -> accounts.create(new Account(UUID.randomUUID().toString(),body.username(),hash,body.role(),scope,true)));
        return Map.of("id",account.id());
    }
    public record Body(String username,String password,Role role,String tenantId,String projectId) { }
}
