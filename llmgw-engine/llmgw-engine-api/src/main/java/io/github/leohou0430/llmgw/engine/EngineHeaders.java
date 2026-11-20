package io.github.leohou0430.llmgw.engine;

/** Wire-level header and media-type names shared by every data plane. */
public final class EngineHeaders {

	public static final String RETRY_AFTER = "Retry-After";

	public static final String REQUEST_ID = "X-Request-Id";

	public static final String OVERHEAD_MS = "X-LLMGW-Overhead-Ms";

	public static final String UPSTREAM_MS = "X-LLMGW-Upstream-Ms";

	/**
	 * Chapter-9 §9.2.4 attempt-budget transfer: client-supplied ABSOLUTE epoch-ms
	 * deadline for the whole logical call. The gateway caps every attempt's timeout to
	 * the remaining budget and stops the plan with 504 when exhausted.
	 */
	public static final String DEADLINE = "X-LLMGW-Deadline";

	/**
	 * Chapter-9 §9.4.5 task→session→turn attribution chain (Agent Gateway semantics).
	 * Caller-supplied identifiers from their authoritative execution context; the gateway
	 * binds them to the authenticated subject in the audit trail and never uses them for
	 * admission or quota.
	 */
	public static final String SESSION_ID = "X-LLMGW-Session-Id";

	public static final String TURN_ID = "X-LLMGW-Turn-Id";

	/**
	 * Chapter-9 §9.4.4 budget pressure signal: soft (approaching threshold, harness may
	 * stop adding steps) / hard (admission failed). Informational; the harness decides
	 * what to do with it.
	 */
	public static final String BUDGET_PRESSURE = "X-LLMGW-Budget-Pressure";

	/**
	 * Distinct event types: model intent vs. actual tool execution (Chapter-9 §9.4.6).
	 */
	public static final String TOOL_INTENT = "model.tool_calls";

	public static final String TOOL_EXECUTION = "tool.execution";

	public static final String CACHE_CONTROL = "Cache-Control";

	public static final String NO_CACHE = "no-cache";

	public static final String NO_STORE = "no-store";

	public static final String APPLICATION_JSON = "application/json";

	public static final String TEXT_EVENT_STREAM = "text/event-stream";

	private EngineHeaders() {
	}

}
