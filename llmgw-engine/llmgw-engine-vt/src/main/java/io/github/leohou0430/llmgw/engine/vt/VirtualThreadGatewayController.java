package io.github.leohou0430.llmgw.engine.vt;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GatewayPipeline;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.governance.GovernanceUnavailableException;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.Protocols;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.protocol.Protocols;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.routing.TaskRouteSticky;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.conversion.StreamTranscoder;
import io.github.leohou0430.llmgw.conversion.UpstreamException;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import io.github.leohou0430.llmgw.core.multipart.MultipartLimitException;
import io.github.leohou0430.llmgw.core.multipart.MultipartScanner;
import io.github.leohou0430.llmgw.core.multipart.MultipartUpload;
import io.github.leohou0430.llmgw.core.multipart.SpoolLimitException;
import io.github.leohou0430.llmgw.core.multipart.SpoolManager;
import io.github.leohou0430.llmgw.core.upstream.ChunkSink;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.billing.BillingInput;
import io.github.leohou0430.llmgw.billing.BillingLedger;
import io.github.leohou0430.llmgw.billing.BillingMeter;
import io.github.leohou0430.llmgw.billing.MissingPriceException;
import io.github.leohou0430.llmgw.billing.PriceBook;
import io.github.leohou0430.llmgw.billing.StrictBoundRequiredException;
import io.github.leohou0430.llmgw.billing.UsageEvidence;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.HeaderListener;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import io.github.leohou0430.llmgw.core.upstream.UpstreamRequest;
import io.github.leohou0430.llmgw.engine.BillableAttempt;
import io.github.leohou0430.llmgw.engine.BillableCall;
import io.github.leohou0430.llmgw.engine.EngineHeaders;
import io.github.leohou0430.llmgw.engine.EngineProfiles;
import io.github.leohou0430.llmgw.engine.EngineReasons;
import io.github.leohou0430.llmgw.engine.EngineRoutes;
import io.github.leohou0430.llmgw.engine.EngineTimings;
import io.github.leohou0430.llmgw.engine.IdempotencyGate;
import io.github.leohou0430.llmgw.engine.ForwardingPolicy;
import io.github.leohou0430.llmgw.engine.SseChunkAccounting;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

/**
 * Blocking data plane on virtual threads (one request = one virtual thread). Deliberately
 * uses the same upstream client as the WebFlux engine so the benchmark has exactly ONE
 * variable: the concurrency model.
 *
 * <p>
 * v12 multi-protocol: every POST endpoint (chat / responses / messages / images /
 * video-create) shares one handler parameterized by {@link ProtocolKind}; the kinds
 * differ only in request parsing, SSE terminator and response-side token/cache semantics,
 * all of which hang off ParsedRequest.kind().
 *
 * <p>
 * Streaming error contract: a stream counts as successful ONLY when the upstream actually
 * emitted its protocol terminator ([DONE] / response.completed / message_stop). A clean
 * EOF without that terminator is a silent truncation, not a success. Before the first
 * chunk the response is still uncommitted, so any failure yields a real HTTP 502; after
 * the first chunk the status is on the wire, so the failure is reported in-band as an SSE
 * {@code event: error} followed by {@code [DONE]}. Fallback is only ever attempted before
 * the first chunk. Once the terminator is out, a late upstream disconnect does not
 * invalidate the completed answer.
 *
 * <p>
 * Video tasks are asynchronous upstream jobs: create responses bind the task id to the
 * winning route ({@link TaskRouteSticky}), and poll/content GETs are pinned to that route
 * with NO fallback and NO circuit breaking — a task exists only on the upstream that
 * created it. A STICKY MISS (unknown task id) resolves the wildcard route and DOES honor
 * the circuit breaker (v13).
 *
 * <p>
 * v13 forwarding: attempt ordering (same-route retry with backoff, then the fallback
 * chain) is delegated to {@link ForwardingPolicy}; header pass-through in both directions
 * is delegated to {@link HeaderPolicy}. Comment lines (": ...", SSE heartbeat) are
 * transport-level and excluded from accounting, TTFT and the first-chunk guard. JDK
 * heartbeats use the requesting thread; StreamSink also serializes writes from custom
 * providers defensively.
 */
@RestController
@Profile(EngineProfiles.VT)
public class VirtualThreadGatewayController implements AutoCloseable {

	private final GovernanceChain chain;

	private final Router router;

	private final UpstreamProvider provider;

	private final CircuitBreaker breaker;

	private final GatewayProperties properties;

	/** W3.6: sticky store is an injectable SPI (in-memory default, Redis pack). */
	private final TaskRouteSticky taskRoutes;

	private io.github.leohou0430.llmgw.catalog.ResourceLifecycle resources;

	/** ADR-0007 billing pack; nullable so demo profiles without the pack still run. */
	private volatile BillingLedger billingLedger;

	private volatile PriceBook billingPrices;

	private volatile BillingMeter billingMeter;

	private volatile io.github.leohou0430.llmgw.recording.RecordingStore recordingStore;

	/** Chapter-9 §9.4.5 客户幂等键存储（缺席 = 无闸门，行为与历史版本一致）。 */
	private volatile io.github.leohou0430.llmgw.idempotency.IdempotencyStore idempotencyStore;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void resourceCatalog(io.github.leohou0430.llmgw.catalog.ResourceCatalog catalog,
			io.github.leohou0430.llmgw.core.governance.ApiKeyRegistry keys) {
		this.resources = new io.github.leohou0430.llmgw.catalog.ResourceLifecycle(catalog, keys);
	}

	/** ADR-0007 billing pack binding (absent in demo profiles → no-op billing). */
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void billingPack(BillingLedger ledger, PriceBook prices, BillingMeter meter) {
		this.billingLedger = ledger;
		this.billingPrices = prices;
		this.billingMeter = meter;
	}

	/** Task-5 recording store binding (absent → no recording bypass). */
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void recordingStore(io.github.leohou0430.llmgw.recording.RecordingStore store) {
		this.recordingStore = store;
	}

	/** Chapter-9 §9.4.5 客户幂等键绑定（absent → no idempotency gate）。 */
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void idempotencyStore(io.github.leohou0430.llmgw.idempotency.IdempotencyStore store) {
		this.idempotencyStore = store;
	}

	@org.springframework.beans.factory.annotation.Value("${llmgw.spool-directory:${java.io.tmpdir}/llmgw-spool/${server.port:8080}}")
	private String spoolDirectory;

	private final java.util.concurrent.Semaphore requestSlots;

	private final HeaderPolicy headerPolicy;

	private final ForwardingPolicy.RetryConfig retryConfig;

	/**
	 * T1.12 protocol-conversion seam. Nullable: when absent (or {@code applies(route)} is
	 * false) the data plane stays byte-for-byte passthrough. Injected by the engine
	 * Spring configuration so this module compiles and tests without the app's conversion
	 * bean.
	 */
	private final ConversionService conversion;

	/**
	 * T2.4 bounded upload spool, owned per controller instance (one per engine profile),
	 * mirroring {@link #requestSlots} ownership — never a static global. Lazily created
	 * on the first upload so download/conversion-only instances never touch disk;
	 * released by {@link #close()} (Spring's inferred destroy method) which also removes
	 * the private temp directory.
	 */
	private volatile SpoolManager spools;

	private volatile Path spoolDir;

	private static final int UPLOAD_CHUNK_BYTES = 16 * 1024;

	public VirtualThreadGatewayController(GovernanceChain chain, Router router, UpstreamProvider provider,
			CircuitBreaker breaker, GatewayProperties properties, HeaderPolicy headerPolicy,
			TaskRouteSticky taskRoutes) {
		this(chain, router, provider, breaker, properties, headerPolicy, taskRoutes, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public VirtualThreadGatewayController(GovernanceChain chain, Router router, UpstreamProvider provider,
			CircuitBreaker breaker, GatewayProperties properties, HeaderPolicy headerPolicy, TaskRouteSticky taskRoutes,
			@org.springframework.lang.Nullable ConversionService conversion) {
		this.chain = chain;
		this.router = router;
		this.provider = provider;
		this.breaker = breaker;
		this.properties = properties;
		this.requestSlots = new java.util.concurrent.Semaphore(properties.getMaxConcurrentRequests());
		this.taskRoutes = taskRoutes;
		this.headerPolicy = headerPolicy;
		this.conversion = conversion;
		GatewayProperties.Upstream.Retry retry = properties.getUpstream().getRetry();
		this.retryConfig = new ForwardingPolicy.RetryConfig(retry.getAttempts(), retry.getBackoffBaseMs(),
				retry.getBackoffMaxMs());
	}

	@PostMapping("/v1/chat/completions")
	public void handleChat(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.CHAT, request, response);
	}

	/**
	 * Task-7 Gemini native generation: the model lives in the URL path; only the
	 * :generateContent / :streamGenerateContent literal operations can ever route (Spring
	 * template requires the exact suffix), and the model must be a safe path segment
	 * (injection rejected with 400 before any upstream contact).
	 */
	@PostMapping("/v1beta/models/{model}:generateContent")
	public void handleGeminiGenerate(@PathVariable("model") String model, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		handleGemini(model, false, request, response);
	}

	@PostMapping("/v1beta/models/{model}:streamGenerateContent")
	public void handleGeminiStream(@PathVariable("model") String model, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		handleGemini(model, true, request, response);
	}

	private void handleGemini(String model, boolean streaming, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		if (model == null || !model.matches("[A-Za-z0-9._\\-]+") || model.contains("..")) {
			writeError(response, GatewayDecision.deny(400, "model path injection rejected"));
			return;
		}
		handleKind(ProtocolKind.GEMINI, streaming, model, request, response);
	}

	@PostMapping("/v1/responses")
	public void handleResponses(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.RESPONSES, request, response);
	}

