package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.ReversalCommand;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /control/billing 族（任务6）：账户、充值、冲正、预留/对账。账务写 （BILLING_MANAGE）仅平台管理员；读（BILLING_READ）按真实
 * scope——租户/ 项目角色只读本租户/项目，观察者只读。每个账务写都记录管理审计。
 */
@RestController
@RequestMapping("/control/billing")
public class ControlBillingController {

	private final BillingLedger ledger;

	private final RequestAuthorizer authorizer;

	private final AuditLog audit;

	private final io.github.leohou0430.llmgw.identity.AuthorizationService rbac = new io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService();

	public ControlBillingController(org.springframework.beans.factory.ObjectProvider<BillingLedger> ledgerProvider,
			RequestAuthorizer authorizer, AuditLog audit) {
		// billing pack 缺席（demo）时控制器保留但端点返回 501，绝不阻断启动。
		this.ledger = ledgerProvider.getIfAvailable();
		this.authorizer = authorizer;
		this.audit = audit;
	}

	private BillingLedger ledgerOr501() {
		if (ledger == null) {
			throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
					"billing pack is not enabled (gateway.billing.enabled=false)");
		}
		return ledger;
	}

	private Account account(String sid) {
		return ControlAccess.account(authorizer, sid);
	}

	private void require(Account account, Action action, Target target) {
		if (!rbac.decide(new Subject(account.role(), account.scope()), action, target).allowed()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, action + " not allowed for this role/scope");
		}
	}

	private void requirePlatformManage(Account account, String tenantId, String detail) {
		if (account.role() != Role.PLATFORM_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "billing writes are platform-admin only");
		}
		audit.record(account.id(), tenantId, "", "BILLING_MANAGE", detail);
	}

	@GetMapping("/accounts/{tenantId}")
	public Map<String, Object> account(@PathVariable("tenantId") String tenantId,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		require(account, Action.BILLING_READ, new Target(tenantId, account.scope().projectId()));
		return ledgerOr501().account(tenantId).<Map<String, Object>>map(a -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("tenantId", a.tenantId());
			out.put("currency", a.currency());
			out.put("balanceNano", a.balanceNano());
			out.put("overdraftNano", a.overdraftNano());
			out.put("carryNano", a.carryNano());
			return out;
		}).orElseGet(() -> Map.of("tenantId", tenantId, "exists", false));
	}

	@GetMapping("/reserved/{tenantId}")
	public Map<String, Object> reserved(@PathVariable("tenantId") String tenantId,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		require(account, Action.BILLING_READ, new Target(tenantId, account.scope().projectId()));
		return Map.of("tenantId", tenantId, "reservedNano", ledgerOr501().reservedNano(tenantId));
	}

	/** 租户的待核验预留（重启恢复视角）。 */
	@GetMapping("/reservations/{tenantId}/pending")
	public List<Map<String, Object>> pending(@PathVariable("tenantId") String tenantId,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		require(account, Action.BILLING_READ, new Target(tenantId, account.scope().projectId()));
		return ledgerOr501().pendingVerifications(tenantId).stream().map(r -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("id", r.id());
			out.put("projectId", r.projectId());
			out.put("model", r.model());
			out.put("priceVersion", r.priceVersion());
			out.put("amountNano", r.amountNano());
			return out;
		}).toList();
	}

	/** 幂等充值（凭证号防重）；负金额等价手工冲正（受控冲正走 /reverse）。 */
	@PostMapping("/recharge")
	public Map<String, Object> recharge(@RequestBody Map<String, Object> body,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		String tenantId = String.valueOf(body.get("tenantId"));
		long amountNano = Long.parseLong(String.valueOf(body.get("amountNano")));
		String voucherNo = String.valueOf(body.get("voucherNo"));
		requirePlatformManage(account, tenantId, "recharge " + amountNano + " nano voucher=" + voucherNo);
		var result = ledgerOr501().recharge(tenantId, amountNano, voucherNo, account.id());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("entryId", result.entryId());
		out.put("tenantId", result.tenantId());
		out.put("amountNano", result.amountNano());
		out.put("voucherNo", result.voucherNo());
		out.put("alreadyRecorded", result.alreadyRecorded());
		return out;
	}

	/** 受控冲正（第十一项确认）：引用原充值流水整笔负入账，禁跨租户/重复冲正。 */
	@PostMapping("/reverse")
	public Map<String, Object> reverse(@RequestBody Map<String, Object> body,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		String tenantId = String.valueOf(body.get("tenantId"));
		String originalEntryId = String.valueOf(body.get("originalEntryId"));
		String reason = String.valueOf(body.get("reason"));
		String operationId = String.valueOf(body.get("operationId"));
		requirePlatformManage(account, tenantId,
				"reverse entry=" + originalEntryId + " op=" + operationId + " reason=" + reason);
		var result = ledgerOr501()
			.reverse(new ReversalCommand(originalEntryId, tenantId, account.id(), reason, operationId));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("entryId", result.entryId());
		out.put("amountNano", result.amountNano());
		out.put("operationId", result.voucherNo());
		out.put("alreadyRecorded", result.alreadyRecorded());
		return out;
	}

	/** 待核验按人工核实结算（actualNano 来自核实证据）。 */
	@PostMapping("/reservations/{reservationId}/settle-pending")
	public Map<String, Object> settlePending(@PathVariable("reservationId") String reservationId,
			@RequestBody Map<String, Object> body, @CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		long actualNano = Long.parseLong(String.valueOf(body.getOrDefault("actualNano", "0")));
		String reason = String.valueOf(body.getOrDefault("reason", "manual verification"));
		requirePlatformManage(account, account.scope().tenantId(),
				"settle-pending " + reservationId + " actual=" + actualNano);
		var result = ledgerOr501().settlePending(reservationId, actualNano, reason, account.id());
		return Map.of("reservationId", result.reservationId(), "postedNano", result.postedNano(), "carryNano",
				result.carryNano());
	}

	/** 待核验确认失败不收费：释放预留。 */
	@PostMapping("/reservations/{reservationId}/release-pending")
	public Map<String, Object> releasePending(@PathVariable("reservationId") String reservationId,
			@RequestBody Map<String, Object> body, @CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		String reason = String.valueOf(body.getOrDefault("reason", "verified failure"));
		requirePlatformManage(account, account.scope().tenantId(),
				"release-pending " + reservationId + " reason=" + reason);
		ledgerOr501().releasePending(reservationId, reason, account.id());
		return Map.of("reservationId", reservationId, "released", true);
	}

}
