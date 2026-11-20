package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.PriceBook;
import io.github.leohou0430.llmgw.billing.sqlite.SqlitePriceBook;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService;
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
 * /control/prices（任务6）：客户售价版本发布。PRICE_PUBLISH 仅平台管理员； 读对 scope 内角色开放（BILLING_READ
 * 关联语义）。发布即新版本——改价不追溯 已冻结调用。
 */
@RestController
@RequestMapping("/control/prices")
public class ControlPriceController {

	private final PriceBook prices;

	private final RequestAuthorizer authorizer;

	private final AuditLog audit;

	private final RbacAuthorizationService rbac = new RbacAuthorizationService();

	public ControlPriceController(org.springframework.beans.factory.ObjectProvider<PriceBook> pricesProvider,
			RequestAuthorizer authorizer, AuditLog audit) {
		// price book 缺席（demo）时端点返回 501，绝不阻断启动。
		this.prices = pricesProvider.getIfAvailable();
		this.authorizer = authorizer;
		this.audit = audit;
	}

	private PriceBook pricesOr501() {
		if (prices == null) {
			throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
					"billing pack is not enabled (gateway.billing.enabled=false)");
		}
		return prices;
	}

	private Account account(String sid) {
		return ControlAccess.account(authorizer, sid);
	}

	private void requirePublish(Account account, String model) {
		if (account.role() != Role.PLATFORM_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "price publishing is platform-admin only");
		}
		audit.record(account.id(), "", "", "PRICE_PUBLISH", "model=" + model);
	}

	private void requireRead(Account account, String tenantId, String projectId) {
		if (!rbac
			.decide(new Subject(account.role(), account.scope()), Action.BILLING_READ, new Target(tenantId, projectId))
			.allowed()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "price read not allowed for this role/scope");
		}
	}

	/** 发布新版本：seed 即新版本（版本簿单调递增），0 = 该单位未定价。 */
	@PostMapping("/{model}")
	public Map<String, Object> publish(@PathVariable("model") String model, @RequestBody Map<String, Object> body,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		requirePublish(account, model);
		if (!(pricesOr501() instanceof SqlitePriceBook book)) {
			throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
					"price publishing requires the sqlite price book");
		}
		long in = Long.parseLong(String.valueOf(body.getOrDefault("inputNanoPerMillion", "0")));
		long out = Long.parseLong(String.valueOf(body.getOrDefault("outputNanoPerMillion", "0")));
		long image = Long.parseLong(String.valueOf(body.getOrDefault("nanoPerImage", "0")));
		long task = Long.parseLong(String.valueOf(body.getOrDefault("nanoPerTask", "0")));
		long second = Long.parseLong(String.valueOf(body.getOrDefault("nanoPerSecond", "0")));
		Price published = book.seed(model, in, out, image, task, second);
		Map<String, Object> outMap = new LinkedHashMap<>();
		outMap.put("model", published.model());
		outMap.put("version", published.version());
		outMap.put("inputNanoPerMillion", published.inputNanoPerMillion());
		outMap.put("outputNanoPerMillion", published.outputNanoPerMillion());
		outMap.put("nanoPerImage", published.nanoPerImage());
		outMap.put("nanoPerTask", published.nanoPerTask());
		outMap.put("nanoPerSecond", published.nanoPerSecond());
		return outMap;
	}

	@GetMapping("/{model}")
	public List<Map<String, Object>> versions(@PathVariable("model") String model,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		requireRead(account, account.scope().tenantId(), account.scope().projectId());
		return pricesOr501().all().stream().filter(p -> p.model().equals(model)).map(p -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("version", p.version());
			out.put("inputNanoPerMillion", p.inputNanoPerMillion());
			out.put("outputNanoPerMillion", p.outputNanoPerMillion());
			out.put("nanoPerImage", p.nanoPerImage());
			out.put("nanoPerTask", p.nanoPerTask());
			out.put("nanoPerSecond", p.nanoPerSecond());
			out.put("frozenAtEpochMs", p.frozenAt());
			return out;
		}).toList();
	}

}
