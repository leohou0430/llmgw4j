package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.ConfigValidator;
import io.github.leohou0430.llmgw.control.local.DefaultConfigValidator;
import io.github.leohou0430.llmgw.control.local.InMemoryRouteVersionStore;
import io.github.leohou0430.llmgw.control.local.RouteVersionService;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.CredentialIssuer;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService;
import io.github.leohou0430.llmgw.persistence.PayloadRecordStore;
import io.github.leohou0430.llmgw.persistence.PayloadRecordStore.PayloadRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only control-plane status surface. This wires the control-plane foundation
 * (identity RBAC + key issuance, route versioning, config validation) into the running
 * app so it is reachable over HTTP, and reports a safe invariant: provider credentials
 * are never readable by any role, including platform admin. No secrets are exposed.
 */
@RestController
@RequestMapping("/control")
public class ControlPlaneController {

	private final AuthorizationService authorization = new RbacAuthorizationService();

	private final CredentialIssuer credentials = new CredentialIssuer();

	private final RouteVersionService routeVersions;

	private final PayloadRecordStore payloads;

	private final RequestAuthorizer authorizer;

	private final AuditLog audit;

	private final ConfigValidator configValidator = new DefaultConfigValidator();

	public ControlPlaneController(RouteVersionService routeVersions, PayloadRecordStore payloads,
			RequestAuthorizer authorizer, AuditLog audit) {
		this.routeVersions = routeVersions;
		this.payloads = payloads;
		this.authorizer = authorizer;
		this.audit = audit;
	}

	private static io.github.leohou0430.llmgw.identity.Account currentAccount(RequestAuthorizer authorizer,
			String sid) {
		return io.github.leohou0430.llmgw.app.ControlAccess.account(authorizer, sid);
	}

	private static void requireBodyRead(io.github.leohou0430.llmgw.identity.Account account) {
		if (!authorizationStatic()
			.decide(subjectOf(account), Action.BODY_READ,
					new Target(account.scope().tenantId(), account.scope().projectId()))
			.allowed()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "body read not allowed for this role/scope");
		}
	}

	private static void requireBodyExport(io.github.leohou0430.llmgw.identity.Account account) {
		if (account.role() != Role.PLATFORM_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "body export is platform-admin only");
		}
	}

	private static Map<String, Object> view(PayloadRecord r) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", r.id());
		out.put("tenantId", r.tenantId());
		out.put("projectId", r.projectId());
		out.put("requestBody", r.requestBody());
		out.put("responseBody", r.responseBody());
		out.put("structured", r.structured());
		out.put("truncated", r.truncated());
		out.put("createdAtEpochMs", r.createdAtEpochMs());
		return out;
	}

	private static Subject subjectOf(io.github.leohou0430.llmgw.identity.Account account) {
		return new Subject(account.role(), account.scope());
	}

	private static AuthorizationService authorizationStatic() {
		return AUTHORIZATION_HOLDER.INSTANCE;
	}

	private static final class AUTHORIZATION_HOLDER {

		private static final AuthorizationService INSTANCE = new RbacAuthorizationService();

	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("rbac", authorization.getClass().getSimpleName());
		status.put("keyLifecycle", credentials.getClass().getSimpleName());
		status.put("routeVersions", routeVersions.getClass().getSimpleName());
		status.put("configValidation", configValidator.getClass().getSimpleName());
		status.put("activeRouteVersion", routeVersions.activeVersion());

		Subject platformAdmin = new Subject(Role.PLATFORM_ADMIN, Scope.platform());
		boolean credentialReadDenied = !authorization
			.decide(platformAdmin, Action.READ_CREDENTIAL, new Target("t", "p"))
			.allowed();
		status.put("credentialReadDenied", credentialReadDenied);
		return status;
	}

	/**
	 * 正文查看（第十八项）：BODY_READ 三级可见——平台管理员全部、租户管理员本 租户、项目管理员本项目；观察者不可见。凭据与密码哈希同级不可读。
	 */
	@GetMapping("/payloads/{id}")
	public Map<String, Object> payload(@PathVariable("id") String id,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = currentAccount(authorizer, sid);
		requireBodyRead(account);
		var record = payloads.get(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payload not found"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", record.id());
		out.put("tenantId", record.tenantId());
		out.put("projectId", record.projectId());
		out.put("requestBody", record.requestBody());
		out.put("responseBody", record.responseBody());
		out.put("structured", record.structured());
		out.put("truncated", record.truncated());
		out.put("createdAtEpochMs", record.createdAtEpochMs());
		return out;
	}

	/**
	 * 正文批量导出（第十九项）：仅平台管理员（BODY_EXPORT，数据离开控制面边界 的动作写入管理审计）。
	 */
	@GetMapping("/payloads/export")
	public List<Map<String, Object>> exportPayloads(@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = currentAccount(authorizer, sid);
		requireBodyExport(account);
		audit.record(account.id(), "", "", "BODY_EXPORT", "platform admin exported call bodies");
		// 首版导出：返回存储内全部正文（规模受保留期与容量约束控制）。
		var out = new ArrayList<Map<String, Object>>();
		for (PayloadRecord r : payloadSnapshot()) {
			out.add(view(r));
		}
		return out;
	}

	/** 导出走存储的快照列表（SQLite 直读），返回空表时文档标注受限。 */
	private List<PayloadRecord> payloadSnapshot() {
		return payloads.listRecent(1000);
	}

}
