package io.github.leohou0430.llmgw.app;

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
 * Tenant/project management. Reads require CATALOG_READ; creating a tenant/project
 * requires platform-level TENANT_MANAGE (server-side). Successful creates are audited.
 */
@RestController
@RequestMapping("/control")
public class ControlOrgController {

	private final RequestAuthorizer authorizer;

	private final OrgStore orgs;

	private final AuditLog audit;

	private final io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage;

	public ControlOrgController(RequestAuthorizer authorizer, OrgStore orgs, AuditLog audit,
			io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storage) {
		this.authorizer = authorizer;
		this.orgs = orgs;
		this.audit = audit;
		this.storage = storage;
	}

	@GetMapping("/tenants")
	public ResponseEntity<Map<String, Object>> tenants(
			@CookieValue(name = "llmgw_sid", required = false) String sessionId) {
		var target = ControlAccess.target(authorizer, sessionId, null, null);
		authorize(sessionId, null, false, Action.CATALOG_READ, target);
		return ResponseEntity.ok(Map.of("tenants", orgs.tenants(target.tenantId())));
	}

	@PostMapping("/tenants")
	public ResponseEntity<Map<String, Object>> createTenant(
			@CookieValue(name = "llmgw_sid", required = false) String sessionId,
			@RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken, @RequestBody TenantBody body) {
		if (body.id() == null || body.id().isBlank() || body.name() == null || body.name().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id and name required");
		}
		authorize(sessionId, csrfToken, true, Action.TENANT_MANAGE, new Target(body.id(), ""));
		WriteGate.check(storage);
		audit.change(ControlAccess.account(authorizer, sessionId).id(), body.id(), "", "tenant.create",
				"name=" + body.name(), () -> {
					orgs.createTenant(body.id(), body.name());
					return null;
				});
		return ResponseEntity.ok(Map.of("id", body.id()));
	}

	@GetMapping("/projects")
	public ResponseEntity<Map<String, Object>> projects(
			@CookieValue(name = "llmgw_sid", required = false) String sessionId) {
		var target = ControlAccess.target(authorizer, sessionId, null, null);
		authorize(sessionId, null, false, Action.CATALOG_READ, target);
		return ResponseEntity.ok(Map.of("projects", orgs.projects(target.tenantId(), target.projectId())));
	}

	@PostMapping("/projects")
	public ResponseEntity<Map<String, Object>> createProject(
			@CookieValue(name = "llmgw_sid", required = false) String sessionId,
			@RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken, @RequestBody ProjectBody body) {
		if (body.id() == null || body.id().isBlank() || body.tenantId() == null || body.tenantId().isBlank()
				|| body.name() == null || body.name().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id/tenantId/name required");
		}
		authorize(sessionId, csrfToken, true, Action.TENANT_MANAGE, new Target(body.tenantId(), ""));
		WriteGate.check(storage);
		audit.change(ControlAccess.account(authorizer, sessionId).id(), body.tenantId(), body.id(), "project.create",
				"id=" + body.id(), () -> {
					orgs.createProject(body.id(), body.tenantId(), body.name());
					return null;
				});
		return ResponseEntity.ok(Map.of("id", body.id()));
	}

	private void authorize(String sessionId, String csrfToken, boolean mutating, Action action, Target target) {
		AuthOutcome outcome = authorizer
			.authorize(new AuthenticatedRequest(sessionId, csrfToken, mutating, action, target));
		if (outcome != AuthOutcome.ALLOW) {
			HttpStatus status = outcome == AuthOutcome.UNAUTHENTICATED ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
			throw new ResponseStatusException(status, outcome.name());
		}
	}

	public record TenantBody(String id, String name) {
	}

	public record ProjectBody(String id, String tenantId, String name) {
	}

}
