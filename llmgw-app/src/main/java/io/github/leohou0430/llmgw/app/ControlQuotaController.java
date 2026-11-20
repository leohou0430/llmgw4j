package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.local.ManagedKeyService;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteUsageLedger;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/control/quotas")
public class ControlQuotaController {

	private final RequestAuthorizer authorizer;

	private final OrgStore orgs;

	private final SqliteUsageLedger usage;

	private final ManagedKeyService keys;

	private final AuditLog audit;

	public ControlQuotaController(RequestAuthorizer authorizer, OrgStore orgs, SqliteUsageLedger usage,
			ManagedKeyService keys, AuditLog audit) {
		this.authorizer = authorizer;
		this.orgs = orgs;
		this.usage = usage;
		this.keys = keys;
		this.audit = audit;
	}

	@PostMapping
	public Map<String, Object> update(@CookieValue(name = "llmgw_sid", required = false) String sid,
			@RequestHeader(name = "X-CSRF-Token", required = false) String csrf, @RequestBody QuotaBody body) {
		var target = ControlAccess.resolve(authorizer, orgs, sid, body.tenantId(), body.projectId());
		ControlAccess.require(authorizer, sid, csrf, true, Action.KEY_MANAGE, target);
		if (target.projectId().isBlank() || body.quotaTokens() < 0 || body.reason() == null || body.reason().isBlank())
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "project, nonnegative quota and reason required");
		String actor = ControlAccess.account(authorizer, sid).id();
		if (body.keyId() == null || body.keyId().isBlank()) {
			var role = ControlAccess.account(authorizer, sid).role();
			if (role != io.github.leohou0430.llmgw.identity.Role.PLATFORM_ADMIN
					&& role != io.github.leohou0430.llmgw.identity.Role.TENANT_ADMIN)
				throw new ResponseStatusException(HttpStatus.FORBIDDEN,
						"tenant administrator required to change project allowance");
			audit.change(actor, target.tenantId(), target.projectId(), "quota.project",
					"quota=" + body.quotaTokens() + " reason=" + body.reason(), () -> {
						usage.setProjectQuota(target.tenantId(), target.projectId(), body.quotaTokens(), actor,
								body.reason());
						return null;
					});
		}
		else {
			if (!keys.belongsTo(body.keyId(), target))
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "key outside project");
			audit.change(actor, target.tenantId(), target.projectId(), "quota.key",
					"key=" + body.keyId() + " quota=" + body.quotaTokens() + " reason=" + body.reason(), () -> {
						usage.setKeyQuota(body.keyId(), body.quotaTokens(), actor, body.reason());
						return null;
					});
		}
		return Map.of("updated", true, "projectUsed", usage.projectUsed(target.tenantId(), target.projectId()));
	}

	@GetMapping("/pending")
	public Map<String, Object> pending(@CookieValue(name = "llmgw_sid", required = false) String sid,
			@RequestParam String tenantId, @RequestParam String projectId) {
		var target = ControlAccess.resolve(authorizer, orgs, sid, tenantId, projectId);
		ControlAccess.require(authorizer, sid, null, false, Action.USAGE_READ, target);
		return Map.of("pending", usage.pending(target.tenantId(), target.projectId()));
	}

	@PostMapping("/reconcile")
	public Map<String, Object> reconcile(@CookieValue(name = "llmgw_sid", required = false) String sid,
			@RequestHeader(name = "X-CSRF-Token", required = false) String csrf, @RequestBody ReconcileBody body) {
		var target = ControlAccess.resolve(authorizer, orgs, sid, body.tenantId(), body.projectId());
		ControlAccess.require(authorizer, sid, csrf, true, Action.KEY_MANAGE, target);
		var actor = ControlAccess.account(authorizer, sid);
		if (actor.role() != io.github.leohou0430.llmgw.identity.Role.PLATFORM_ADMIN
				&& actor.role() != io.github.leohou0430.llmgw.identity.Role.TENANT_ADMIN)
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"tenant administrator required to reconcile unknown usage");
		audit.change(actor.id(), target.tenantId(), target.projectId(), "quota.reconcile",
				"reservation=" + body.reservationId() + " reason=" + body.reason(), () -> {
					usage.reconcile(target.tenantId(), target.projectId(), body.reservationId(), body.promptTokens(),
							body.completionTokens(), actor.id(), body.reason());
					return null;
				});
		return Map.of("reconciled", true);
	}

	public record ReconcileBody(String tenantId, String projectId, String reservationId, long promptTokens,
			long completionTokens, String reason) {
	}

	public record QuotaBody(String tenantId, String projectId, String keyId, long quotaTokens, String reason) {
	}

}
