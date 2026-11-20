package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /control/recordings（任务6）：正文记录元数据与正文。RECORDING_READ 按真实 scope（观察者仅元数据）；正文读取走
 * BODY_READ（三级管理员可见且审计）， 导出走 BODY_EXPORT（仅平台管理员）。
 */
@RestController
@RequestMapping("/control/recordings")
public class ControlRecordingController {

	private final RequestAuthorizer authorizer;

	private final AuditLog audit;

	private final RbacAuthorizationService rbac = new RbacAuthorizationService();

	private final Path recordingDirectory;

	public ControlRecordingController(RequestAuthorizer authorizer, AuditLog audit) {
		this.authorizer = authorizer;
		this.audit = audit;
		this.recordingDirectory = Path.of(System.getProperty("llmgw.recording.directory",
				System.getProperty("java.io.tmpdir") + "/llmgw-recordings"));
	}

	private Account account(String sid) {
		return ControlAccess.account(authorizer, sid);
	}

	private void requireRead(Account account, Action action, Target target) {
		if (!rbac.decide(new Subject(account.role(), account.scope()), action, target).allowed()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, action + " not allowed for this role/scope");
		}
	}

	private Path fileOf(String callId) {
		// 拒绝任意路径：只允许安全文件名字符。
		String stem = callId.replaceAll("[^A-Za-z0-9._-]", "_");
		return recordingDirectory.resolve(stem + ".jsonl").normalize().toAbsolutePath();
	}

	/** 元数据（RECORDING_READ：观察者可见）：行数、大小、终态——不含正文。 */
	@GetMapping("/{callId}")
	public Map<String, Object> meta(@PathVariable("callId") String callId,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		requireRead(account, Action.RECORDING_READ,
				new Target(account.scope().tenantId(), account.scope().projectId()));
		Path file = fileOf(callId);
		if (!Files.exists(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recording not found");
		}
		try {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			String outcome = lines.stream()
				.filter(l -> l.contains("\"t\":\"outcome\""))
				.reduce((a, b) -> b)
				.map(l -> l.contains("COMPLETE") ? "COMPLETE" : "INCOMPLETE")
				.orElse("OPEN");
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("callId", callId);
			out.put("eventLines", lines.stream().filter(l -> l.contains("\"t\":\"event\"")).count());
			out.put("outcome", outcome);
			out.put("bytes", Files.size(file));
			return out;
		}
		catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot read recording meta");
		}
	}

	/** 正文（BODY_READ 三级可见且审计）；观察者不可见。 */
	@GetMapping("/{callId}/body")
	public List<Map<String, Object>> body(@PathVariable("callId") String callId,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		requireRead(account, Action.BODY_READ, new Target(account.scope().tenantId(), account.scope().projectId()));
		audit.record(account.id(), account.scope().tenantId(), account.scope().projectId(), "BODY_READ",
				"recording " + callId);
		Path file = fileOf(callId);
		if (!Files.exists(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recording not found");
		}
		try {
			return Files.readAllLines(file, StandardCharsets.UTF_8)
				.stream()
				.filter(l -> l.contains("\"t\":\"event\""))
				.map(l -> Map.<String, Object>of("line", l))
				.toList();
		}
		catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot read recording body");
		}
	}

	/** 导出（BODY_EXPORT 仅平台管理员，数据离开控制面边界写审计）。 */
	@GetMapping("/{callId}/export")
	public Map<String, Object> export(@PathVariable("callId") String callId,
			@CookieValue(name = "llmgw_sid", required = false) String sid) {
		var account = account(sid);
		if (account.role() != Role.PLATFORM_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "recording export is platform-admin only");
		}
		audit.record(account.id(), "", "", "BODY_EXPORT", "recording " + callId);
		Path file = fileOf(callId);
		if (!Files.exists(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recording not found");
		}
		try {
			return Map.of("callId", callId, "content", Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (Exception e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot export recording");
		}
	}

}
