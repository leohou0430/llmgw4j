package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request accounting context. Completion tokens accumulate while SSE chunks flow.
 * <p>
 * An upstream {@code usage} chunk is authoritative: it REPLACES the running per-delta
 * estimate instead of being added on top of it. Without that rule a normal stream with N
 * deltas plus one usage frame is billed twice.
 *
 * <p>
 * v10 timing: the three marks below are the only per-request work added for AI
 * observability. They are plain atomic CAS/long writes (no allocation, no registry
 * lookup); all formatting, Timer recording and audit enrichment happen on the governance
 * side car after the response has been delivered.
 */
public final class GatewayContext {

	private final String keyId;

	private final ProtocolKind kind;

	private final String model;

	private final String routeId;

	private final boolean stream;

	private final String requestId;

	private final long startNanos;

	private final int promptTokens;

	private final int units;

	private final AtomicInteger reportedPrompt = new AtomicInteger(-1);

	private String reservationId = "";

	public void setPromptTokens(int tokens) {
		reportedPrompt.set(Math.max(0, tokens));
	}

	public void setReservationId(String id) {
		reservationId = id;
	}

	public String reservationId() {
		return reservationId;
	}

	private final AtomicInteger estimatedCompletion = new AtomicInteger();

	/** -1 = the upstream never reported usage; otherwise the authoritative value. */
	private final AtomicInteger reportedCompletion = new AtomicInteger(-1);

	private volatile boolean cached;

	/** v13 $ admission reservation (micro-USD); 0 = no budget applied. */
	private volatile long budgetReservedMicroUsd;

	/** B4: ownership group carried from the key ("" = ungrouped). */
	private volatile String group = "";

	/** B4: group-level $ reservation (micro-USD); 0 = no group budget applied. */
	private volatile long groupBudgetReservedMicroUsd;

	/**
	 * Chapter-9 §9.4.5 归因链：task_id（协议任务对象）→ session_id / turn_id
	 * （调用方权威执行上下文，经关联头传入并绑定已认证主体）→ call_id（billing 预留标识）→ attempt_id（真实上游尝试）。缺失即
	 * unattributed，网关不编造。
	 */
	private volatile String sessionId = "";

	private volatile String turnId = "";

	private volatile String taskId = "";

	private volatile String billingCallId = "";

	private volatile String finalAttemptId = "";

	/** 认证主体映射出的财务归属（Chapter-9 §9.5.2）。 */
	private volatile String tenantId = "";

	private volatile String projectId = "";

	/**
	 * Chapter-9 §9.2.2 对账契约：实际服务本次输出的上游模型（fallback/ targetModel 重写后）。与
	 * {@code model()}（客户请求别名）分别记录； 缓存命中或未发上游时留空（无上游模型服务本次输出）。
	 */
	private volatile String servedModel = "";

	/** Chapter-9 §9.5.3：判定时命中的策略版本（0 = 未盖章，finish 回退当前值）。 */
	private volatile long policyVersion;

	/** 真实上游尝试次数（含同路由重试与 fallback）。 */
	private volatile int attemptCount;

	/** 0 = not started. First CAS wins so a fallback attempt does not move T0. */
	private final AtomicLong upstreamStartNanos = new AtomicLong();

	/** 0 = no chunk yet. Time of the FIRST upstream byte delivered downstream. */
	private final AtomicLong firstChunkNanos = new AtomicLong();

	/** 0 = not finished. Only set on the non-streaming path. */
	private final AtomicLong upstreamEndNanos = new AtomicLong();

	public GatewayContext(String keyId, String model, String routeId, boolean stream, int promptTokens) {
		this(keyId, ProtocolKind.CHAT, model, routeId, stream, promptTokens, 1, "", System.nanoTime());
	}

	public GatewayContext(String keyId, String model, String routeId, boolean stream, int promptTokens,
			String requestId) {
		this(keyId, ProtocolKind.CHAT, model, routeId, stream, promptTokens, 1, requestId, System.nanoTime());
	}

