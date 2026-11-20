package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.approval.ApprovalService;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /control/approvals 族（Chapter-9 §9.5.4）：待决审批列表、决策与撤销。 决策（APPROVAL_DECIDE）按审批自身的
 * tenant/project 范围做 RBAC——租户 管理员限本租户、项目管理员限本项目；每次决策/撤销写管理审计（原因必填）。 批准 ≠ 放行：执行方在真正执行前必须经
 * {@code revalidate} 重校验主体与 参数摘要；本控制器只推进状态机。
 */
@RestController
@RequestMapping("/control/approvals")
public class ControlApprovalController {

	private final ApprovalService approvals;

	private final RequestAuthorizer authorizer;

	private final AuditLog audit;

	private final io.github.leohou0430.llmgw.identity.AuthorizationService rbac = new io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService();

	public ControlApprovalController(ObjectProvider<ApprovalService> approvalsProvider, RequestAuthorizer authorizer,
			AuditLog audit) {
		this.approvals = approvalsProvider.getIfAvailable();
		this.authorizer = authorizer;
		this.audit = audit;
	}

	private ApprovalService approvalsOr501() {
		if (approvals == null) {
			throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
					"approval workflow requires the control database");
		}
		return approvals;
	}

	private Account account(String sid) {
		return ControlAccess.account(authorizer, sid);
	}

	private void require(Account account, Action action, Target target) {
		if (!rbac
			.decide(new io.github.leohou0430.llmgw.identity.Subject(account.role(), account.scope()), action, target)
			.allowed()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, action + " not allowed for this role/scope");
		}
	}

	/** 待决审批列表：范围必须落在调用者的 scope 内（平台管理员全局）。 */
	@GetMapping
	public Map<String, Object> pending(@RequestParam("tenantId") String tenantId,
			@RequestParam(value = "projectId", required = false) String projectId,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		require(account, Action.APPROVAL_READ,
				new Target(tenantId, projectId == null ? account.scope().projectId() : projectId));
		List<Map<String, Object>> items = approvalsOr501().pending(tenantId)
			.stream()
			.filter(a -> projectId == null || projectId.isBlank() || a.projectId().equals(projectId))
			.map(ControlApprovalController::view)
			.toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("tenantId", tenantId);
		out.put("items", items);
		return out;
	}

	/** 决策：approve=true 批准 / false 拒绝。重复决策幂等（状态机吸收）。 */
	@PostMapping("/{id}/decide")
	public Map<String, Object> decide(@PathVariable("id") String id, @RequestBody DecisionRequest request,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		if (request.reason() == null || request.reason().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
		}
		var approval = approvalsOr501().get(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown approval"));
		require(account, Action.APPROVAL_DECIDE, new Target(approval.tenantId(), approval.projectId()));
		var decided = approvalsOr501().decide(id, request.approve(), account.id(), request.reason(),
				System.currentTimeMillis());
		audit.record(account.id(), decided.tenantId(), decided.projectId(), "APPROVAL_DECIDE",
				"approval=" + id + " decision=" + decided.state() + " target=" + decided.targetKind() + "/"
						+ decided.targetName() + " reason=" + request.reason());
		return view(decided);
	}

	/** 撤销：仅已批准可撤销；撤销后执行方 revalidate 一律 false。 */
	@PostMapping("/{id}/revoke")
	public Map<String, Object> revoke(@PathVariable("id") String id, @RequestBody DecisionRequest request,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		if (request.reason() == null || request.reason().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
		}
		var approval = approvalsOr501().get(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown approval"));
		require(account, Action.APPROVAL_DECIDE, new Target(approval.tenantId(), approval.projectId()));
		var revoked = approvalsOr501().revoke(id, account.id(), request.reason(), System.currentTimeMillis());
		audit.record(account.id(), revoked.tenantId(), revoked.projectId(), "APPROVAL_REVOKE", "approval=" + id
				+ " target=" + revoked.targetKind() + "/" + revoked.targetName() + " reason=" + request.reason());
		return view(revoked);
	}

	private static Map<String, Object> view(ApprovalService.Approval approval) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", approval.id());
		out.put("subjectRef", approval.subjectRef());
		out.put("tenantId", approval.tenantId());
		out.put("projectId", approval.projectId());
		out.put("targetKind", approval.targetKind());
		out.put("targetName", approval.targetName());
		out.put("toolVersion", approval.toolVersion());
		out.put("paramsDigest", approval.paramsDigest());
		out.put("risk", approval.risk());
		out.put("state", approval.state().name());
		out.put("createdAtEpochMs", approval.createdAtEpochMs());
		out.put("expiresAtEpochMs", approval.expiresAtEpochMs());
		if (approval.decidedBy() != null) {
			out.put("decidedBy", approval.decidedBy());
		}
		if (approval.decisionReason() != null) {
			out.put("decisionReason", approval.decisionReason());
		}
		return out;
	}

	/** 决策/撤销请求体：原因必填（审计可解释）。 */
	public record DecisionRequest(boolean approve, String reason) {
	}

}