	@PostMapping("/v1/messages")
	public void handleMessages(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.MESSAGES, request, response);
	}

	@PostMapping("/v1/images/generations")
	public void handleImages(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.IMAGES, request, response);
	}

	@PostMapping("/v1/videos")
	public void handleVideoCreate(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.VIDEOS, request, response);
	}

	// W4.3 protocol-surface expansion: JSON-in/JSON-out pass-through kinds.
	@PostMapping("/v1/embeddings")
	public void handleEmbeddings(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.EMBEDDINGS, request, response);
	}

	@PostMapping("/v1/rerank")
	public void handleRerank(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.RERANK, request, response);
	}

	/** JSON in, BINARY out: same byte-faithful path as video content. */
	@PostMapping("/v1/audio/speech")
	public void handleSpeech(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleBinaryKind(ProtocolKind.SPEECH, "audio/mpeg", request, response);
	}

	/**
	 * W4.3 binary-response kind (speech): full governance, then a byte-for-byte
	 * pass-through. The bytes never round-trip a String.
	 */
	private void handleBinaryKind(ProtocolKind kind, String contentType, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (!requestSlots.tryAcquire()) {
			writeError(response, GatewayDecision.deny(503, EngineReasons.CAPACITY_EXCEEDED));
			return;
		}
		try {
			handleBinaryKindWithinCapacity(kind, contentType, request, response);
		}
		finally {
			requestSlots.release();
		}
	}

	private void handleBinaryKindWithinCapacity(ProtocolKind kind, String contentType, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		long arrived = System.nanoTime();
		long max = Math.max(1, properties.getMaxRequestBytes());
		int cap = (int) Math.min(max, Integer.MAX_VALUE - 1) + 1;
		byte[] bytes = request.getInputStream().readNBytes(cap);
		if (bytes.length > max) {
			writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
			return;
		}
		String rawJson = new String(bytes, StandardCharsets.UTF_8);
		if (rawJson.isBlank()) {
			rawJson = "{}";
		}
		Map<String, String> clientHeaders = firstValues(request);
		String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
		response.setHeader(EngineHeaders.REQUEST_ID, requestId);
		GatewayDecision decision = GatewayPipeline.enter(chain, router, kind, request.getHeader("Authorization"),
				rawJson);
		if (!decision.allowed()) {
			writeError(response, decision);
			return;
		}
		GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
		Route route = decision.route();
		Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
		boolean success = false;
		try {
			if (!breaker.allow(route)) {
				throw new CircuitOpenException(route.id());
			}
			context.markUpstreamStart();
			byte[] body = provider.fetchRaw(EngineRoutes.postBytes(rawJson, decision.parsed(), route,
					properties.getUpstream().getTimeoutMs(), pass));
			breaker.recordSuccess(route);
			context.markUpstreamEnd();
			response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
			response.setStatus(200);
			response.setContentType(contentType);
			response.getOutputStream().write(body);
			response.flushBuffer();
			success = true;
		}
		catch (Exception e) {
			if (!response.isCommitted()) {
				writeError(response, errorDecision(e));
			}
		}
		finally {
			GatewayPipeline.finish(chain, context, success);
		}
	}

	@GetMapping("/v1/videos/{id}")
	public void handleVideoPoll(@PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id, EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.GET,
				request, response);
	}

	@GetMapping("/v1/videos/{id}/content")
	public void handleVideoContent(@PathVariable("id") String id, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id + "/content", "video/mp4", id, UpstreamMethod.GET,
				request, response);
	}

	// W4.3 completion of the market parity list: uploads, files, batches.
	@PostMapping("/v1/audio/transcriptions")
	public void handleTranscriptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleUpload(ProtocolKind.TRANSCRIPTIONS, request, response);
	}

	@PostMapping("/v1/files")
	public void handleFileUpload(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleUpload(ProtocolKind.FILES, request, response);
	}

	@GetMapping("/v1/files")
	public void handleFileList(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleStickyFetch(ProtocolKind.FILES, "/files", EngineHeaders.APPLICATION_JSON, null, UpstreamMethod.GET,
				request, response);
	}

	@GetMapping("/v1/files/{id}")
	public void handleFileGet(@PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		handleStickyFetch(ProtocolKind.FILES, "/files/" + id, EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.GET,
				request, response);
	}

	@org.springframework.web.bind.annotation.DeleteMapping("/v1/files/{id}")
	public void handleFileDelete(@PathVariable("id") String id, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		handleStickyFetch(ProtocolKind.FILES, "/files/" + id, EngineHeaders.APPLICATION_JSON, id, UpstreamMethod.DELETE,
				request, response);
	}

	@PostMapping("/v1/batches")
	public void handleBatchCreate(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleKind(ProtocolKind.BATCHES, request, response);
	}

	@GetMapping("/v1/batches")
	public void handleBatchList(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleStickyFetch(ProtocolKind.BATCHES, "/batches", EngineHeaders.APPLICATION_JSON, null, UpstreamMethod.GET,
				request, response);
	}

	@GetMapping("/v1/batches/{id}")
	public void handleBatchPoll(@PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id, EngineHeaders.APPLICATION_JSON, id,
				UpstreamMethod.GET, request, response);
	}

	// B11: market-parity pass-through endpoints (models catalog, batch cancel,
	// file content download).
	@GetMapping("/v1/models")
	public void handleModels(HttpServletRequest request, HttpServletResponse response) throws IOException {
		handleStickyFetch(ProtocolKind.CHAT, "/models", EngineHeaders.APPLICATION_JSON, null, UpstreamMethod.GET,
				request, response);
	}

	@PostMapping("/v1/batches/{id}/cancel")
	public void handleBatchCancel(@PathVariable("id") String id, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id + "/cancel", EngineHeaders.APPLICATION_JSON, id,
				UpstreamMethod.POST, request, response);
	}

	@GetMapping("/v1/files/{id}/content")
	public void handleFileContent(@PathVariable("id") String id, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		handleStickyFetch(ProtocolKind.FILES, "/files/" + id + "/content", "application/octet-stream", id,
				UpstreamMethod.GET, request, response);
	}

	private void handleKind(ProtocolKind kind, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		handleKind(kind, false, null, request, response);
	}

	/** Path-model variant (Gemini): the model and streaming flag come from the URL. */
	private void handleKind(ProtocolKind kind, boolean forceStream, String pathModel, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (!requestSlots.tryAcquire()) {
			writeError(response, GatewayDecision.deny(503, EngineReasons.CAPACITY_EXCEEDED));
			return;
		}
		try {
			handleKindWithinCapacity(kind, forceStream, pathModel, request, response);
		}
		finally {
			requestSlots.release();
		}
	}

	private void handleKindWithinCapacity(ProtocolKind kind, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		handleKindWithinCapacity(kind, false, null, request, response);
	}

	private void handleKindWithinCapacity(ProtocolKind kind, boolean forceStream, String pathModel,
			HttpServletRequest request, HttpServletResponse response) throws IOException {
		long arrived = System.nanoTime();
		long max = Math.max(1, properties.getMaxRequestBytes());
		int cap = (int) Math.min(max, Integer.MAX_VALUE - 1) + 1;
		byte[] bytes = request.getInputStream().readNBytes(cap);
		if (bytes.length > max) {
			writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
			return;
		}
		String rawJson = new String(bytes, StandardCharsets.UTF_8);
		if (rawJson.isBlank()) {
			rawJson = "{}";
		}
		Map<String, String> clientHeaders = firstValues(request);
		String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
		response.setHeader(EngineHeaders.REQUEST_ID, requestId);
		GatewayDecision decision;
		if (pathModel != null) {
			// Gemini: probe once for the token estimate, then admit with the
			// PATH model and the URL streaming flag (the body never carries them).
			ParsedRequest probe;
			try {
				probe = Protocols.of(kind).parse(rawJson);
			}
			catch (RuntimeException e) {
				writeError(response, GatewayDecision.deny(400, "invalid json: " + e.getMessage()));
				return;
			}
			ParsedRequest preParsed = new ParsedRequest(rawJson, kind, pathModel, forceStream, probe.promptText(),
					probe.estimatedTokens(), 1, null);
			decision = GatewayPipeline.enter(chain, router, preParsed, request.getHeader("Authorization"));
		}
		else {
			decision = GatewayPipeline.enter(chain, router, kind, request.getHeader("Authorization"), rawJson);
		}
		if (!decision.allowed()) {
			writeError(response, decision);
			return;
		}
		var requestResources = resources == null ? null : resources.scoped(decision);
		decision = requestResources == null ? decision : decision
			.withRoute(requestResources.referencedRoute(decision.parsed(), decision.keyId(), decision.route()));
		ParsedRequest parsed = decision.parsed();
		GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
		// Chapter-9 §9.4.5 关联头：session/turn 来自调用方权威执行上下文（大小写
		// 不敏感取第一个值），绑定到已认证主体写入审计，不参与准入与配额。
		context.setSessionId(firstValueIgnoreCase(clientHeaders, EngineHeaders.SESSION_ID));
		context.setTurnId(firstValueIgnoreCase(clientHeaders, EngineHeaders.TURN_ID));
		// Chapter-9 §9.4.5 / ADR-0007 客户幂等键：认证后、金额预留前判读——
		// 重复请求不占配额不预留，也不能借幂等键绕过授权。重复 → 409
		// idempotent_request_exists（流式不重放 SSE，只附原调用标识）。
		IdempotencyGate idem = IdempotencyGate.enter(idempotencyStore,
				decision.projectId().isBlank() ? decision.keyId() : decision.projectId(),
				firstValueIgnoreCase(clientHeaders, "Idempotency-Key"),
				IdempotencyGate.digest(kind.wireName(), decision.model(), kind.upstreamPath(), rawJson), requestId);
		if (idem != null && idem.isDuplicate()) {
			GatewayPipeline.finish(chain, context, false);
			writeError(response,
					GatewayDecision.deny(409, "idempotent_request_exists: state=" + idem.result().status().name()
							+ " call=" + idem.result().billingCallId() + " request=" + idem.result().requestId()));
			return;
		}
		if (!(requestResources == null
				? EngineRoutes.ownsReferencedFiles(parsed, decision.keyId(), decision.route(), taskRoutes)
				: requestResources.ownsFiles(parsed, decision.keyId(), decision.route()))) {
			GatewayPipeline.finish(chain, context, false);
			writeError(response, GatewayDecision.deny(404, "referenced file not found on this route"));
			return;
		}
		// ADR-0007 金额准入：token 通过后冻结售价、按协议计量单位估算并原子预留；
		// 缺计价规则或金额拒绝都不发上游（402）。
		// Chapter-9 §9.2.6（ADR-0009）：token 类协议携带显式输出上界时按上界预留；
		// 严格预算模式下无上界的 token 调用被 400 拒绝（不可证明的预留即软预算）。
		boolean strictBilling = properties.getBilling().isEnabled() && properties.getBilling().isStrictBudget();
		BillableCall billable;
		try {
			// Stage-1 scope mapping: blank tenant/project falls back to keyId.
			billable = BillableCall.open(billingLedger, billingPrices, billingMeter,
					billingInput(kind, parsed, rawJson),
					decision.tenantId().isBlank() ? decision.keyId() : decision.tenantId(),
					decision.projectId().isBlank() ? decision.keyId() : decision.projectId(), decision.model(),
					strictBilling);
		}
		catch (MissingPriceException missing) {
			GatewayPipeline.finish(chain, context, false);
			writeError(response, GatewayDecision.deny(402, missing.getMessage()));
			return;
		}
		catch (StrictBoundRequiredException strict) {
			GatewayPipeline.finish(chain, context, false);
			writeError(response,
					GatewayDecision.deny(400, "strict_budget_requires_output_bound: " + strict.getMessage()));
			return;
		}
		if (billable == null) {
			GatewayPipeline.finish(chain, context, false);
			writeError(response, GatewayDecision.deny(402, "billing-insufficient-funds"));
			return;
		}
		context.setBillingCallId(billable.billingCallId());
		// Task-5 事件旁路：请求正文消毒记录；终态由响应路径推进，finally 兜底 INCOMPLETE。
		io.github.leohou0430.llmgw.recording.RecordingHandle record = recordingStore == null ? null
				: recordingStore.open(new io.github.leohou0430.llmgw.recording.RecordingStart(requestId,
						decision.tenantId().isBlank() ? decision.keyId() : decision.tenantId(),
						decision.projectId().isBlank() ? decision.keyId() : decision.projectId(), kind.wireName()));
		if (record != null) {
			record.append(io.github.leohou0430.llmgw.recording.RecordedEvent
				.now(io.github.leohou0430.llmgw.recording.RecordedEvent.Direction.REQUEST, rawJson));
		}
		Route route = decision.route();
		int timeout = properties.getUpstream().getTimeoutMs();
		ForwardingPolicy.RetryConfig retry = retryConfig;
		Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
		HeaderListener egress = egressListener(response);
		// Chapter-9 §9.2.4 跨 attempt 截止时间：配置的 total-deadline-ms 与客户端
		// X-LLMGW-Deadline（绝对 epoch-ms）取较早者。解析失败或已过期的客户端头
		// 被忽略记 0——不可信的头部永远不能成为立即击杀开关。每个 attempt 的
		// 超时被截断到剩余预算；预算耗尽以可解释的 504 停止计划。
		ForwardingPolicy.AttemptBudget budget = ForwardingPolicy.AttemptBudget.of(
				properties.getUpstream().getTotalDeadlineMs(),
				parseDeadlineEpochMs(firstValueIgnoreCase(clientHeaders, EngineHeaders.DEADLINE)),
				System.currentTimeMillis(), arrived);
		// Chapter-9 §9.2.4：每个真实上游尝试一个独立计费 attempt；holder 记录
		// 最后一个句柄，供外层 catch 兜底终态（递归循环以数组参数传递）。
		BillableAttempt[] lastAttempt = new BillableAttempt[1];
		// T1.12 protocol conversion: prepare BEFORE any upstream request is built, so a
		// capability
		// violation fails pre-header with the entry error shape instead of touching the
		// upstream.
		PreparedConversion prepared = null;
		if (conversion != null && conversion.applies(route)) {
			try {
				// Stage-1 degenerate mapping: tenantId = projectId = decision.keyId().
				// The chain guarantees non-blank tenant/project in production and
				// monetary-billing modes; keyId is only the demo-profile fallback.
				DegradationGrant grant = DegradationGrant.parse(degradationGrantValue(clientHeaders));
				prepared = conversion.prepare(parsed, route,
						decision.tenantId().isBlank() ? decision.keyId() : decision.tenantId(),
						decision.projectId().isBlank() ? decision.keyId() : decision.projectId(), grant);
			}
			catch (CapabilityException | UpstreamException error) {
				GatewayPipeline.finish(chain, context, false);
				writeConversionError(response, kind, error);
				return;
			}
		}
		final PreparedConversion conversionHandle = prepared;
		// Matrix §11 security invariant: the degradation grant is gateway-only; strip it
		// from the
		// upstream pass headers unconditionally, regardless of the configured whitelist.
		final Map<String, String> upstreamPass = withoutDegradation(pass);
		boolean success = false;
		try {
			if (parsed.stream()) {
				success = handleStream(kind, response, rawJson, parsed, route, timeout, context, upstreamPass,
						conversionHandle, billable, lastAttempt, budget, idem);
			}
			else {
				boolean cacheBypass = "1".equals(clientHeaders.get("x-llmgw-no-cache"));
				Optional<String> cached = cacheBypass ? Optional.empty() : chain.lookupCache(decision.keyId(), parsed);
				String body;
				Route winning = route;
				if (cached.isPresent()) {
					context.markCached();
					body = cached.get();
					// 缓存命中没有上游成本：撤回金额预留（终态后重复撤回是 no-op）。
					billable.releaseUnsubmitted("cache hit");
					if (idem != null)
						idem.complete(true, null);
				}
				else {
					Completed completed = completeWithPlan(EngineRoutes.forwardPlan(router, route, retry, parsed), 0,
							rawJson, parsed, timeout, context, upstreamPass, egress, 0, conversionHandle, billable,
							lastAttempt, budget);
					body = completed.body();
					winning = completed.route();
					context.markUpstreamEnd();
					// T1.12: transcode the upstream body into the ENTRY body BEFORE
					// accounting,
					// cache, taskId binding and the write. A cache HIT is already
					// entry-shaped.
					if (conversionHandle != null) {
						body = conversionHandle.transcodeResponse(body);
					}
				}
				// Single parse: token count AND cacheability come from the
				// same tree (the body previously went through readTree twice).
				JsonNode root = ProtocolCodec.tryParse(body);
				JsonNode authoritativeUsage = root.path("usage");
				if (authoritativeUsage.isMissingNode())
					authoritativeUsage = root.path("response").path("usage");
				// Gemini names its authoritative frame usageMetadata
				// (candidatesTokenCount);
				// OpenAI-shaped kinds report "usage". Both are evidence of known usage.
				boolean usageKnown = (!authoritativeUsage.isMissingNode() && authoritativeUsage.isObject()
						&& authoritativeUsage.size() > 0)
						|| (kind == ProtocolKind.GEMINI && !root.path("usageMetadata").isMissingNode()
								&& root.path("usageMetadata").isObject() && root.path("usageMetadata").size() > 0);
				if (authoritativeUsage.has("prompt_tokens") || authoritativeUsage.has("input_tokens")) {
					context.setPromptTokens(
							authoritativeUsage.has("prompt_tokens") ? authoritativeUsage.path("prompt_tokens").asInt()
									: authoritativeUsage.path("input_tokens").asInt());
				}
				context.setCompletionTokensIfAbsent(ProtocolCodec.completionTokensFromResponse(kind, root));
				if (cached.isEmpty() && !cacheBypass) {
					chain.cache(decision.keyId(), parsed, body, ProtocolCodec.isCacheableResponse(kind, root));
				}
				// A video job lives on the upstream that created it: pin the
				// task id (async-job protocols only) to the route that
				// actually served the create call.
				String taskId = cached.isEmpty() ? Protocols.of(kind).taskId(root) : "";
				if (!taskId.isEmpty()) {
					context.setTaskId(taskId);
					taskRoutes.bind(decision.keyId(), kind.wireName(), taskId, winning.id());
					if (requestResources != null)
						requestResources.created(decision.keyId(), kind, winning, body);
				}
				// ADR-0007 结算：上游真实调用按权威 usage 帧经计量器入账（终态 CAS
				// 仅一次；证据缺席不记零，挂账待核验）。§9.2.4：结算使用循环内
				// 最终成功 attempt 的句柄；holder 为空（全部未提交）则撤回预留。
				if (cached.isEmpty()) {
					BillableAttempt finalAttempt = lastAttempt[0];
					if (finalAttempt != null) {
						billWithExtract(billable, finalAttempt, kind, parsed,
								usageKnown ? UsageEvidence.known(Math.max(0, context.promptTokens()),
										Math.max(0, context.completionTokens())) : UsageEvidence.unknown());
						// 幂等终态：已知用量=known；证据缺席（挂账）=unknown 不自动重发。
						if (idem != null)
							idem.complete(usageKnown, billable.billingCallId());
					}
					else {
						billable.releaseUnsubmitted("upstream finished without a billable attempt");
						if (idem != null)
							idem.complete(true, null);
					}
				}
				String upstream = EngineTimings.upstreamOrNull(context);
				if (upstream != null) {
					response.setHeader(EngineHeaders.UPSTREAM_MS, upstream);
				}
				response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
				if (!context.sessionId().isEmpty()) {
					// Chapter-9 §9.4.5：把权威 session 回写给调用方，保证两端
					// 关联标识一致（缺失不编造）。
					response.setHeader(EngineHeaders.SESSION_ID, context.sessionId());
				}
				response.setStatus(200);
				response.setContentType(EngineHeaders.APPLICATION_JSON);
				if (record != null) {
					record.append(io.github.leohou0430.llmgw.recording.RecordedEvent
						.now(io.github.leohou0430.llmgw.recording.RecordedEvent.Direction.RESPONSE, body));
					record.finish(io.github.leohou0430.llmgw.recording.RecordingOutcome.COMPLETE);
				}
				response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
				response.flushBuffer();
				success = true;
			}
		}
		catch (Exception e) {
			BillableAttempt finalAttempt = lastAttempt[0];
			if (e instanceof CircuitOpenException) {
				billable.releaseUnsubmitted("circuit open before upstream commit");
				if (idem != null)
					idem.complete(true, null);
			}
			else if (e instanceof ForwardingPolicy.DeadlineExceededException) {
				// §9.2.4：预算在真正发起上游尝试前耗尽——证实未提交，撤回预留；
				// 已提交的流式失败不会走到这里（chunkSent 直接上抛，不进下一 attempt）。
				billable.releaseUnsubmitted(e.getMessage());
				if (idem != null)
					idem.complete(true, null);
			}
			else if (billable.enabled() && finalAttempt != null) {
				// 提交状态未知：保守挂账待核验（宁挂账不漏收；终态后幂等 no-op）。
				// §9.2.4：兜底用最后一个 attempt 的句柄，前序未提交 attempt 不推进终态。
				finalAttempt.markUnknown("upstream failure, submit state unknown: " + e.getMessage());
				if (idem != null)
					idem.complete(false, billable.billingCallId());
			}
			if (!response.isCommitted()) {
				if (e instanceof CapabilityException || e instanceof UpstreamException) {
					writeConversionError(response, kind, (RuntimeException) e);
				}
				else {
					writeError(response, errorDecision(e));
				}
			}
		}
		finally {
			// 兜底撤回：任何终态已推进的路径这里是幂等 no-op。
			billable.releaseUnsubmitted("request ended without billing terminal");
			if (idem != null)
				idem.complete(true, null);
			if (record != null) {
				record.close(); // 终态已推进时幂等 no-op；否则记 INCOMPLETE
			}
			if (conversionHandle != null) {
				conversionHandle.close();
			}
			GatewayPipeline.finish(chain, context, success);
		}
	}

	/**
	 * W4.3 byte-body upload kinds (transcriptions, files): full governance via the
	 * byte-probe, then a byte-faithful POST. The multipart Content-Type (with its
	 * boundary) rides the policy pass-through untouched.
	 */
	private void handleUpload(ProtocolKind kind, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		// T2.7: a declared Content-Length over the cap is rejected before the body
		// is read or a permit is taken; unknown length (-1) falls through to the
		// scanner's cumulative bound.
		long declared = request.getContentLengthLong();
		if (declared > properties.getMaxRequestBytes()) {
			writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
			return;
		}
		if (!requestSlots.tryAcquire()) {
			writeError(response, GatewayDecision.deny(503, EngineReasons.CAPACITY_EXCEEDED));
			return;
		}
		try {
			handleUploadWithinCapacity(kind, request, response);
		}
		finally {
			requestSlots.release();
		}
	}

	private void handleUploadWithinCapacity(ProtocolKind kind, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		long arrived = System.nanoTime();
		Map<String, String> clientHeaders = firstValues(request);
		String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
		response.setHeader(EngineHeaders.REQUEST_ID, requestId);

		String boundary = multipartBoundary(request.getContentType());
		if (boundary == null) {
			writeError(response, GatewayDecision.deny(400, "missing multipart boundary"));
			return;
		}
		// T2.4: stream the body through the scanner+spool instead of buffering it
		// whole. The configured request cap becomes the whole-multipart bound
		// (413), with the plan's per-file / metadata defaults; limits are enforced
		// chunk-by-chunk so memory stays flat regardless of upload size.
		MultipartScanner.Limits defaults = MultipartScanner.Limits.defaults();
		MultipartScanner.Limits limits = new MultipartScanner.Limits(defaults.maxFileBytes(),
				Math.max(1, properties.getMaxRequestBytes()), defaults.maxMetadataBytes());

		MultipartUpload.Prepared prepared;
		try {
			prepared = MultipartUpload.prepare(
					ByteStream.inputStreamSource(request.getInputStream(), UPLOAD_CHUNK_BYTES), boundary, limits,
					spools());
		}
		catch (MultipartLimitException | SpoolLimitException e) {
			writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
			return;
		}
		catch (IOException e) {
			writeError(response, GatewayDecision.deny(400, "invalid multipart body: " + e.getMessage()));
			return;
		}

		boolean success = false;
		GatewayContext context = null;
		try {
			// Mirror DefaultProtocols.Multipart routing: FILES keys on "purpose",
			// TRANSCRIPTIONS on "model"; the prompt field rides as probe text.
			String routingField = kind == ProtocolKind.FILES ? "purpose" : "model";
			ParsedRequest parsed = new ParsedRequest("", kind, prepared.fields().getOrDefault(routingField, ""), false,
					prepared.fields().getOrDefault("prompt", ""), (int) Math.max(1L, prepared.totalBytes() / 4), 1,
					null);
			GatewayDecision decision = chain.enter(request.getHeader("Authorization"), parsed,
					router.resolve(kind, parsed.model()));
			if (!decision.allowed()) {
				writeError(response, decision);
				return;
			}
			var requestResources = resources == null ? null : resources.scoped(decision);
			context = GatewayPipeline.context(decision, requestId, arrived);
			Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
			try {
				if (!breaker.allow(decision.route())) {
					throw new CircuitOpenException(decision.route().id());
				}
				context.markUpstreamStart();
				byte[] upstreamBody = provider
					.fetchRaw(EngineRoutes.postRawBytes(prepared.body(), prepared.bodyLength(), parsed,
							decision.route(), properties.getUpstream().getTimeoutMs(), pass));
				breaker.recordSuccess(decision.route());
				context.markUpstreamEnd();
				if (kind == ProtocolKind.FILES) {
					String resourceId = ProtocolCodec.tryParse(new String(upstreamBody, StandardCharsets.UTF_8))
						.path("id")
						.asText("");
					if (!resourceId.isBlank()) {
						taskRoutes.bind(decision.keyId(), kind.wireName(), resourceId, decision.route().id());
						if (requestResources != null)
							requestResources.created(decision.keyId(), kind, decision.route(),
									new String(upstreamBody, StandardCharsets.UTF_8));
					}
				}
				response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
				response.setStatus(200);
				response.setContentType(EngineHeaders.APPLICATION_JSON);
				response.getOutputStream().write(upstreamBody);
				response.flushBuffer();
				success = true;
			}
			catch (Exception e) {
				if (!response.isCommitted()) {
					writeError(response, errorDecision(e));
				}
			}
		}
		finally {
			// Every terminal path (success, governance reject, upstream error,
			// cancel) releases the spool exactly once; Prepared.close is idempotent.
			prepared.close();
			if (context != null) {
				GatewayPipeline.finish(chain, context, success);
			}
		}
	}

	@jakarta.annotation.PostConstruct
	public void recoverUploads() throws IOException {
		if (spoolDirectory != null)
			spools();
	}

	/** Lazily create the per-engine spool manager owning one restricted temp dir. */
	private synchronized SpoolManager spools() throws IOException {
		if (spools == null) {
			spoolDir = spoolDirectory == null ? Files.createTempDirectory("llmgw-vt-spool-")
					: Path.of(spoolDirectory, "vt");
			spools = new SpoolManager(SpoolManager.Config.defaults(spoolDir));
		}
		return spools;
	}

	/** Extract the multipart boundary parameter from a Content-Type header. */
	private static String multipartBoundary(String contentType) {
		if (contentType == null) {
			return null;
		}
		for (String part : contentType.split(";")) {
			String t = part.trim();
			if (t.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
				String b = t.substring("boundary=".length()).trim();
				if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
					b = b.substring(1, b.length() - 1);
				}
				return b.isEmpty() ? null : b;
			}
		}
		return null;
	}

	@Override
	public synchronized void close() {
		if (spools != null) {
			spools.close();
			spools = null;
		}
		if (spoolDir != null) {
			try {
				Files.deleteIfExists(spoolDir);
			}
			catch (IOException ignored) {
				// best-effort temp-dir removal on shutdown
			}
			spoolDir = null;
		}
	}

	/**
	 * Task poll / fetch GET/DELETE (video, batch, files): governance + sticky routing,
	 * then a raw byte-for-byte pass-through. A PINNED task has no fallback and no circuit
	 * breaking -- the task exists only on the upstream that created it, and a
	 * create-route circuit must never strand a live job's polling. A STICKY MISS resolves
	 * the wildcard route, which is an ordinary route call and DOES honor the circuit
	 * breaker (v13).
	 */
	private void handleStickyFetch(ProtocolKind kind, String upstreamPath, String contentType, String taskId,
			UpstreamMethod method, HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!requestSlots.tryAcquire()) {
			writeError(response, GatewayDecision.deny(503, EngineReasons.CAPACITY_EXCEEDED));
			return;
		}
		try {
			handleStickyFetchWithinCapacity(kind, upstreamPath, contentType, taskId, method, request, response);
		}
		finally {
			requestSlots.release();
		}
	}

	private void handleStickyFetchWithinCapacity(ProtocolKind kind, String upstreamPath, String contentType,
			String taskId, UpstreamMethod method, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		long arrived = System.nanoTime();
		String requestId = GatewayPipeline.requestId(request.getHeader(EngineHeaders.REQUEST_ID));
		response.setHeader(EngineHeaders.REQUEST_ID, requestId);
		ParsedRequest parsed = ParsedRequest.bodyless(kind);
		GatewayDecision decision = chain.enter(request.getHeader("Authorization"), parsed, router.resolve(kind, "*"));
		if (!decision.allowed()) {
			writeError(response, decision);
			return;
		}
		var requestResources = resources == null ? null : resources.scoped(decision);
		if (requestResources != null && taskId == null
				&& (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES)) {
			boolean listed = false;
			try {
				String body = requestResources.list(decision.keyId(), kind, request.getParameter("limit"),
						request.getParameter("after"));
				response.setStatus(200);
				response.setContentType(EngineHeaders.APPLICATION_JSON);
				response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
				listed = true;
			}
			catch (IllegalArgumentException failure) {
				writeError(response, GatewayDecision.deny(400, failure.getMessage()));
			}
			finally {
				GatewayPipeline.finish(chain, GatewayPipeline.context(decision, requestId, arrived), listed);
			}
			return;
		}
		Route pinned = taskId == null ? null
				: requestResources != null && (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES)
						? requestResources.route(decision.keyId(), kind, taskId)
						: router.byId(taskRoutes.resolve(decision.keyId(), kind.wireName(), taskId));
		if ((taskId != null && pinned == null)
				|| (taskId == null && (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES))) {
			GatewayPipeline.finish(chain, GatewayPipeline.context(decision, requestId, arrived), false);
			writeError(response, GatewayDecision.deny(taskId == null ? 501 : 404,
					taskId == null ? "tenant-scoped resource listing is unavailable" : "resource not found"));
			return;
		}
		boolean isPinned = pinned != null;
		Route route = isPinned ? pinned : decision.route();
		decision = decision.withRoute(route);
		GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
		boolean stream = !EngineHeaders.APPLICATION_JSON.equals(contentType);
		boolean success = false;
		boolean fetched = false;
		try {
			if (!isPinned && !breaker.allow(route)) {
				throw new CircuitOpenException(route.id());
			}
			context.markUpstreamStart();
			if (stream) {
				// T2.4 binary content download: bounded blocking drain of fetchStream
				// straight to the servlet output (one chunk in flight, never a whole
				// byte[]). Headers precede the first byte because it commits the
				// response; only pre-commit overhead is reported here (upstream timing
				// is a meter), matching the streaming convention.
				response.setStatus(200);
				response.setContentType(contentType);
				response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.preflight(context));
				ServletDrain drain = new ServletDrain(response.getOutputStream());
				provider
					.fetchStream(EngineRoutes.methodFetch(route, upstreamPath, method,
							properties.getUpstream().getTimeoutMs()))
					.subscribe(drain);
				drain.await();
				if (drain.error != null) {
					throw asException(drain.error);
				}
				if (!isPinned) {
					breaker.recordSuccess(route);
				}
				fetched = true;
				context.markUpstreamEnd();
				response.flushBuffer();
				success = true;
			}
			else {
				byte[] body = provider.fetchRaw(
						EngineRoutes.methodFetch(route, upstreamPath, method, properties.getUpstream().getTimeoutMs()));
				if (!isPinned) {
					breaker.recordSuccess(route);
				}
				if (requestResources != null && taskId != null)
					requestResources.observed(decision.keyId(), kind, taskId, route,
							new String(body, StandardCharsets.UTF_8), method == UpstreamMethod.DELETE);
				fetched = true;
				context.markUpstreamEnd();
				String upstream = EngineTimings.upstreamOrNull(context);
				if (upstream != null) {
					response.setHeader(EngineHeaders.UPSTREAM_MS, upstream);
				}
				response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
				response.setStatus(200);
				response.setContentType(contentType);
				response.getOutputStream().write(body);
				response.flushBuffer();
				success = true;
			}
		}
		catch (Exception e) {
			if (!isPinned && !fetched && !(e instanceof CircuitOpenException)) {
				breaker.recordFailure(route);
			}
			if (!response.isCommitted()) {
				writeError(response, errorDecision(e));
			}
		}
		finally {
			GatewayPipeline.finish(chain, context, success);
		}
	}

	private static Exception asException(Throwable t) {
		return t instanceof Exception e ? e : new IOException(t);
	}

	/**
	 * T2.4 bounded blocking drain of a download publisher to the servlet output. One
	 * chunk is in flight at a time (request(1) after each write) so a slow client
	 * backpressures the upstream instead of buffering the whole body. A write failure
	 * cancels the subscription (releasing the upstream source) and is surfaced as the
	 * terminal error. ByteStream emits on the requesting thread, so {@link #await()}
	 * returns immediately for the JDK transport.
	 */
	private static final class ServletDrain implements Flow.Subscriber<ByteBuffer> {

		private final OutputStream out;

		private final CountDownLatch done = new CountDownLatch(1);

		private volatile Flow.Subscription sub;

		private volatile Throwable error;

		ServletDrain(OutputStream out) {
			this.out = out;
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			this.sub = s;
			s.request(1);
		}

		@Override
		public void onNext(ByteBuffer b) {
			try {
				byte[] a = new byte[b.remaining()];
				b.get(a);
				out.write(a);
			}
			catch (IOException e) {
				error = e;
				sub.cancel();
				done.countDown();
				return;
			}
			sub.request(1);
		}

		@Override
		public void onError(Throwable t) {
			error = t;
			done.countDown();
		}

		@Override
		public void onComplete() {
			done.countDown();
		}

		void await() throws IOException {
			try {
				done.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("download interrupted", e);
			}
		}

	}

	/**
	 * Do NOT call flushBuffer() before the first chunk: that commits the response and
	 * turns a failed upstream into a misleading "200 OK, empty body". Headers alone keep
	 * the response mutable until the first byte.
	 */
	private boolean handleStream(ProtocolKind kind, HttpServletResponse response, String rawJson, ParsedRequest parsed,
			Route route, int timeout, GatewayContext context, Map<String, String> pass, PreparedConversion prepared,
			BillableCall billable, BillableAttempt[] lastAttempt, ForwardingPolicy.AttemptBudget budget,
			IdempotencyGate idem) {
		response.setStatus(200);
		response.setContentType(EngineHeaders.TEXT_EVENT_STREAM);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader(EngineHeaders.CACHE_CONTROL, EngineHeaders.NO_CACHE);
		// Headers are still mutable here (no flushBuffer before the first
		// chunk). Both engines report only pre-commit work; full TTFT and
		// upstream timings are recorded as llmgw.ttft / llmgw.upstream meters.
		response.setHeader(EngineHeaders.OVERHEAD_MS, EngineTimings.preflight(context));
		if (!context.sessionId().isEmpty()) {
			// Chapter-9 §9.4.5：提交前回写权威 session，保证两端关联标识一致。
			response.setHeader(EngineHeaders.SESSION_ID, context.sessionId());
		}

		// T1.12: one fresh transcoder per request; the sink transcodes upstream chat
		// lines into
		// ENTRY lines before accounting and owns the entry terminal (no double error /
		// fake success).
		StreamTranscoder transcoder = prepared == null ? null : prepared.newStreamTranscoder();
		StreamSink sink = new StreamSink(response, context, headerPolicy, transcoder);
		try {
			streamWithPlan(EngineRoutes.forwardPlan(router, route, retryConfig, parsed), 0, rawJson, parsed, timeout,
					sink, context, pass, 0, prepared, billable, lastAttempt, budget);
			// A clean EOF is NOT proof of completion. Only an explicit upstream
			// terminator frame is. Do not fabricate one here.
			if (!sink.isComplete()) {
				boolean known = billOnStreamFailure(billable, lastAttempt[0], sink, kind, parsed, context,
						"stream truncated before protocol terminator");
				if (idem != null)
					idem.complete(known, billable.billingCallId());
				return handleFailure(response, sink, EngineReasons.truncated(kind), kind, prepared);
			}
			BillableAttempt finalAttempt = lastAttempt[0];
			if (finalAttempt != null) {
				// markSubmitted/selectFinal 已在循环内成功提交上游后完成，此处结算。
				billStreamUsage(billable, finalAttempt, kind, parsed, context);
				if (idem != null)
					idem.complete(true, billable.billingCallId());
			}
			else {
				billable.releaseUnsubmitted("stream completed without a billable attempt");
				if (idem != null)
					idem.complete(true, null);
			}
			return true;
		}
		catch (Exception e) {
			if (sink.isComplete()) {
				// Protocol terminator already delivered; a late disconnect is
				// not a failure and must not append a spurious error event.
				BillableAttempt finalAttempt = lastAttempt[0];
				if (finalAttempt != null) {
					finalAttempt.markSubmitted(null);
					billStreamUsage(billable, finalAttempt, kind, parsed, context);
					if (idem != null)
						idem.complete(true, billable.billingCallId());
				}
				else {
					billable.releaseUnsubmitted("stream completed without a billable attempt");
					if (idem != null)
						idem.complete(true, null);
				}
				return true;
			}
			boolean known = billOnStreamFailure(billable, lastAttempt[0], sink, kind, parsed, context,
					"stream failed: " + e.getMessage());
			if (idem != null)
				idem.complete(known, billable.billingCallId());
			int status = e instanceof ForwardingPolicy.DeadlineExceededException ? 504 : 502;
			return handleFailure(response, sink, EngineReasons.upstream(e), status, kind, prepared);
		}
		finally {
			// Close AFTER terminal handling so onUpstreamError ran while the transcoder
			// was open.
			if (transcoder != null) {
				transcoder.close();
			}
		}
	}

	/** 统一结算：计量器提取 → 金额结算；证据未知挂账（complete 内部处理）。 */
	private void billWithExtract(BillableCall billable, BillableAttempt attempt, ProtocolKind kind,
			ParsedRequest parsed, UsageEvidence evidence) {
		if (!billable.enabled() || billable.finished()) {
			return;
		}
		if (billingMeter == null) {
			attempt.complete(evidence, BillableCall.OUTCOME_SETTLED);
			return;
		}
		io.github.leohou0430.llmgw.billing.MeteredQuantity actual = billingMeter.extract(billable.frozenPrice(),
				evidence, Math.max(1, parsed.units()), billingMeter.unitFor(kind.wireName()));
		if (actual.quality() == io.github.leohou0430.llmgw.billing.MeteredQuantity.Quality.UNKNOWN) {
			attempt.complete(UsageEvidence.unknown(), BillableCall.OUTCOME_SETTLED);
		}
		else {
			attempt.completeNano(actual.nano(), BillableCall.OUTCOME_SETTLED);
		}
	}

	/**
	 * 流式终态推进：上游已发过 usage 帧 → 按已知量结算（取消也收已知部分）； 已提交但用量未知 → 挂账待核验；从未提交 → 撤回预留。终态幂等。
	 * @return true = 结果已知（结算或撤回）；false = 结果未知（挂账，幂等 终态以 unknown 收口，不自动重发）
	 */
	private boolean billOnStreamFailure(BillableCall billable, BillableAttempt attempt, StreamSink sink,
			ProtocolKind kind, ParsedRequest parsed, GatewayContext context, String reason) {
		if (!billable.enabled()) {
			return true;
		}
		if (sink.chunkSent || sink.disconnected) {
			if (attempt == null) {
				// 防御：有数据送达却没有可用的 attempt 句柄，保守撤回（终态幂等）。
				billable.releaseUnsubmitted(reason);
				return true;
			}
			if (context.reportedPromptTokensRaw() >= 0 && context.reportedCompletionTokensRaw() >= 0) {
				billWithExtract(billable, attempt, kind, parsed,
						UsageEvidence.known(context.reportedPromptTokensRaw(), context.reportedCompletionTokensRaw()));
				return true;
			}
			attempt.markUnknown(reason);
			return false;
		}
		billable.releaseUnsubmitted(reason);
		return true;
	}

	/** 正常终止的流式结算：SSE accounting 已记录权威用量。 */
	private void billStreamUsage(BillableCall billable, BillableAttempt attempt, ProtocolKind kind,
			ParsedRequest parsed, GatewayContext context) {
		billWithExtract(billable, attempt, kind, parsed,
				UsageEvidence.known(Math.max(0, context.promptTokens()), Math.max(0, context.completionTokens())));
	}

	/** Shared terminal handling for thrown errors and silent truncation (default 502). */
	private boolean handleFailure(HttpServletResponse response, StreamSink sink, String reason, ProtocolKind kind,
			PreparedConversion prepared) {
		return handleFailure(response, sink, reason, 502, kind, prepared);
	}

	/**
	 * Shared terminal handling with an explicit pre-header status: the attempt budget
	 * maps to 504 (§9.2.4); everything else stays 502.
	 */
	private boolean handleFailure(HttpServletResponse response, StreamSink sink, String reason, int status,
			ProtocolKind kind, PreparedConversion prepared) {
		if (sink.isComplete()) {
			return true;
		}
		if (sink.terminalHandled()) {
			// T1.12: the transcoder already rendered the entry terminal (success or
			// error). Never
			// append a second error or a fabricated success; the caller bills the failure
			// once.
			return false;
		}
		if (!sink.chunkSent && !response.isCommitted()) {
			try {
				if (prepared != null) {
					writeConversionError(response, kind, new UpstreamException(reason));
				}
				else {
					writeError(response, GatewayDecision.deny(status, reason));
				}
			}
			catch (IOException ignored) {
				// Client disconnected; nothing left to report to.
			}
		}
		else {
			sink.writeErrorEvent(reason);
		}
		return false;
	}

	/**
	 * The attempt loop, driven by the shared {@link ForwardingPolicy} plan: same-route
	 * retries honor their backoff delay (a virtual-thread sleep is free), non-retryable
	 * failures skip the current route's remaining attempts and advance the fallback
	 * chain, exactly mirroring the reactive engine.
	 *
	 * <p>
	 * Chapter-9 §9.2.4: every attempt that really reaches an upstream opens its own
	 * billing attempt handle (recorded into {@code lastAttempt}), the per-attempt timeout
	 * is capped to the remaining {@code budget}, and the budget is checked BEFORE any
	 * upstream contact. A successful attempt is marked submitted and selected as the
	 * call's final attempt; a failed one that provably never delivered anything advances
	 * the plan without moving billing terminal state.
	 */
	private void streamWithPlan(List<ForwardingPolicy.Attempt> plan, int from, String rawJson, ParsedRequest parsed,
			int timeout, StreamSink sink, GatewayContext context, Map<String, String> pass, long extraDelayMs,
			PreparedConversion prepared, BillableCall billable, BillableAttempt[] lastAttempt,
			ForwardingPolicy.AttemptBudget budget) {
		if (from >= plan.size()) {
			throw new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE);
		}
		ForwardingPolicy.Attempt attempt = plan.get(from);
		sleepBefore(attempt, extraDelayMs);
		// §9.2.4: no attempt may start on an exhausted budget — an explainable
		// 504 instead of a per-attempt timeout multiplied by the plan length.
		budget.checkBeforeAttempt(from + 1);
		if (!breaker.allow(attempt.route())) {
			// Fail fast locally, then move on to the next viable attempt.
			CircuitOpenException open = new CircuitOpenException(attempt.route().id());
			int next = ForwardingPolicy.nextViable(plan, from, open);
			if (next < 0) {
				throw open;
			}
			streamWithPlan(plan, next, rawJson, parsed, timeout, sink, context, pass,
					ForwardingPolicy.effectiveDelayMs(plan, from, next, open), prepared, billable, lastAttempt, budget);
			return;
		}
		BillableAttempt attemptHandle = beginBillableAttempt(billable, attempt.route().id(), lastAttempt);
		context.incrementAttemptCount();
		try {
			context.markUpstreamStart();
			UpstreamRequest request = prepared == null
					? EngineRoutes.request(withStreamUsageInjection(rawJson, parsed, attempt.route()), parsed,
							attempt.route(), budget.boundedTimeoutMs(timeout), pass)
					: EngineRoutes.convertedRequest(prepared.upstreamJson(), prepared.upstreamPath(), parsed,
							attempt.route(), budget.boundedTimeoutMs(timeout), pass);
			provider.streamRawBlocking(request, sink);
			breaker.recordSuccess(attempt.route());
			// §9.2.2 对账契约：记录实际服务的上游模型（targetModel 重写后）——
			// 与计费句柄无关（billing 关闭时同样记录）。
			context.setServedModel(EngineRoutes.servedModel(attempt.route(), parsed.model()));
			if (attemptHandle != null) {
				// §9.2.4：上游已提交——本 attempt 成为 call 的最终结算 attempt。
				attemptHandle.markSubmitted(null);
				billable.selectFinalAttempt(attemptHandle.attemptId());
				context.setFinalAttemptId(attemptHandle.attemptId());
			}
		}
		catch (RuntimeException e) {
			// A failed client write says nothing about upstream health; retrying only
			// wastes work.
			if (sink.disconnected)
				throw e;
			if (sink.chunkSent) {
				// Status and headers are committed: never splice a second
				// upstream's chunks into a stream the client already saw.
				// (chunkSent can only belong to THIS attempt: a chunked
				// failure of an earlier attempt already broke the chain.)
				// Billing: the attempt stays submitted; the outer handler
				// settles or books it as pending-verify.
				throw e;
			}
			// Provably not delivered: advance the plan WITHOUT moving billing
			// terminal state — this handle is simply superseded by the next.
			breaker.recordFailure(attempt.route());
			int next = ForwardingPolicy.nextViable(plan, from, e);
			if (next < 0) {
				throw e;
			}
			streamWithPlan(plan, next, rawJson, parsed, timeout, sink, context, pass,
					ForwardingPolicy.effectiveDelayMs(plan, from, next, e), prepared, billable, lastAttempt, budget);
		}
	}

	private record Completed(String body, Route route) {
	}

	/**
	 * T1.12 conversion-aware completion loop: a non-null {@code prepared} builds the
	 * upstream request from the already converted payload; null keeps the passthrough
	 * path untouched.
	 *
	 * <p>
	 * Chapter-9 §9.2.4: the winning attempt opens its own billing attempt handle, is
	 * marked submitted and selected as the call's final attempt INSIDE the loop
	 * (previously the caller marked the single pre-loop handle after the fact). Failed
	 * attempts that provably never delivered advance the plan without moving billing
	 * terminal state.
	 */
	private Completed completeWithPlan(List<ForwardingPolicy.Attempt> plan, int from, String rawJson,
			ParsedRequest parsed, int timeout, GatewayContext context, Map<String, String> pass, HeaderListener egress,
			long extraDelayMs, PreparedConversion prepared, BillableCall billable, BillableAttempt[] lastAttempt,
			ForwardingPolicy.AttemptBudget budget) {
		if (from >= plan.size()) {
			throw new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE);
		}
		ForwardingPolicy.Attempt attempt = plan.get(from);
		sleepBefore(attempt, extraDelayMs);
		// §9.2.4: no attempt may start on an exhausted budget.
		budget.checkBeforeAttempt(from + 1);
		if (!breaker.allow(attempt.route())) {
			CircuitOpenException open = new CircuitOpenException(attempt.route().id());
			int next = ForwardingPolicy.nextViable(plan, from, open);
			if (next < 0) {
				throw open;
			}
			return completeWithPlan(plan, next, rawJson, parsed, timeout, context, pass, egress,
					ForwardingPolicy.effectiveDelayMs(plan, from, next, open), prepared, billable, lastAttempt, budget);
		}
		BillableAttempt attemptHandle = beginBillableAttempt(billable, attempt.route().id(), lastAttempt);
		context.incrementAttemptCount();
		try {
			context.markUpstreamStart();
			UpstreamRequest request = prepared == null
					? EngineRoutes.request(withStreamUsageInjection(rawJson, parsed, attempt.route()), parsed,
							attempt.route(), budget.boundedTimeoutMs(timeout), pass)
					: EngineRoutes.convertedRequest(prepared.upstreamJson(), prepared.upstreamPath(), parsed,
							attempt.route(), budget.boundedTimeoutMs(timeout), pass);
			String body = provider.completeRaw(request, egress);
			breaker.recordSuccess(attempt.route());
			// §9.2.2 对账契约：记录实际服务的上游模型（targetModel 重写后）——
			// 与计费句柄无关（billing 关闭时同样记录）。
			context.setServedModel(EngineRoutes.servedModel(attempt.route(), parsed.model()));
			if (attemptHandle != null) {
				attemptHandle.markSubmitted(null);
				billable.selectFinalAttempt(attemptHandle.attemptId());
				context.setFinalAttemptId(attemptHandle.attemptId());
			}
			return new Completed(body, attempt.route());
		}
		catch (RuntimeException e) {
			breaker.recordFailure(attempt.route());
			int next = ForwardingPolicy.nextViable(plan, from, e);
			if (next < 0) {
				throw e;
			}
			return completeWithPlan(plan, next, rawJson, parsed, timeout, context, pass, egress,
					ForwardingPolicy.effectiveDelayMs(plan, from, next, e), prepared, billable, lastAttempt, budget);
		}
	}

	private static void sleepBefore(ForwardingPolicy.Attempt attempt, long extraDelayMs) {
		long total = attempt.delayBeforeMs() + Math.max(0, extraDelayMs);
		if (total > 0) {
			try {
				Thread.sleep(total);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("retry backoff interrupted", e);
			}
		}
	}

	private void writeError(HttpServletResponse response, GatewayDecision decision) throws IOException {
		response.setStatus(decision.status());
		response.setContentType(EngineHeaders.APPLICATION_JSON);
		// Chapter-9 §9.2.7: every 503 (capacity AND governance-unavailable) carries
		// the same Retry-After backoff contract.
		if (decision.status() == 503) {
			response.setHeader(EngineHeaders.RETRY_AFTER, "1");
		}
		response.getOutputStream().write(ProtocolCodec.errorJson(decision.reason()).getBytes(StandardCharsets.UTF_8));
		response.flushBuffer();
	}

	/**
	 * Chapter-9 §9.2.7: a governance dependency failure (Redis fail-closed) is a
	 * gateway-side outage, surfaced as 503 (same backoff contract as capacity) instead of
	 * masquerading as an upstream 502. §9.2.4: an exhausted attempt budget is an
	 * explainable 504.
	 */
	private static GatewayDecision errorDecision(Throwable e) {
		if (e instanceof GovernanceUnavailableException) {
			return GatewayDecision.deny(503, e.getMessage());
		}
		if (e instanceof ForwardingPolicy.DeadlineExceededException) {
			return GatewayDecision.deny(504, EngineReasons.upstream(e));
		}
		return GatewayDecision.deny(502, EngineReasons.upstream(e));
	}

	/**
	 * Chapter-9 §9.2.7: admission/finish paths outside the forwarding try (chain.enter /
	 * GatewayPipeline.finish) must still honor the 503 contract.
	 */
	@org.springframework.web.bind.annotation.ExceptionHandler(GovernanceUnavailableException.class)
	public void governanceUnavailable(HttpServletRequest request, HttpServletResponse response,
			GovernanceUnavailableException e) throws IOException {
		writeError(response, GatewayDecision.deny(503, e.getMessage()));
	}

	/**
	 * §9.2.4: client {@code X-LLMGW-Deadline} (absolute epoch-ms). Parse failures are
	 * ignored (0); past deadlines are ignored inside
	 * {@link ForwardingPolicy.AttemptBudget#of} — an untrusted header can never become an
	 * immediate kill switch.
	 */
	private static long parseDeadlineEpochMs(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		try {
			long deadline = Long.parseLong(value.trim());
			return deadline > 0 ? deadline : 0;
		}
		catch (NumberFormatException ignored) {
			return 0;
		}
	}

	/** RFC 7230 §3.2: header names are case-insensitive; take the FIRST match. */
	private static String firstValueIgnoreCase(Map<String, String> clientHeaders, String name) {
		for (Map.Entry<String, String> entry : clientHeaders.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * §9.2.4: one billing attempt per REAL upstream try; billing-disabled calls skip the
	 * handle but still count the attempt through the caller.
	 */
	private static BillableAttempt beginBillableAttempt(BillableCall billable, String routeId,
			BillableAttempt[] lastAttempt) {
		BillableAttempt handle = billable.enabled() ? billable.beginAttempt(routeId, "") : null;
		lastAttempt[0] = handle;
		return handle;
	}

	/**
	 * §9.2.6（ADR-0009）: token-protocol calls WITH an explicit request output bound
	 * reserve the PROVABLE worst case ({@code tokenCallBounded}); all other shapes keep
	 * the estimate-based input (1024 default output).
	 */
	private static BillingInput billingInput(ProtocolKind kind, ParsedRequest parsed, String rawJson) {
		java.util.OptionalLong bound = EngineRoutes.outputBoundTokens(parsed);
		if (isTokenKind(kind) && bound.isPresent()) {
			return BillingInput.tokenCallBounded(kind.wireName(), Math.max(1, parsed.estimatedTokens()),
					bound.getAsLong());
		}
		return new BillingInput(kind.wireName(), Math.max(1, parsed.estimatedTokens()), 1024,
				Math.max(1, parsed.units()), rawJson.length());
	}

	/** Metered-by-token protocols subject to the strict output bound. */
	private static boolean isTokenKind(ProtocolKind kind) {
		return kind == ProtocolKind.CHAT || kind == ProtocolKind.RESPONSES || kind == ProtocolKind.MESSAGES
				|| kind == ProtocolKind.EMBEDDINGS || kind == ProtocolKind.RERANK || kind == ProtocolKind.GEMINI;
	}

	/**
	 * Chapter-9 §9.2.5 include_usage injection: only a route that DECLARES
	 * {@code stream-usage-injection} (breaking byte-for-byte parity on purpose) gets
	 * {@code stream_options.include_usage=true} injected — and only for streaming CHAT
	 * passthrough bodies that do not already carry {@code stream_options}. The client
	 * still receives the ORIGINAL response bytes; only the upstream-bound body changes.
	 */
	private String withStreamUsageInjection(String rawJson, ParsedRequest parsed, Route route) {
		if (parsed.kind() != ProtocolKind.CHAT || !parsed.stream() || rawJson.contains("stream_options")
				|| !streamUsageInjectionEnabled(route.id())) {
			return rawJson;
		}
		return injectStreamUsage(rawJson);
	}

	/**
	 * Route-level injection flag, read from properties by route id (core Route
	 * untouched).
	 */
	private boolean streamUsageInjectionEnabled(String routeId) {
		for (GatewayProperties.RouteDef def : properties.getRoutes()) {
			if (routeId.equals(def.getId())) {
				return def.isStreamUsageInjection();
			}
		}
		return false;
	}

	private static String injectStreamUsage(String rawJson) {
		JsonNode root = ProtocolCodec.tryParse(rawJson);
		if (root == null || !root.isObject()) {
			return rawJson;
		}
		if (root.has("stream_options")) {
			return rawJson;
		}
		ObjectNode object = (ObjectNode) root;
		object.putObject("stream_options").put("include_usage", true);
		return ProtocolCodec.mapper().writeValueAsString(object);
	}

	/**
	 * T1.12 pre-header conversion error: the exception's own {@code status()} plus the
	 * entry-shaped JSON (matrix §10.2). CapabilityException keeps its 4xx;
	 * UpstreamException is 502.
	 */
	private void writeConversionError(HttpServletResponse response, ProtocolKind kind, RuntimeException error)
			throws IOException {
		int status = error instanceof CapabilityException capability ? capability.status()
				: error instanceof UpstreamException upstream ? upstream.status() : 502;
		response.setStatus(status);
		response.setContentType(EngineHeaders.APPLICATION_JSON);
		String body;
		if (conversion != null) {
			// Render the ENTRY error shape (matrix §10.2); an upstream failure carries
			// the
			// upstream_error code with its own 502, exactly like the streaming pre-header
			// path.
			CapabilityException rendered = error instanceof CapabilityException capability ? capability
					: new CapabilityException(UpstreamException.UPSTREAM_ERROR, status, "upstream", error.getMessage());
			body = conversion.errorJson(kind, rendered);
		}
		else {
			body = ProtocolCodec.errorJson(error.getMessage());
		}
		response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
		response.flushBuffer();
	}

	/**
	 * Matrix §11: the degradation grant header is gateway-only. Strip it from the
	 * upstream pass headers unconditionally (case-insensitive), regardless of the
	 * configured whitelist.
	 */
	private static Map<String, String> withoutDegradation(Map<String, String> pass) {
		Map<String, String> out = null;
		for (String name : pass.keySet()) {
			if (name.equalsIgnoreCase(DegradationGrant.HEADER)) {
				if (out == null) {
					out = new LinkedHashMap<>(pass);
				}
				out.remove(name);
			}
		}
		return out == null ? pass : out;
	}

	// RFC 7230 §3.2: header names are case-insensitive; firstValues preserves wire case,
	// so match ignoring case.
	private static String degradationGrantValue(Map<String, String> clientHeaders) {
		return firstValueIgnoreCase(clientHeaders, DegradationGrant.HEADER);
	}

	/**
	 * Upstream → client header forwarding. Called on the request thread before any chunk,
	 * while the response is still uncommitted.
	 */
	private HeaderListener egressListener(HttpServletResponse response) {
		return (status, headers) -> {
			if (response.isCommitted()) {
				return;
			}
			for (String name : headerPolicy.toClient(headers)) {
				List<String> values = headers.get(name);
				if (values != null && !values.isEmpty() && values.get(0) != null) {
					response.addHeader(name, values.get(0));
				}
			}
		};
	}

	private static Map<String, String> firstValues(HttpServletRequest request) {
		Map<String, String> out = new LinkedHashMap<>();
		var names = request.getHeaderNames();
		if (names == null) {
			return out;
		}
		for (String name : java.util.Collections.list(names)) {
			String value = request.getHeader(name);
			if (value != null) {
				out.put(name, value);
			}
		}
		return out;
	}

	/** One request = one virtual thread, so plain booleans are sufficient. */
	private static final class StreamSink implements ChunkSink {

		private final HttpServletResponse response;

		private final GatewayContext context;

		private final HeaderPolicy headerPolicy;

		/**
		 * T1.12: null = byte-for-byte passthrough; non-null transcodes upstream chat ->
		 * entry.
		 */
		private final StreamTranscoder transcoder;

		private OutputStream out;

		private boolean chunkSent;

		private boolean doneWritten;

		private boolean errorWritten;

		private boolean disconnected;

		/**
		 * T1.12: the transcoder emitted an entry terminal (success OR error); never
		 * double-write.
		 */
		private boolean transcoderTerminal;

		StreamSink(HttpServletResponse response, GatewayContext context, HeaderPolicy headerPolicy) {
			this(response, context, headerPolicy, null);
		}

		StreamSink(HttpServletResponse response, GatewayContext context, HeaderPolicy headerPolicy,
				StreamTranscoder transcoder) {
			this.response = response;
			this.context = context;
			this.headerPolicy = headerPolicy;
			this.transcoder = transcoder;
		}

		@Override
		public void onChunk(String rawSseLine) {
			if (rawSseLine.startsWith(":")) {
				// Transport-level comment frame (SSE heartbeat / upstream
				// comment): forward verbatim, but it is NOT protocol data --
				// no accounting, no first-chunk marking, no terminator check.
				write(rawSseLine);
				return;
			}
			if (transcoder != null) {
				// Feed EVERY upstream line: the transcoder self-guards post-terminal
				// lines and owns
				// the entry terminal. For chat the finish chunk latches terminalSeen()
				// but the
				// [DONE] sentinel still follows, so gating here would drop it and
				// mis-bill success
				// as a truncation. transcoderTerminal is an error-suppression latch, not
				// a stop gate.
				List<String> entries;
				try {
					entries = transcoder.onUpstreamLine(rawSseLine);
				}
				catch (RuntimeException e) {
					// Illegal upstream chunk: render the entry error in band, then
					// terminate.
					writeTail(transcoder.onUpstreamError(e));
					return;
				}
				for (String entry : entries) {
					writeEntry(entry);
				}
				if (transcoder.terminalSeen()) {
					transcoderTerminal = true;
				}
				return;
			}
			boolean done = SseChunkAccounting.apply(context.kind(), rawSseLine, context);
			if (!rawSseLine.isBlank() && !chunkSent) {
				context.markFirstChunk();
			}
			write(rawSseLine);
			if (!rawSseLine.isBlank()) {
				chunkSent = true;
			}
			if (done) {
				doneWritten = true;
			}
		}

		/**
		 * T1.12: account + write one framed ENTRY line; the entry terminator sets
		 * doneWritten.
		 */
		private void writeEntry(String entryLine) {
			String framed = entryLine + "\n\n";
			boolean done = SseChunkAccounting.apply(context.kind(), framed, context);
			if (!chunkSent) {
				context.markFirstChunk();
			}
			write(framed);
			chunkSent = true;
			if (done) {
				doneWritten = true;
			}
		}

		/**
		 * T1.12: write the transcoder's terminal/error tail and latch the entry terminal.
		 */
		private void writeTail(List<String> lines) {
			for (String line : lines) {
				writeEntry(line);
			}
			if (transcoder.terminalSeen()) {
				transcoderTerminal = true;
			}
		}

		@Override
		public void onHeaders(int status, Map<String, List<String>> headers) {
			// Called on the request thread before any chunk; the response is
			// still uncommitted (no flushBuffer before the first chunk).
			if (response.isCommitted()) {
				return;
			}
			for (String name : headerPolicy.toClient(headers)) {
				List<String> values = headers.get(name);
				if (values != null && !values.isEmpty() && values.get(0) != null) {
					response.addHeader(name, values.get(0));
				}
			}
		}

		@Override
		public void onDone() {
			// Upstream reached a clean EOF. Intentionally do NOT synthesize a
			// terminator frame: that would disguise a truncated stream as a
			// success. handleStream() inspects isComplete() and reports it.
			if (transcoder == null || (!chunkSent && !response.isCommitted())) {
				// Passthrough: no-op. Conversion with no data AND nothing committed:
				// leave the
				// pre-header truncation error to handleFailure (502 + entry JSON). A
				// heartbeat may
				// have committed the response without any entry data, though -- then the
				// transcoder
				// renders the entry error in band below, never a generic gateway shape.
				return;
			}
			// Rely on the transcoder's idempotent onUpstreamComplete (contract: emit the
			// terminal
			// once), NOT a transcoderTerminal gate: for chat the finish chunk latches
			// terminalSeen()
			// while [DONE] is still pending, so gating here would drop it and mis-bill
			// success.
			// A genuine truncation still yields an error event, never a fabricated
			// success.
			writeTail(transcoder.onUpstreamComplete());
		}

		/** True only when the upstream itself emitted the protocol terminator. */
		boolean isComplete() {
			return doneWritten;
		}

		/**
		 * T1.12: true once a terminal was delivered — either the upstream protocol
		 * terminator ({@link #isComplete()}) or an entry terminal the transcoder rendered
		 * (success or error). handleFailure uses this to avoid appending a SECOND error
		 * after the transcoder's terminal.
		 */
		boolean terminalHandled() {
			// transcoder.terminalSeen() is a monotonic latch: it stays true even if
			// writeEntry
			// disconnected mid-terminal-write (before doneWritten/transcoderTerminal were
			// set), so
			// handleFailure must not append a second error after the transcoder already
			// rendered one.
			return doneWritten || transcoderTerminal || (transcoder != null && transcoder.terminalSeen());
		}

		@Override
		public void onError(Throwable error) {
			// JdkHttpUpstream rethrows after this callback; no need to throw here.
			if (transcoder == null || (!chunkSent && !response.isCommitted())) {
				// Passthrough: no-op. Conversion pre-header (nothing committed):
				// handleFailure renders
				// 502 + entry JSON. A heartbeat-committed stream with no entry data still
				// renders the
				// entry error in band below.
				return;
			}
			// Rely on the transcoder's idempotent onUpstreamError, NOT a
			// transcoderTerminal gate:
			// a transport reset AFTER the semantic terminal must finish exactly like a
			// clean EOF
			// (chat gets [DONE] once and bills success), while a genuine mid-stream reset
			// still
			// yields an error event. Gating on terminalSeen would drop the former.
			writeTail(transcoder.onUpstreamError(error));
		}

		void writeErrorEvent(String reason) {
			if (errorWritten || doneWritten) {
				return;
			}
			errorWritten = true;
			try {
				write(ProtocolCodec.errorEvent(reason));
				// [DONE] follows only for protocols whose contract says so
				// (chat); the gateway never fabricates another protocol's
				// terminator, so responses/messages close bare.
				if (Protocols.of(context.kind()).appendDoneAfterInBandError() && !doneWritten) {
					write(ProtocolCodec.doneLine());
					doneWritten = true;
				}
			}
			catch (RuntimeException ignored) {
				// Best effort: the client may already be gone.
			}
		}

		/**
		 * Single-writer contract: one request runs on one virtual thread and
		 * {@link ChunkSink} is a blocking callback, so data and heartbeat writes are
		 * already serialized on the requesting thread. No lock is needed -- the plain
		 * boolean state above assumes the same single writer.
		 */
		private void write(String line) {
			try {
				if (out == null) {
					out = response.getOutputStream();
				}
				out.write(line.getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
			catch (IOException e) {
				disconnected = true;
				throw new IllegalStateException(e);
			}
		}

	}

}