	/** Start the clock at request arrival, not after admission (v10). */
	public GatewayContext(String keyId, String model, String routeId, boolean stream, int promptTokens,
			String requestId, long arrivedNanos) {
		this(keyId, ProtocolKind.CHAT, model, routeId, stream, promptTokens, 1, requestId, arrivedNanos);
	}

	public GatewayContext(String keyId, ProtocolKind kind, String model, String routeId, boolean stream,
			int promptTokens, int units, String requestId, long arrivedNanos) {
		this.keyId = keyId;
		this.kind = kind;
		this.model = model;
		this.routeId = routeId;
		this.stream = stream;
		this.requestId = requestId == null ? "" : requestId;
		this.promptTokens = promptTokens;
		this.units = Math.max(1, units);
		this.startNanos = arrivedNanos;
	}

	/** Called immediately before an upstream attempt is issued. */
	public void markUpstreamStart() {
		upstreamStartNanos.compareAndSet(0, System.nanoTime());
	}

	/** Called on the first non-blank SSE line; TTFT is measured from arrival. */
	public void markFirstChunk() {
		firstChunkNanos.compareAndSet(0, System.nanoTime());
	}

	/** Called once a non-streaming upstream body has been fully received. */
	public void markUpstreamEnd() {
		upstreamEndNanos.set(System.nanoTime());
	}

	/** Gateway work before the response is committed (headers must be set by then). */
	public long preflightNanos() {
		long upstream = upstreamStartNanos.get();
		return Math.max(0, (upstream == 0 ? System.nanoTime() : upstream) - startNanos);
	}

	/** Time to first token, or -1 when no chunk arrived (failure / non-stream). */
	public long ttftNanos() {
		long first = firstChunkNanos.get();
		return first == 0 ? -1 : Math.max(0, first - startNanos);
	}

	/**
	 * Upstream time: full body for non-streaming, time-to-first-byte for SSE. Returns -1
	 * when no upstream attempt was made (cache hit / admission deny).
	 */
	public long upstreamNanos() {
		long start = upstreamStartNanos.get();
		if (start == 0) {
			return -1;
		}
		long first = firstChunkNanos.get();
		long end = upstreamEndNanos.get();
		long stop = first != 0 ? first : (end != 0 ? end : System.nanoTime());
		return Math.max(0, stop - start);
	}

	/** Total latency minus upstream time: what the gateway itself contributed. */
	public long gatewayOverheadNanos() {
		long total = Math.max(0, System.nanoTime() - startNanos);
		long upstream = upstreamNanos();
		return upstream < 0 ? total : Math.max(0, total - upstream);
	}

	/** Allocation-light 0.1ms formatting (String.format costs microseconds). */
	public static String formatMillis(long nanos) {
		if (nanos <= 0) {
			return "0.0";
		}
		long tenths = (nanos + 50_000L) / 100_000L;
		return (tenths / 10) + "." + (tenths % 10);
	}

	/** One decimal place in milliseconds, for audit events. */
	public static double millis(long nanos) {
		return nanos <= 0 ? 0.0 : Math.round(nanos / 100_000.0) / 10.0;
	}

	/** Accumulate an estimate from a delta's text (used only when usage is absent). */
	public void addDeltaTokens(String delta) {
		estimatedCompletion.addAndGet(ProtocolCodec.estimateTokens(delta));
	}

	/** Authoritative upstream usage; overrides any estimate accumulated so far. */
	public void setCompletionTokens(int tokens) {
		reportedCompletion.set(Math.max(0, tokens));
	}

	/** Exact value for a non-streaming response (or a stream with no usage chunk). */
	public void setCompletionTokensIfAbsent(int tokens) {
		reportedCompletion.compareAndSet(-1, Math.max(0, tokens));
	}

	public void markCached() {
		this.cached = true;
	}

	/** $ reservation held at admission; settled by the budget ledger at finish. */
	public void setBudgetReservedMicroUsd(long microUsd) {
		this.budgetReservedMicroUsd = microUsd;
	}

	public long budgetReservedMicroUsd() {
		return budgetReservedMicroUsd;
	}

	public void setGroup(String group) {
		this.group = group == null ? "" : group;
	}

	public String group() {
		return group;
	}

	public void setGroupBudgetReservedMicroUsd(long microUsd) {
		this.groupBudgetReservedMicroUsd = microUsd;
	}

	public long groupBudgetReservedMicroUsd() {
		return groupBudgetReservedMicroUsd;
	}

	/** 关联头绑定（调用方权威执行上下文；不参与准入与配额）。 */
	public void setSessionId(String id) {
		this.sessionId = sanitizeCorrelation(id);
	}

	public String sessionId() {
		return sessionId;
	}

	public void setTurnId(String id) {
		this.turnId = sanitizeCorrelation(id);
	}

	public String turnId() {
		return turnId;
	}

	public void setTaskId(String id) {
		this.taskId = sanitizeCorrelation(id);
	}

	public String taskId() {
		return taskId;
	}

	/** 计费账本预留标识（call_id 归因）。 */
	public void setBillingCallId(String id) {
		this.billingCallId = id == null ? "" : id;
	}

	public String billingCallId() {
		return billingCallId;
	}

	/** 认证主体映射出的财务归属。 */
	public void setTenantId(String id) {
		this.tenantId = id == null ? "" : id;
	}

	public String tenantId() {
		return tenantId;
	}

	public void setProjectId(String id) {
		this.projectId = id == null ? "" : id;
	}

	public String projectId() {
		return projectId;
	}

	/** 实际服务的上游模型（targetModel 重写后；留空 = 无上游服务本次输出）。 */
	public void setServedModel(String model) {
		this.servedModel = model == null ? "" : model;
	}

	public String servedModel() {
		return servedModel;
	}

	/** 最终服务的真实上游尝试标识。 */
	public void setFinalAttemptId(String id) {
		this.finalAttemptId = id == null ? "" : id;
	}

	public String finalAttemptId() {
		return finalAttemptId;
	}

	public void incrementAttemptCount() {
		attemptCount++;
	}

	public int attemptCount() {
		return attemptCount;
	}

	public void setPolicyVersion(long version) {
		this.policyVersion = Math.max(0, version);
	}

	public long policyVersion() {
		return policyVersion;
	}

	/**
	 * 关联头净化：可打印 ASCII、限长 128、无控制字符。非法值剥为空串 （unattributed）而不是拒绝——关联信息不参与准入，伪造只影响调用者
	 * 自己主体的关联记录，无法越权污染他人账本。
	 */
	private static String sanitizeCorrelation(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty() || trimmed.length() > 128) {
			return "";
		}
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				return "";
			}
		}
		return trimmed;
	}

	/** 归因是否完整（至少有会话或轮次一层）。 */
	public boolean attributed() {
		return !sessionId.isEmpty() || !turnId.isEmpty() || !taskId.isEmpty();
	}

	public String keyId() {
		return keyId;
	}

	public ProtocolKind kind() {
		return kind;
	}

	public String model() {
		return model;
	}

	public String routeId() {
		return routeId;
	}

	public boolean stream() {
		return stream;
	}

	/** Request units (images: n; video create: 1 task; text protocols: 1). */
	public int units() {
		return units;
	}

	/** Correlation id echoed to the client and written into audit events. */
	public String requestId() {
		return requestId;
	}

	public int promptTokens() {
		return reportedPrompt.get() >= 0 ? reportedPrompt.get() : promptTokens;
	}

	/** The exact amount charged to the token bucket / reserved at admission. */
	public int reservedTokens() {
		return Math.max(1, promptTokens);
	}

	public int completionTokens() {
		int reported = reportedCompletion.get();
		return reported >= 0 ? reported : estimatedCompletion.get();
	}

	/** Raw upstream usage report; -1 when the upstream never emitted a usage frame. */
	public int reportedPromptTokensRaw() {
		return reportedPrompt.get();
	}

	/** Raw upstream usage report; -1 when the upstream never emitted a usage frame. */
	public int reportedCompletionTokensRaw() {
		return reportedCompletion.get();
	}

	public boolean cached() {
		return cached;
	}

	public long latencyMs() {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

}
