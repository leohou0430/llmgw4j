package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.billing.BillingInput;
import io.github.leohou0430.llmgw.billing.BillingMeter;
import io.github.leohou0430.llmgw.billing.MeteredQuantity;
import io.github.leohou0430.llmgw.billing.MissingPriceException;
import io.github.leohou0430.llmgw.billing.PriceBook;
import io.github.leohou0430.llmgw.billing.StrictBoundRequiredException;
import io.github.leohou0430.llmgw.billing.UsageEvidence;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.GatewayContext;
import io.github.leohou0430.llmgw.core.governance.GatewayDecision;
import io.github.leohou0430.llmgw.core.governance.GatewayPipeline;
import io.github.leohou0430.llmgw.core.governance.GovernanceChain;
import io.github.leohou0430.llmgw.core.governance.GovernanceUnavailableException;
import io.github.leohou0430.llmgw.core.multipart.MultipartLimitException;
import io.github.leohou0430.llmgw.core.multipart.MultipartScanner;
import io.github.leohou0430.llmgw.core.multipart.MultipartUpload;
import io.github.leohou0430.llmgw.core.multipart.SpoolLimitException;
import io.github.leohou0430.llmgw.core.multipart.SpoolManager;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.core.protocol.Protocols;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.core.routing.Router;
import io.github.leohou0430.llmgw.core.routing.TaskRouteSticky;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import io.github.leohou0430.llmgw.core.upstream.CircuitBreaker;
import io.github.leohou0430.llmgw.core.upstream.CircuitOpenException;
import io.github.leohou0430.llmgw.core.upstream.HeaderListener;
import io.github.leohou0430.llmgw.core.upstream.HeaderPolicy;
import io.github.leohou0430.llmgw.core.upstream.UpstreamMethod;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.PreparedConversion;
import io.github.leohou0430.llmgw.conversion.StreamTranscoder;
import io.github.leohou0430.llmgw.conversion.UpstreamException;
import io.github.leohou0430.llmgw.provider.UpstreamProvider;
import io.github.leohou0430.llmgw.engine.EngineHeaders;
import io.github.leohou0430.llmgw.engine.EngineProfiles;
import io.github.leohou0430.llmgw.engine.EngineReasons;
import io.github.leohou0430.llmgw.engine.EngineRoutes;
import io.github.leohou0430.llmgw.engine.EngineTimings;
import io.github.leohou0430.llmgw.engine.ForwardingPolicy;
import io.github.leohou0430.llmgw.engine.SseChunkAccounting;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reactive data plane. Writes raw SSE lines straight through, no re-encoding.
 * <p>
 * Both streaming and non-streaming paths are fully asynchronous: no blocking
 * HttpClient.send and no boundedElastic offload on the request path. This is what makes
 * the comparison against virtual threads a fair concurrency-model experiment rather than
 * a comparison against a handicapped reactive engine.
 *
 * <p>
 * v12 multi-protocol: every POST endpoint (chat / responses / messages / images /
 * video-create) shares one handler parameterized by {@link ProtocolKind}; the kinds
 * differ only in request parsing, SSE terminator and response-side token/cache semantics,
 * all of which hang off ParsedRequest.kind().
 *
 * <p>
 * Streaming error contract (mirrors the servlet engine): a stream is successful ONLY when
 * the upstream emitted its protocol terminator ({@code [DONE]} /
 * {@code response.completed} / {@code message_stop}). A clean EOF without it is a silent
 * truncation. Any failure before the first chunk becomes HTTP 502 + JSON; after the first
 * chunk it becomes an in-band {@code event: error}. The trailing {@code [DONE]} after an
 * in-band error is CHAT-only: the gateway never fabricates another protocol's terminator,
 * so responses/messages streams simply close. Fallback routes are only tried before any
 * chunk has been emitted. Once the terminator has been seen, a late error is NOT a
 * failure -- the protocol exchange is already complete.
 *
 * <p>
 * Video tasks are asynchronous upstream jobs: create responses bind the task id to the
 * winning route ({@link TaskRouteSticky}), and poll/content GETs are pinned to that route
 * with NO fallback and NO circuit breaking -- a task exists only on the upstream that
 * created it. A STICKY MISS (unknown task id) resolves the wildcard route and DOES honor
 * the circuit breaker (v13): it is an ordinary route call, and hammering a dead wildcard
 * route must trip it.
 *
 * <p>
 * v13 forwarding: attempt ordering (same-route retry with backoff, then the fallback
 * chain) is delegated to {@link ForwardingPolicy}; header pass-through in both directions
 * is delegated to {@link HeaderPolicy}. Comment lines (": ...", SSE heartbeat) are
 * transport-level and excluded from accounting, TTFT and the first-chunk guard.
 */
@RestController
@Profile(EngineProfiles.WEBFLUX)
public class WebfluxGatewayController implements AutoCloseable {

	private final GovernanceChain chain;

	private final Router router;

	private final UpstreamProvider provider;

	private final CircuitBreaker breaker;

	private final GatewayProperties properties;

	/** W3.6: sticky store is an injectable SPI (in-memory default, Redis pack). */
	private final TaskRouteSticky taskRoutes;

	private io.github.leohou0430.llmgw.catalog.ResourceLifecycle resources;

	/** ADR-0007 billing pack; nullable so demo profiles without the pack still run. */
	private volatile io.github.leohou0430.llmgw.billing.BillingLedger billingLedger;

	private volatile io.github.leohou0430.llmgw.billing.PriceBook billingPrices;

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
	public void billingPack(io.github.leohou0430.llmgw.billing.BillingLedger ledger,
			io.github.leohou0430.llmgw.billing.PriceBook prices, BillingMeter meter) {
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
	 * T2.4 per-controller spool owner (lazy, one restricted temp dir + independent
	 * quota), mirroring the VT engine's instance ownership — never a static global.
	 * Closed via the inferred Spring destroy method on context shutdown.
	 */
	private volatile SpoolManager spools;

	private volatile Path spoolDir;

	private static final int UPLOAD_QUEUE_CAPACITY = 4;

	public WebfluxGatewayController(GovernanceChain chain, Router router, UpstreamProvider provider,
			CircuitBreaker breaker, GatewayProperties properties, HeaderPolicy headerPolicy,
			TaskRouteSticky taskRoutes) {
		this(chain, router, provider, breaker, properties, headerPolicy, taskRoutes, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public WebfluxGatewayController(GovernanceChain chain, Router router, UpstreamProvider provider,
			CircuitBreaker breaker, GatewayProperties properties, HeaderPolicy headerPolicy, TaskRouteSticky taskRoutes,
			@org.springframework.lang.Nullable ConversionService conversion) {
		this.chain = chain;
		this.router = router;
		this.provider = provider;
		this.breaker = breaker;
		this.properties = properties;
		this.taskRoutes = taskRoutes;
		this.headerPolicy = headerPolicy;
		this.conversion = conversion;
		GatewayProperties.Upstream.Retry retry = properties.getUpstream().getRetry();
		this.retryConfig = new ForwardingPolicy.RetryConfig(retry.getAttempts(), retry.getBackoffBaseMs(),
				retry.getBackoffMaxMs());
	}

	@PostMapping("/v1/chat/completions")
	public Mono<Void> handleChat(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.CHAT, request, response);
	}

	/**
	 * Task-7 Gemini native generation: model from the URL path; only the literal
	 * :generateContent / :streamGenerateContent suffixes can ever route, and the model
	 * must be a safe path segment (injection rejected with 400).
	 */
	@PostMapping("/v1beta/models/{model}:generateContent")
	public Mono<Void> handleGeminiGenerate(@PathVariable("model") String model, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleGemini(model, false, request, response);
	}

	@PostMapping("/v1beta/models/{model}:streamGenerateContent")
	public Mono<Void> handleGeminiStream(@PathVariable("model") String model, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleGemini(model, true, request, response);
	}

	private Mono<Void> handleGemini(String model, boolean streaming, ServerHttpRequest request,
			ServerHttpResponse response) {
		if (model == null || !model.matches("[A-Za-z0-9._\\-]+") || model.contains("..")) {
			return writeError(response, GatewayDecision.deny(400, "model path injection rejected"));
		}
		return handleKind(ProtocolKind.GEMINI, streaming, model, request, response);
	}

	@PostMapping("/v1/responses")
	public Mono<Void> handleResponses(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.RESPONSES, request, response);
	}

	@PostMapping("/v1/messages")
	public Mono<Void> handleMessages(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.MESSAGES, request, response);
	}

	@PostMapping("/v1/images/generations")
	public Mono<Void> handleImages(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.IMAGES, request, response);
	}

	@PostMapping("/v1/videos")
	public Mono<Void> handleVideoCreate(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.VIDEOS, request, response);
	}

	// W4.3 protocol-surface expansion: JSON-in/JSON-out pass-through kinds.
	@PostMapping("/v1/embeddings")
	public Mono<Void> handleEmbeddings(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.EMBEDDINGS, request, response);
	}

	@PostMapping("/v1/rerank")
	public Mono<Void> handleRerank(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.RERANK, request, response);
	}

	/** JSON in, BINARY out: same byte-faithful path as video content. */
	@PostMapping("/v1/audio/speech")
	public Mono<Void> handleSpeech(ServerHttpRequest request, ServerHttpResponse response) {
		return handleBinaryKind(ProtocolKind.SPEECH, "audio/mpeg", request, response);
	}

	@GetMapping("/v1/videos/{id}")
	public Mono<Void> handleVideoPoll(@PathVariable("id") String id, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id, EngineHeaders.APPLICATION_JSON, id,
				UpstreamMethod.GET, request, response);
	}

	@GetMapping("/v1/videos/{id}/content")
	public Mono<Void> handleVideoContent(@PathVariable("id") String id, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.VIDEOS, "/videos/" + id + "/content", "video/mp4", id, UpstreamMethod.GET,
				request, response);
	}

	// W4.3 completion of the market parity list: uploads, files, batches.
	@PostMapping("/v1/audio/transcriptions")
	public Mono<Void> handleTranscriptions(ServerHttpRequest request, ServerHttpResponse response) {
		return handleUpload(ProtocolKind.TRANSCRIPTIONS, request, response);
	}

	@PostMapping("/v1/files")
	public Mono<Void> handleFileUpload(ServerHttpRequest request, ServerHttpResponse response) {
		return handleUpload(ProtocolKind.FILES, request, response);
	}

	@GetMapping("/v1/files")
	public Mono<Void> handleFileList(ServerHttpRequest request, ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.FILES, "/files", EngineHeaders.APPLICATION_JSON, null, UpstreamMethod.GET,
				request, response);
	}

	@GetMapping("/v1/files/{id}")
	public Mono<Void> handleFileGet(@PathVariable("id") String id, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.FILES, "/files/" + id, EngineHeaders.APPLICATION_JSON, id,
				UpstreamMethod.GET, request, response);
	}

	@org.springframework.web.bind.annotation.DeleteMapping("/v1/files/{id}")
	public Mono<Void> handleFileDelete(@PathVariable("id") String id, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.FILES, "/files/" + id, EngineHeaders.APPLICATION_JSON, id,
				UpstreamMethod.DELETE, request, response);
	}

	@PostMapping("/v1/batches")
	public Mono<Void> handleBatchCreate(ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(ProtocolKind.BATCHES, request, response);
	}

	@GetMapping("/v1/batches")
	public Mono<Void> handleBatchList(ServerHttpRequest request, ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.BATCHES, "/batches", EngineHeaders.APPLICATION_JSON, null,
				UpstreamMethod.GET, request, response);
	}

	@GetMapping("/v1/batches/{id}")
	public Mono<Void> handleBatchPoll(@PathVariable("id") String id, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id, EngineHeaders.APPLICATION_JSON, id,
				UpstreamMethod.GET, request, response);
	}

	// B11: market-parity pass-through endpoints (models catalog, batch cancel,
	// file content download). All honor sticky routing where a resource is
	// instance-local and the circuit breaker where it is not.
	@GetMapping("/v1/models")
	public Mono<Void> handleModels(ServerHttpRequest request, ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.CHAT, "/models", EngineHeaders.APPLICATION_JSON, null, UpstreamMethod.GET,
				request, response);
	}

	@PostMapping("/v1/batches/{id}/cancel")
	public Mono<Void> handleBatchCancel(@PathVariable("id") String id, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.BATCHES, "/batches/" + id + "/cancel", EngineHeaders.APPLICATION_JSON, id,
				UpstreamMethod.POST, request, response);
	}

	@GetMapping("/v1/files/{id}/content")
	public Mono<Void> handleFileContent(@PathVariable("id") String id, ServerHttpRequest request,
			ServerHttpResponse response) {
		return handleStickyFetch(ProtocolKind.FILES, "/files/" + id + "/content", "application/octet-stream", id,
				UpstreamMethod.GET, request, response);
	}

	private Mono<Void> handleKind(ProtocolKind kind, ServerHttpRequest request, ServerHttpResponse response) {
		return handleKind(kind, false, null, request, response);
	}

	/** Path-model variant (Gemini): model and streaming flag come from the URL. */
	private Mono<Void> handleKind(ProtocolKind kind, boolean forceStream, String pathModel, ServerHttpRequest request,
			ServerHttpResponse response) {
		long arrived = System.nanoTime();
		long max = Math.max(1, properties.getMaxRequestBytes());
		Map<String, String> clientHeaders = firstValues(request.getHeaders());
		return DataBufferUtils.join(request.getBody(), (int) Math.min(max, Integer.MAX_VALUE)).map(buffer -> {
			String raw = buffer.toString(StandardCharsets.UTF_8);
			DataBufferUtils.release(buffer);
			return raw;
		})
			.defaultIfEmpty("{}")
			.publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
			.flatMap(raw -> dispatch(kind, forceStream, pathModel, raw, request.getHeaders().getFirst("Authorization"),
					clientHeaders, response, request.getHeaders().getFirst(EngineHeaders.REQUEST_ID), arrived))
			.onErrorResume(DataBufferLimitException.class,
					e -> writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE)));
	}

	/**
	 * W4.3 binary-response kind (speech): full governance, then a byte-for-byte
	 * pass-through. The bytes never round-trip a String. JSON errors before the first
	 * byte keep the standard gateway error contract.
	 */
	private Mono<Void> handleBinaryKind(ProtocolKind kind, String contentType, ServerHttpRequest request,
			ServerHttpResponse response) {
		long arrived = System.nanoTime();
		long max = Math.max(1, properties.getMaxRequestBytes());
		Map<String, String> clientHeaders = firstValues(request.getHeaders());
		return DataBufferUtils.join(request.getBody(), (int) Math.min(max, Integer.MAX_VALUE)).map(buffer -> {
			String raw = buffer.toString(StandardCharsets.UTF_8);
			DataBufferUtils.release(buffer);
			return raw;
		}).defaultIfEmpty("{}").publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).flatMap(rawJson -> {
			String requestId = GatewayPipeline.requestId(request.getHeaders().getFirst(EngineHeaders.REQUEST_ID));
			response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
			GatewayDecision decision = GatewayPipeline.enter(chain, router, kind,
					request.getHeaders().getFirst("Authorization"), rawJson);
			if (!decision.allowed()) {
				return writeError(response, decision);
			}
			GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
			Route route = decision.route();
			Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
			AtomicBoolean settled = new AtomicBoolean(false);
			return Mono.defer(() -> {
				if (!breaker.allow(route)) {
					return Mono.<byte[]>error(new CircuitOpenException(route.id()));
				}
				context.markUpstreamStart();
				return Mono.fromFuture(() -> provider.fetchRawAsync(EngineRoutes.postBytes(rawJson, decision.parsed(),
						route, properties.getUpstream().getTimeoutMs(), pass)));
			}).doOnSuccess(body -> breaker.recordSuccess(route)).doOnError(e -> {
				if (!(e instanceof CircuitOpenException)) {
					breaker.recordFailure(route);
				}
			}).flatMap(body -> {
				context.markUpstreamEnd();
				response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
				response.setStatusCode(HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
				return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
			}).onErrorResume(e -> {
				finishOnce(context, settled, false);
				return writeError(response, errorDecision(e));
			}).doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));
		})
			.onErrorResume(DataBufferLimitException.class,
					e -> writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE)));
	}

	private Mono<Void> dispatch(ProtocolKind kind, String rawJson, String auth, Map<String, String> clientHeaders,
			ServerHttpResponse response, String incomingRequestId, long arrivedNanos) {
		return dispatch(kind, false, null, rawJson, auth, clientHeaders, response, incomingRequestId, arrivedNanos);
	}

	private Mono<Void> dispatch(ProtocolKind kind, boolean forceStream, String pathModel, String rawJson, String auth,
			Map<String, String> clientHeaders, ServerHttpResponse response, String incomingRequestId,
			long arrivedNanos) {
		String requestId = GatewayPipeline.requestId(incomingRequestId);
		response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
		GatewayDecision admitted;
		if (pathModel != null) {
			// Gemini: probe once for the token estimate, then admit with the PATH
			// model and the URL streaming flag (the body never carries them).
			ParsedRequest probe;
			try {
				probe = Protocols.of(kind).parse(rawJson);
			}
			catch (RuntimeException e) {
				return writeError(response, GatewayDecision.deny(400, "invalid json: " + e.getMessage()));
			}
			ParsedRequest preParsed = new ParsedRequest(rawJson, kind, pathModel, forceStream, probe.promptText(),
					probe.estimatedTokens(), 1, null);
			admitted = GatewayPipeline.enter(chain, router, preParsed, auth);
		}
		else {
			admitted = GatewayPipeline.enter(chain, router, kind, auth, rawJson);
		}
		if (!admitted.allowed()) {
			return writeError(response, admitted);
		}
		var requestResources = resources == null ? null : resources.scoped(admitted);
		ParsedRequest parsed = admitted.parsed();
		GatewayDecision decision = requestResources == null ? admitted
				: admitted.withRoute(requestResources.referencedRoute(parsed, admitted.keyId(), admitted.route()));
		GatewayContext context = GatewayPipeline.context(decision, requestId, arrivedNanos);
		// Chapter-9 §9.4.5 关联头：session/turn 来自调用方权威执行上下文（大小写
		// 不敏感取第一个值），绑定到已认证主体写入审计，不参与准入与配额。
		context.setSessionId(firstValueIgnoreCase(clientHeaders, EngineHeaders.SESSION_ID));
		context.setTurnId(firstValueIgnoreCase(clientHeaders, EngineHeaders.TURN_ID));
		if (!(requestResources == null
				? EngineRoutes.ownsReferencedFiles(parsed, decision.keyId(), decision.route(), taskRoutes)
				: requestResources.ownsFiles(parsed, decision.keyId(), decision.route()))) {
			GatewayPipeline.finish(chain, context, false);
			return writeError(response, GatewayDecision.deny(404, "referenced file not found on this route"));
		}
		// Chapter-9 §9.4.5 / ADR-0007 客户幂等键：认证后、金额预留前判读——
		// 重复请求不占配额不预留，也不能借幂等键绕过授权。重复 → 409
		// idempotent_request_exists（流式不重放 SSE，只附原调用标识）。
		io.github.leohou0430.llmgw.engine.IdempotencyGate idem = io.github.leohou0430.llmgw.engine.IdempotencyGate
			.enter(idempotencyStore, decision.projectId().isBlank() ? decision.keyId() : decision.projectId(),
					firstValueIgnoreCase(clientHeaders, "Idempotency-Key"),
					io.github.leohou0430.llmgw.engine.IdempotencyGate.digest(kind.wireName(), decision.model(),
							kind.upstreamPath(), rawJson),
					requestId);
		if (idem != null && idem.isDuplicate()) {
			GatewayPipeline.finish(chain, context, false);
			return writeError(response,
					io.github.leohou0430.llmgw.core.governance.GatewayDecision.deny(409,
							"idempotent_request_exists: state=" + idem.result().status().name() + " call="
									+ idem.result().billingCallId() + " request=" + idem.result().requestId()));
		}
		// ADR-0007 金额准入：冻结售价、按协议计量单位估算并原子预留；缺计价
		// 规则或金额拒绝都不发上游（402）。
		// Chapter-9 §9.2.6（ADR-0009）：token 类协议携带显式输出上界时按上界预留；
		// 严格预算模式下无上界的 token 调用被 400 拒绝（不可证明的预留即软预算）。
		boolean strictBilling = properties.getBilling().isEnabled() && properties.getBilling().isStrictBudget();
		io.github.leohou0430.llmgw.engine.BillableCall billable;
		try {
			// Stage-1 scope mapping: blank tenant/project falls back to keyId.
			billable = io.github.leohou0430.llmgw.engine.BillableCall.open(billingLedger, billingPrices, billingMeter,
					billingInput(kind, parsed, rawJson),
					decision.tenantId().isBlank() ? decision.keyId() : decision.tenantId(),
					decision.projectId().isBlank() ? decision.keyId() : decision.projectId(), decision.model(),
					strictBilling);
		}
		catch (MissingPriceException missing) {
			GatewayPipeline.finish(chain, context, false);
			return writeError(response, GatewayDecision.deny(402, missing.getMessage()));
		}
		catch (StrictBoundRequiredException strict) {
			GatewayPipeline.finish(chain, context, false);
			return writeError(response,
					GatewayDecision.deny(400, "strict_budget_requires_output_bound: " + strict.getMessage()));
		}
		if (billable == null) {
			GatewayPipeline.finish(chain, context, false);
			return writeError(response, GatewayDecision.deny(402, "billing-insufficient-funds"));
		}
		context.setBillingCallId(billable.billingCallId());
		// Task-5 事件旁路：请求正文消毒记录；终态由响应路径推进，doFinally 兜底 INCOMPLETE。
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
		AtomicBoolean settled = new AtomicBoolean(false);
		// Chapter-9 §9.2.4 跨 attempt 截止时间：配置的 total-deadline-ms 与客户端
		// X-LLMGW-Deadline（绝对 epoch-ms）取较早者。解析失败或已过期的客户端头
		// 被忽略记 0——不可信的头部永远不能成为立即击杀开关。每个 attempt 的
		// 超时被截断到剩余预算；预算耗尽以可解释的 504 停止计划。
		ForwardingPolicy.AttemptBudget budget = ForwardingPolicy.AttemptBudget.of(
				properties.getUpstream().getTotalDeadlineMs(),
				parseDeadlineEpochMs(firstValueIgnoreCase(clientHeaders, EngineHeaders.DEADLINE)),
				System.currentTimeMillis(), arrivedNanos);
		// Chapter-9 §9.2.4：每个真实上游尝试一个独立计费 attempt；holder 记录
		// 最后一个句柄，供终态兜底与结算（异步链在订阅时写入）。
		AtomicReference<io.github.leohou0430.llmgw.engine.BillableAttempt> lastAttempt = new AtomicReference<>();

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
				return writeConversionError(response, kind, error);
			}
		}
		final PreparedConversion conversionHandle = prepared;
		// Matrix §11 security invariant: the degradation grant is gateway-only; strip it
		// from the
		// upstream pass headers unconditionally, regardless of the configured whitelist.
		final Map<String, String> upstreamPass = withoutDegradation(pass);

		if (!parsed.stream()) {
			// B8: explicit per-request cache bypass header (also skips the put).
			boolean cacheBypass = "1".equals(clientHeaders.get("x-llmgw-no-cache"));
			Optional<String> cached = cacheBypass ? Optional.empty() : chain.lookupCache(decision.keyId(), parsed);
			Mono<String> bodyMono;
			AtomicReference<Route> winner = new AtomicReference<>(route);
			if (cached.isPresent()) {
				// Tokens are accounted once, in the shared flatMap below.
				// Adding them here as well double-billed cache hits.
				context.markCached();
				bodyMono = Mono.just(cached.get());
				// 缓存命中没有上游成本：撤回金额预留（终态后重复撤回是 no-op）。
				billable.releaseUnsubmitted("cache hit");
				if (idem != null)
					idem.complete(true, null);
			}
			else {
				bodyMono = completeAttempts(EngineRoutes.forwardPlan(router, route, retry, parsed), 0, rawJson, parsed,
						timeout, context, winner, upstreamPass, egress, conversionHandle, billable, lastAttempt, budget)
					.map(Completed::body);
			}
			return bodyMono.flatMap(body -> {
				context.markUpstreamEnd();
				// T1.12: transcode the upstream body into the ENTRY body BEFORE
				// accounting,
				// cache, taskId binding and the write. A cache HIT is already
				// entry-shaped
				// (it was stored post-transcode), so it is never re-transcoded.
				String entryBody = body;
				if (conversionHandle != null && cached.isEmpty()) {
					try {
						entryBody = conversionHandle.transcodeResponse(body);
					}
					catch (CapabilityException | UpstreamException error) {
						throw new ConversionFailure(error);
					}
				}
				// Single parse: token count AND cacheability come from
				// the same tree. The body previously went through
				// readTree twice more (tokens + cache check).
				JsonNode root = ProtocolCodec.tryParse(entryBody);
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
					chain.cache(decision.keyId(), parsed, entryBody, ProtocolCodec.isCacheableResponse(kind, root));
					// A video job lives on the upstream that created it:
					// pin the task id (async-job protocols only) to the
					// route that served the create.
				}
				String taskId = cached.isEmpty() ? Protocols.of(kind).taskId(root) : "";
				if (!taskId.isEmpty()) {
					context.setTaskId(taskId);
					taskRoutes.bind(decision.keyId(), kind.wireName(), taskId, winner.get().id());
					if (requestResources != null)
						requestResources.created(decision.keyId(), kind, winner.get(), entryBody);
				}
				// ADR-0007 结算：上游真实调用按权威 usage 帧经计量器入账
				// （终态 CAS 仅一次；证据缺席不记零，挂账待核验）。§9.2.4：
				// 结算使用链内最终成功 attempt 的句柄（markSubmitted/selectFinal
				// 已在 completeAttempts 内完成）；holder 为空则撤回预留。
				if (cached.isEmpty()) {
					io.github.leohou0430.llmgw.engine.BillableAttempt finalAttempt = lastAttempt.get();
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
				if (record != null) {
					record.append(io.github.leohou0430.llmgw.recording.RecordedEvent
						.now(io.github.leohou0430.llmgw.recording.RecordedEvent.Direction.RESPONSE, entryBody));
					record.finish(io.github.leohou0430.llmgw.recording.RecordingOutcome.COMPLETE);
				}
				return writeJson(response, entryBody, context);
			}).onErrorResume(e -> {
				finishOnce(context, settled, false);
				io.github.leohou0430.llmgw.engine.BillableAttempt finalAttempt = lastAttempt.get();
				if (e instanceof CircuitOpenException) {
					billable.releaseUnsubmitted("circuit open before upstream commit");
					if (idem != null)
						idem.complete(true, null);
				}
				else if (e instanceof ForwardingPolicy.DeadlineExceededException) {
					// §9.2.4：预算在真正发起上游尝试前耗尽——证实未提交，
					// 撤回预留并以可解释的 504 停止计划。
					billable.releaseUnsubmitted(e.getMessage());
					if (idem != null)
						idem.complete(true, null);
				}
				else if (billable.enabled() && !billable.finished() && finalAttempt != null) {
					// 提交状态未知：保守挂账待核验（宁挂账不漏收）。
					// §9.2.4：兜底用最后一个 attempt 的句柄；前序未提交
					// attempt 不推进终态，直接被下一 attempt 取代。
					finalAttempt.markUnknown("upstream failure, submit state unknown: " + e.getMessage());
					if (idem != null)
						idem.complete(false, billable.billingCallId());
				}
				if (e instanceof ConversionFailure failure) {
					return writeConversionError(response, kind, failure.error());
				}
				return writeError(response, errorDecision(e));
			})
				// doFinally also sees CANCEL, which produces NO terminal
				// signal. Without it a client disconnect silently skipped
				// billing/audit and leaked the quota reservation.
				.doFinally(signal -> {
					finishOnce(context, settled, signal == SignalType.ON_COMPLETE);
					// CANCEL 时请求可能已发出：挂账保守处理（终态已推进则 no-op）。
					io.github.leohou0430.llmgw.engine.BillableAttempt cancelled = lastAttempt.get();
					if (signal == SignalType.CANCEL && billable.enabled() && !billable.finished()
							&& cancelled != null) {
						cancelled.markUnknown("client cancelled after admission");
						if (idem != null)
							idem.complete(false, billable.billingCallId());
					}
					else if (signal == SignalType.CANCEL && idem != null) {
						idem.complete(true, null);
					}
					if (record != null) {
						record.close(); // 终态已推进时幂等 no-op；否则记 INCOMPLETE
					}
					if (conversionHandle != null) {
						conversionHandle.close();
					}
				});
		}

		response.setStatusCode(HttpStatus.OK);
		response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
		response.getHeaders().setCacheControl(EngineHeaders.NO_CACHE);
		// writeWith() commits headers BEFORE subscribing to the body, so a
		// streaming response can only report work done up to this point.
		// Complete TTFT/upstream timings live in llmgw.ttft / llmgw.upstream.
		response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.preflight(context));
		if (!context.sessionId().isEmpty()) {
			// Chapter-9 §9.4.5：提交前回写权威 session，保证两端关联标识一致。
			response.getHeaders().set(EngineHeaders.SESSION_ID, context.sessionId());
		}

		AtomicBoolean started = new AtomicBoolean(false);
		AtomicBoolean doneSeen = new AtomicBoolean(false);
		Flux<String> upstream = streamAttempts(EngineRoutes.forwardPlan(router, route, retry, parsed), 0, rawJson,
				parsed, timeout, started, context, upstreamPass, egress, conversionHandle, billable, lastAttempt,
				budget);
		Flux<String> chunks;
		if (conversionHandle != null) {
			// T1.12: transcode upstream chat lines into ENTRY lines BEFORE accounting, so
			// the
			// terminator verdict, usage and the truncation guard all run on the entry
			// protocol.
			chunks = convertedStream(upstream, conversionHandle, kind, context, settled, started, doneSeen, response);
		}
		else {
			chunks = upstream.doOnNext(line -> {
				// Comment frames (": ...") are transport-level and must not
				// touch the protocol accounting or the terminator verdict.
				if (!line.startsWith(":") && SseChunkAccounting.apply(kind, line, context)) {
					doneSeen.set(true);
				}
			}).switchOnFirst((signal, rest) -> {
				if (signal.hasError()) {
					// No chunk was ever emitted: the status can still change.
					finishOnce(context, settled, false);
					return firstChunkError(response, signal.getThrowable(), kind);
				}
				if (signal.isOnComplete()) {
					// Not even one chunk: an empty stream is a truncation,
					// never a success with a fabricated terminator.
					finishOnce(context, settled, false);
					return firstChunkError(response, truncated(kind), kind);
				}
				// Append the completion check AFTER the upstream completes
				// normally. concatWith(Flux.defer) reads doneSeen at that
				// point rather than at assembly time.
				return rest.concatWith(Flux.defer(() -> {
					if (doneSeen.get()) {
						return Flux.empty();
					}
					finishOnce(context, settled, false);
					if (!started.get() && !response.isCommitted()) {
						return firstChunkError(response, truncated(kind), kind);
					}
					return terminalError(EngineReasons.truncated(kind), kind);
				}));
			}).onErrorResume(e -> {
				if (doneSeen.get()) {
					// The upstream already delivered its terminator. A
					// disconnect afterwards must not turn a complete answer
					// into a failed one, nor append a spurious error event.
					finishOnce(context, settled, true);
					return Flux.empty();
				}
				finishOnce(context, settled, false);
				if (started.get()) {
					return terminalError(EngineReasons.upstream(e), kind);
				}
				return firstChunkError(response, e, kind);
			}).doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));
		}

		Flux<DataBuffer> body = SseResponseBuffers.encode(chunks, response.bufferFactory());
		return response.writeWith(body)
			// ADR-0007 流式终态（覆盖完成/错误/取消三种信号，两个分支统一挂点）：
			// 终止帧已出 → 按已知量结算；已提交未知用量 → 挂账；未提交 → 撤回。
			// §9.2.4：使用最后一个真实 attempt 的句柄。
			.doFinally(signal -> {
				Boolean known = billStreamTerminal(billable, lastAttempt.get(), kind, parsed, started, doneSeen,
						context, signal);
				if (idem != null)
					idem.complete(known == null || known, billable.billingCallId());
				// Task-5：流式只记请求与终态（正文逐帧旁路过重，首版省略）。
				if (record != null) {
					if (doneSeen.get()) {
						record.finish(io.github.leohou0430.llmgw.recording.RecordingOutcome.COMPLETE);
					}
					record.close();
				}
			});
	}

	/** 统一结算：计量器提取 → 金额结算；证据未知挂账（complete 内部处理）。 */
	private void billWithExtract(io.github.leohou0430.llmgw.engine.BillableCall billable,
			io.github.leohou0430.llmgw.engine.BillableAttempt attempt, ProtocolKind kind, ParsedRequest parsed,
			UsageEvidence evidence) {
		if (!billable.enabled() || billable.finished()) {
			return;
		}
		if (billingMeter == null) {
			attempt.complete(evidence, io.github.leohou0430.llmgw.engine.BillableCall.OUTCOME_SETTLED);
			return;
		}
		MeteredQuantity actual = billingMeter.extract(billable.frozenPrice(), evidence, Math.max(1, parsed.units()),
				billingMeter.unitFor(kind.wireName()));
		if (actual.quality() == MeteredQuantity.Quality.UNKNOWN) {
			attempt.complete(UsageEvidence.unknown(), io.github.leohou0430.llmgw.engine.BillableCall.OUTCOME_SETTLED);
		}
		else {
			attempt.completeNano(actual.nano(), io.github.leohou0430.llmgw.engine.BillableCall.OUTCOME_SETTLED);
		}
	}

	/**
	 * 流式终态推进（终态幂等）：doneSeen（协议终止帧已出）→ 已知 usage 结算； 已发出请求但用量未知 → 挂账待核验；从未提交 → 撤回预留。
	 */
	private Boolean billStreamTerminal(io.github.leohou0430.llmgw.engine.BillableCall billable,
			io.github.leohou0430.llmgw.engine.BillableAttempt attempt, ProtocolKind kind, ParsedRequest parsed,
			AtomicBoolean started, AtomicBoolean doneSeen, GatewayContext context, SignalType signal) {
		if (!billable.enabled() || billable.finished()) {
			return null;
		}
		if (attempt == null) {
			// 防御：没有任何真实上游 attempt（无从结算）——撤回预留（终态幂等）。
			billable.releaseUnsubmitted("stream ended without a billable attempt");
			return true;
		}
		if (doneSeen.get()) {
			billWithExtract(billable, attempt, kind, parsed,
					UsageEvidence.known(Math.max(0, context.promptTokens()), Math.max(0, context.completionTokens())));
			return true;
		}
		if (started.get()) {
			if (context.reportedPromptTokensRaw() >= 0 && context.reportedCompletionTokensRaw() >= 0) {
				billWithExtract(billable, attempt, kind, parsed,
						UsageEvidence.known(context.reportedPromptTokensRaw(), context.reportedCompletionTokensRaw()));
				return true;
			}
			attempt.markUnknown("stream " + signal + " without a usage frame");
			return false;
		}
		billable.releaseUnsubmitted("stream failed before upstream commit");
		return true;
	}

	private static IllegalStateException truncated(ProtocolKind kind) {
		return new IllegalStateException(ProtocolCodec.truncatedMessage(kind));
	}

	/**
	 * In-band error after the first chunk. {@code [DONE]} follows only for protocols
	 * whose contract says so (chat): fabricating another protocol's terminator would
	 * corrupt the client's protocol view of the stream.
	 */
	private static Flux<String> terminalError(String reason, ProtocolKind kind) {
		return Protocols.of(kind).appendDoneAfterInBandError()
				? Flux.just(ProtocolCodec.errorEvent(reason), ProtocolCodec.doneLine())
				: Flux.just(ProtocolCodec.errorEvent(reason));
	}

	private Flux<String> firstChunkError(ServerHttpResponse response, Throwable error, ProtocolKind kind) {
		String reason = EngineReasons.upstream(error);
		if (response.isCommitted()) {
			// Defensive: headers already escaped, so fail in band instead.
			return terminalError(reason, kind);
		}
		// Chapter-9 §9.2.7: governance-unavailable is a gateway outage (503,
		// same Retry-After contract as capacity); §9.2.4: an exhausted attempt
		// budget is an explainable 504. Everything else stays 502.
		HttpStatus status = errorStatus(error);
		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		response.getHeaders().set(EngineHeaders.CACHE_CONTROL, EngineHeaders.NO_STORE);
		if (status == HttpStatus.SERVICE_UNAVAILABLE) {
			response.getHeaders().set(EngineHeaders.RETRY_AFTER, "1");
		}
		return Flux.just(ProtocolCodec.errorJson(reason));
	}

	/** §9.2.7/§9.2.4 error-to-status mapping shared by every render site. */
	private static HttpStatus errorStatus(Throwable e) {
		if (e instanceof GovernanceUnavailableException) {
			return HttpStatus.SERVICE_UNAVAILABLE;
		}
		if (e instanceof ForwardingPolicy.DeadlineExceededException) {
			return HttpStatus.GATEWAY_TIMEOUT;
		}
		return HttpStatus.BAD_GATEWAY;
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
	 * Chapter-9 §9.2.7: admission/finish paths outside the reactive chain (chain.enter /
	 * GatewayPipeline.finish) must still honor the 503 contract.
	 */
	@org.springframework.web.bind.annotation.ExceptionHandler(GovernanceUnavailableException.class)
	public Mono<Void> governanceUnavailable(ServerHttpResponse response, GovernanceUnavailableException e) {
		return writeError(response, GatewayDecision.deny(503, e.getMessage()));
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
	private static io.github.leohou0430.llmgw.engine.BillableAttempt beginBillableAttempt(
			io.github.leohou0430.llmgw.engine.BillableCall billable, String routeId,
			AtomicReference<io.github.leohou0430.llmgw.engine.BillableAttempt> lastAttempt) {
		io.github.leohou0430.llmgw.engine.BillableAttempt handle = billable.enabled()
				? billable.beginAttempt(routeId, "") : null;
		lastAttempt.set(handle);
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
	 * T1.12 streaming conversion pipeline. The transcoder turns upstream chat SSE lines
	 * into ENTRY lines BEFORE accounting, so the terminator verdict, usage and truncation
	 * guard all run on the entry protocol. The transcoder owns the entry terminal: once
	 * it emitted one (success or error) the engine must NOT append a second error or a
	 * fabricated success terminal.
	 */
	private Flux<String> convertedStream(Flux<String> upstream, PreparedConversion prepared, ProtocolKind kind,
			GatewayContext context, AtomicBoolean settled, AtomicBoolean started, AtomicBoolean doneSeen,
			ServerHttpResponse response) {
		StreamTranscoder transcoder = prepared.newStreamTranscoder();
		AtomicBoolean entryTerminal = new AtomicBoolean(false);
		AtomicBoolean dataSeen = new AtomicBoolean(false);
		return entryLines(upstream, transcoder, entryTerminal, dataSeen).doOnNext(line -> {
			if (!line.startsWith(":") && SseChunkAccounting.apply(kind, line, context)) {
				doneSeen.set(true);
			}
		}).switchOnFirst((signal, rest) -> {
			if (signal.hasError()) {
				finishOnce(context, settled, false);
				return conversionFirstChunkError(response, signal.getThrowable(), kind, transcoder, entryTerminal);
			}
			if (signal.isOnComplete()) {
				finishOnce(context, settled, false);
				return conversionFirstChunkError(response, truncated(kind), kind, transcoder, entryTerminal);
			}
			return rest.concatWith(Flux.defer(() -> {
				if (doneSeen.get()) {
					return Flux.empty();
				}
				if (entryTerminal.get()) {
					// The transcoder already rendered the entry error (truncation/illegal
					// chunk). Never append a second error; bill the failure once.
					finishOnce(context, settled, false);
					return Flux.empty();
				}
				finishOnce(context, settled, false);
				if (!started.get() && !response.isCommitted()) {
					return conversionFirstChunkError(response, truncated(kind), kind, transcoder, entryTerminal);
				}
				// Committed (e.g. a lone heartbeat) but no entry terminal yet: render the
				// entry-protocol error via the transcoder, never the generic gateway
				// shape.
				return Flux.fromIterable(frameTail(transcoder.onUpstreamComplete(), transcoder, entryTerminal));
			}));
		}).onErrorResume(e -> {
			if (doneSeen.get()) {
				finishOnce(context, settled, true);
				return Flux.empty();
			}
			if (entryTerminal.get()) {
				finishOnce(context, settled, false);
				return Flux.empty();
			}
			finishOnce(context, settled, false);
			if (started.get()) {
				// Entry data already reached the client: render the entry-protocol error
				// via
				// the transcoder, never the generic gateway shape.
				return Flux.fromIterable(frameTail(transcoder.onUpstreamError(e), transcoder, entryTerminal));
			}
			return conversionFirstChunkError(response, e, kind, transcoder, entryTerminal);
		}).doFinally(signal -> {
			finishOnce(context, settled, signal == SignalType.ON_COMPLETE);
			// Close AFTER error handling so onUpstreamError ran while the transcoder was
			// open.
			transcoder.close();
			prepared.close();
		});
	}

	/**
	 * Upstream chat lines -> framed ENTRY lines. Heartbeats (": ...") pass through
	 * verbatim and are never transcoded or accounted. The transcoder returns bare lines;
	 * the transport appends the SSE blank-line separator. An empty upstream (no data)
	 * emits no tail so the outer chain still renders a pre-header truncation error.
	 */
	private Flux<String> entryLines(Flux<String> upstream, StreamTranscoder transcoder, AtomicBoolean entryTerminal,
			AtomicBoolean dataSeen) {
		return upstream.concatMapIterable(line -> {
			if (line.startsWith(":")) {
				return List.of(line);
			}
			List<String> entries;
			try {
				entries = transcoder.onUpstreamLine(line);
			}
			catch (RuntimeException e) {
				// Illegal upstream chunk: render the entry error in band (parity with the
				// servlet engine's onChunk catch), never let it escape as a pre-header
				// 502.
				// A genuine TRANSPORT error originates upstream of this mapper and still
				// reaches onErrorResume below, so the no-data pre-header path is
				// preserved.
				entries = transcoder.onUpstreamError(e);
			}
			List<String> out = new java.util.ArrayList<>();
			for (String entry : entries) {
				out.add(entry + "\n\n");
			}
			if (transcoder.terminalSeen()) {
				entryTerminal.set(true);
			}
			// dataSeen tracks ENTRY output actually produced, matching the servlet
			// engine's
			// chunkSent. A raw upstream line that maps to zero entry lines (the real
			// DeepSeek
			// role-only first frame, a blank/non-data line) must NOT commit the stream,
			// so a
			// later EOF/error renders the same pre-header 502 on both engines instead of
			// a
			// 200 in-band error here.
			if (!out.isEmpty()) {
				dataSeen.set(true);
			}
			return out;
		}).concatWith(Flux.defer(() -> {
			if (!dataSeen.get()) {
				return Flux.empty();
			}
			return Flux.fromIterable(frameTail(transcoder.onUpstreamComplete(), transcoder, entryTerminal));
		})).onErrorResume(e -> {
			if (!dataSeen.get()) {
				// Pre-header: let the outer chain render the entry error JSON with 502.
				return Flux.error(e);
			}
			return Flux.fromIterable(frameTail(transcoder.onUpstreamError(e), transcoder, entryTerminal));
		});
	}

	private static List<String> frameTail(List<String> lines, StreamTranscoder transcoder,
			AtomicBoolean entryTerminal) {
		List<String> out = new java.util.ArrayList<>();
		for (String line : lines) {
			out.add(line + "\n\n");
		}
		if (transcoder.terminalSeen()) {
			entryTerminal.set(true);
		}
		return out;
	}

	/**
	 * T1.12 pre-header streaming error in the ENTRY shape (matrix §10.2): 502 + entry
	 * error JSON. Once headers are committed the status can never change, so this is only
	 * used pre-header.
	 */
	private Flux<String> conversionFirstChunkError(ServerHttpResponse response, Throwable error, ProtocolKind kind,
			StreamTranscoder transcoder, AtomicBoolean entryTerminal) {
		String reason = EngineReasons.upstream(error);
		if (response.isCommitted()) {
			// Headers already escaped: render the ENTRY-protocol error via the
			// transcoder, never the
			// generic gateway shape (a lone heartbeat can commit the stream with zero
			// entry data).
			return Flux.fromIterable(frameTail(transcoder.onUpstreamError(error), transcoder, entryTerminal));
		}
		response.setStatusCode(HttpStatus.BAD_GATEWAY);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		response.getHeaders().set(EngineHeaders.CACHE_CONTROL, EngineHeaders.NO_STORE);
		CapabilityException upstream = new CapabilityException(UpstreamException.UPSTREAM_ERROR,
				new UpstreamException(reason).status(), "upstream", reason);
		return Flux.just(conversion.errorJson(kind, upstream));
	}

	/**
	 * The attempt chain. Errors advance per {@link ForwardingPolicy#nextViable}; once a
	 * chunk reached the client ({@code started}), no further attempt is ever spliced in.
	 * Backoff delays for same-route retries are applied lazily (Mono.delay), so a
	 * first-attempt success pays nothing.
	 */
	/**
	 * The attempt chain. Errors advance per {@link ForwardingPolicy#nextViable}; once a
	 * chunk reached the client ({@code started}), no further attempt is ever spliced in.
	 * Backoff delays for same-route retries are applied lazily (Mono.delay), so a
	 * first-attempt success pays nothing.
	 *
	 * <p>
	 * Chapter-9 §9.2.4: every attempt that really reaches an upstream opens its own
	 * billing attempt handle (recorded into {@code lastAttempt}), the per-attempt timeout
	 * is capped to the remaining {@code budget}, and the budget is checked BEFORE any
	 * upstream contact. A stream that completes marks its handle submitted and selects it
	 * as the call's final attempt.
	 */
	private Flux<String> streamAttempts(List<ForwardingPolicy.Attempt> plan, int from, String rawJson,
			ParsedRequest parsed, int timeout, AtomicBoolean started, GatewayContext context, Map<String, String> pass,
			HeaderListener egress, PreparedConversion prepared, io.github.leohou0430.llmgw.engine.BillableCall billable,
			AtomicReference<io.github.leohou0430.llmgw.engine.BillableAttempt> lastAttempt,
			ForwardingPolicy.AttemptBudget budget) {
		if (from >= plan.size()) {
			return Flux.error(new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE));
		}
		ForwardingPolicy.Attempt attempt = plan.get(from);
		Flux<String> self = streamAttempt(rawJson, parsed, attempt, from + 1, timeout, started, context, pass, egress,
				prepared, billable, lastAttempt, budget);
		return self.onErrorResume(e -> {
			if (started.get()) {
				return Flux.error(e);
			}
			if (e instanceof ForwardingPolicy.DeadlineExceededException) {
				// §9.2.4: an exhausted budget stops the plan — no further tries.
				return Flux.error(e);
			}
			int next = ForwardingPolicy.nextViable(plan, from, e);
			if (next < 0) {
				return Flux.error(e);
			}
			Flux<String> rest = streamAttempts(plan, next, rawJson, parsed, timeout, started, context, pass, egress,
					prepared, billable, lastAttempt, budget);
			long delay = ForwardingPolicy.effectiveDelayMs(plan, from, next, e);
			return delay > 0 ? Mono.delay(Duration.ofMillis(delay)).thenMany(rest) : rest;
		});
	}

	/**
	 * One REAL upstream attempt (subscribed once per plan step). The billing attempt
	 * handle is opened inside the defer, so every actual upstream subscription gets a
	 * fresh handle; a provably undelivered failure leaves the handle without terminal
	 * state and simply advances the plan.
	 */
	private Flux<String> streamAttempt(String rawJson, ParsedRequest parsed, ForwardingPolicy.Attempt attempt,
			int attemptOrdinal, int timeout, AtomicBoolean started, GatewayContext context, Map<String, String> pass,
			HeaderListener egress, PreparedConversion prepared, io.github.leohou0430.llmgw.engine.BillableCall billable,
			AtomicReference<io.github.leohou0430.llmgw.engine.BillableAttempt> lastAttempt,
			ForwardingPolicy.AttemptBudget budget) {
		Route route = attempt.route();
		AtomicBoolean chunked = new AtomicBoolean(false);
		return Flux.defer(() -> {
			// §9.2.4: no attempt may start on an exhausted budget.
			budget.checkBeforeAttempt(attemptOrdinal);
			if (!breaker.allow(route)) {
				// Local failure, no network I/O: the fallback chain moves on.
				return Flux.<String>error(new CircuitOpenException(route.id()));
			}
			io.github.leohou0430.llmgw.engine.BillableAttempt attemptHandle = beginBillableAttempt(billable, route.id(),
					lastAttempt);
			context.incrementAttemptCount();
			context.markUpstreamStart();
			io.github.leohou0430.llmgw.core.upstream.UpstreamRequest request = prepared == null
					? EngineRoutes.request(withStreamUsageInjection(rawJson, parsed, route), parsed, route,
							budget.boundedTimeoutMs(timeout), pass)
					: EngineRoutes.convertedRequest(prepared.upstreamJson(), prepared.upstreamPath(), parsed, route,
							budget.boundedTimeoutMs(timeout), pass);
			return upstreamLines(request, egress).doOnNext(line -> {
				// Comment frames are transport-level: never a first chunk,
				// never a fallback trigger, invisible to TTFT.
				if (!line.isBlank() && !line.startsWith(":")) {
					if (chunked.compareAndSet(false, true)) {
						context.markFirstChunk();
					}
					started.set(true);
				}
			})
				// Connectivity verdicts only. Delivering data = healthy,
				// no data + exception = failed attempt. A downstream cancel
				// records nothing (the client went away, upstream is fine).
				.doOnComplete(() -> {
					if (chunked.get()) {
						breaker.recordSuccess(route);
					}
					// §9.2.2 对账契约：记录实际服务的上游模型（targetModel 重写后）——
					// 与计费句柄无关（billing 关闭时同样记录）。
					context.setServedModel(EngineRoutes.servedModel(route, parsed.model()));
					if (attemptHandle != null) {
						// §9.2.4：上游已提交——本 attempt 成为 call 的最终结算
						// attempt（未提交句柄不推进终态，直接被取代）。
						attemptHandle.markSubmitted(null);
						billable.selectFinalAttempt(attemptHandle.attemptId());
						context.setFinalAttemptId(attemptHandle.attemptId());
					}
				})
				.doOnError(e -> {
					if (!chunked.get()) {
						breaker.recordFailure(route);
					}
				});
		});
	}

	private record Completed(String body, Route route) {
	}

	/**
	 * Async equivalent of the servlet engine's blocking fallback loop. Every attempt
	 * stays lazy, so a cache hit or a cancelled subscription never issues an upstream
	 * request, and Netty's event loop is never blocked. The winning route is captured for
	 * video-task stickiness.
	 *
	 * <p>
	 * Chapter-9 §9.2.4: the winning attempt opens its own billing attempt handle, is
	 * marked submitted and selected as the call's final attempt INSIDE the chain
	 * (previously the caller marked the single pre-loop handle after the fact). Failed
	 * attempts that provably never delivered advance the plan without moving billing
	 * terminal state.
	 */
	private Mono<Completed> completeAttempts(List<ForwardingPolicy.Attempt> plan, int from, String rawJson,
			ParsedRequest parsed, int timeout, GatewayContext context, AtomicReference<Route> winner,
			Map<String, String> pass, HeaderListener egress, PreparedConversion prepared,
			io.github.leohou0430.llmgw.engine.BillableCall billable,
			AtomicReference<io.github.leohou0430.llmgw.engine.BillableAttempt> lastAttempt,
			ForwardingPolicy.AttemptBudget budget) {
		if (from >= plan.size()) {
			return Mono.error(new IllegalStateException(EngineReasons.NO_UPSTREAM_ROUTE));
		}
		ForwardingPolicy.Attempt attempt = plan.get(from);
		Mono<Completed> self = Mono.defer(() -> {
			// §9.2.4: no attempt may start on an exhausted budget.
			budget.checkBeforeAttempt(from + 1);
			if (!breaker.allow(attempt.route())) {
				return Mono.<Completed>error(new CircuitOpenException(attempt.route().id()));
			}
			io.github.leohou0430.llmgw.engine.BillableAttempt attemptHandle = beginBillableAttempt(billable,
					attempt.route().id(), lastAttempt);
			context.incrementAttemptCount();
			context.markUpstreamStart();
			io.github.leohou0430.llmgw.core.upstream.UpstreamRequest request = prepared == null
					? EngineRoutes.request(withStreamUsageInjection(rawJson, parsed, attempt.route()), parsed,
							attempt.route(), budget.boundedTimeoutMs(timeout), pass)
					: EngineRoutes.convertedRequest(prepared.upstreamJson(), prepared.upstreamPath(), parsed,
							attempt.route(), budget.boundedTimeoutMs(timeout), pass);
			return Mono.fromFuture(() -> provider.completeRawAsync(request, egress)).map(body -> {
				winner.set(attempt.route());
				// §9.2.2 对账契约：记录实际服务的上游模型（targetModel 重写后）——
				// 与计费句柄无关（billing 关闭时同样记录）。
				context.setServedModel(EngineRoutes.servedModel(attempt.route(), parsed.model()));
				if (attemptHandle != null) {
					attemptHandle.markSubmitted(null);
					billable.selectFinalAttempt(attemptHandle.attemptId());
					context.setFinalAttemptId(attemptHandle.attemptId());
				}
				return new Completed(body, attempt.route());
			})
				.doOnSuccess(c -> breaker.recordSuccess(attempt.route()))
				.doOnError(e -> breaker.recordFailure(attempt.route()));
		});
		return self.onErrorResume(e -> {
			if (e instanceof ForwardingPolicy.DeadlineExceededException) {
				// §9.2.4: an exhausted budget stops the plan — no further tries.
				return Mono.error(e);
			}
			int next = ForwardingPolicy.nextViable(plan, from, e);
			if (next < 0) {
				return Mono.error(e);
			}
			Mono<Completed> rest = completeAttempts(plan, next, rawJson, parsed, timeout, context, winner, pass, egress,
					prepared, billable, lastAttempt, budget);
			long delay = ForwardingPolicy.effectiveDelayMs(plan, from, next, e);
			return delay > 0 ? Mono.delay(Duration.ofMillis(delay)).then(rest) : rest;
		});
	}

	/**
	 * Task poll / fetch GET/DELETE (video, batch, files): governance + sticky routing,
	 * then a raw byte-for-byte pass-through. A PINNED task has no fallback and no circuit
	 * breaking -- the task exists only on the upstream that created it, and a
	 * create-route circuit must never strand a live job's polling. A STICKY MISS resolves
	 * the wildcard route, which is an ordinary route call and DOES honor the circuit
	 * breaker (v13). A null taskId (list endpoints) is always unpinned.
	 */
	private Mono<Void> handleStickyFetch(ProtocolKind kind, String upstreamPath, String contentType, String taskId,
			UpstreamMethod method, ServerHttpRequest request, ServerHttpResponse response) {
		return Mono.defer(() -> handleScopedFetch(kind, upstreamPath, contentType, taskId, method, request, response))
			.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
	}

	private Mono<Void> handleScopedFetch(ProtocolKind kind, String upstreamPath, String contentType, String taskId,
			UpstreamMethod method, ServerHttpRequest request, ServerHttpResponse response) {
		long arrived = System.nanoTime();
		String requestId = GatewayPipeline.requestId(request.getHeaders().getFirst(EngineHeaders.REQUEST_ID));
		response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
		ParsedRequest parsed = ParsedRequest.bodyless(kind);
		GatewayDecision admission = chain.enter(request.getHeaders().getFirst("Authorization"), parsed,
				router.resolve(kind, "*"));
		if (!admission.allowed()) {
			return writeError(response, admission);
		}
		var requestResources = resources == null ? null : resources.scoped(admission);
		if (requestResources != null && taskId == null
				&& (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES)) {
			GatewayContext listedContext = GatewayPipeline.context(admission, requestId, arrived);
			try {
				String body = requestResources.list(admission.keyId(), kind, request.getQueryParams().getFirst("limit"),
						request.getQueryParams().getFirst("after"));
				response.setStatusCode(HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				return response
					.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))))
					.doFinally(
							signal -> GatewayPipeline.finish(chain, listedContext, signal == SignalType.ON_COMPLETE));
			}
			catch (IllegalArgumentException failure) {
				GatewayPipeline.finish(chain, listedContext, false);
				return writeError(response, GatewayDecision.deny(400, failure.getMessage()));
			}
		}
		Route pinned = taskId == null ? null
				: requestResources != null && (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES)
						? requestResources.route(admission.keyId(), kind, taskId)
						: router.byId(taskRoutes.resolve(admission.keyId(), kind.wireName(), taskId));
		if ((taskId != null && pinned == null)
				|| (taskId == null && (kind == ProtocolKind.FILES || kind == ProtocolKind.BATCHES))) {
			GatewayPipeline.finish(chain, GatewayPipeline.context(admission, requestId, arrived), false);
			return writeError(response, GatewayDecision.deny(taskId == null ? 501 : 404,
					taskId == null ? "tenant-scoped resource listing is unavailable" : "resource not found"));
		}
		boolean isPinned = pinned != null;
		Route route = isPinned ? pinned : admission.route();
		GatewayDecision decision = admission.withRoute(route);
		GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
		AtomicBoolean settled = new AtomicBoolean(false);
		boolean stream = !EngineHeaders.APPLICATION_JSON.equals(contentType);
		if (stream) {
			return Mono.defer(() -> {
				if (!isPinned && !breaker.allow(route)) {
					return Mono.<Void>error(new CircuitOpenException(route.id()));
				}
				context.markUpstreamStart();
				response.setStatusCode(HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
				response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.preflight(context));
				Flux<DataBuffer> body = reactor.adapter.JdkFlowAdapter
					.flowPublisherToFlux(provider.fetchStream(EngineRoutes.methodFetch(route, upstreamPath, method,
							properties.getUpstream().getTimeoutMs())))
					.publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
					.map(chunk -> response.bufferFactory().wrap(chunk));
				return response.writeWith(body);
			}).doOnSuccess(v -> {
				context.markUpstreamEnd();
				if (!isPinned) {
					breaker.recordSuccess(route);
				}
			}).doOnError(e -> {
				if (!isPinned && !(e instanceof CircuitOpenException)) {
					breaker.recordFailure(route);
				}
			}).onErrorResume(e -> {
				finishOnce(context, settled, false);
				if (response.isCommitted()) {
					return Mono.error(e);
				}
				return writeError(response, errorDecision(e));
			}).doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));
		}
		return Mono.defer(() -> {
			if (!isPinned && !breaker.allow(route)) {
				return Mono.<byte[]>error(new CircuitOpenException(route.id()));
			}
			context.markUpstreamStart();
			return Mono.fromFuture(() -> provider.fetchRawAsync(
					EngineRoutes.methodFetch(route, upstreamPath, method, properties.getUpstream().getTimeoutMs())));
		}).doOnSuccess(body -> {
			if (!isPinned) {
				breaker.recordSuccess(route);
			}
		}).doOnError(e -> {
			if (!isPinned && !(e instanceof CircuitOpenException)) {
				breaker.recordFailure(route);
			}
		}).flatMap(body -> {
			if (requestResources != null && taskId != null)
				requestResources.observed(admission.keyId(), kind, taskId, route,
						new String(body, StandardCharsets.UTF_8), method == UpstreamMethod.DELETE);
			context.markUpstreamEnd();
			String upstream = EngineTimings.upstreamOrNull(context);
			if (upstream != null) {
				response.getHeaders().set(EngineHeaders.UPSTREAM_MS, upstream);
			}
			response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
			response.setStatusCode(HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
			DataBuffer buffer = response.bufferFactory().wrap(body);
			return response.writeWith(Mono.just(buffer));
		}).onErrorResume(e -> {
			finishOnce(context, settled, false);
			return writeError(response, errorDecision(e));
		}).doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));
	}

	/**
	 * W4.3 byte-body upload kinds (transcriptions, files): full governance via the
	 * multipart scanner, then a byte-faithful POST. T2.4: the client body is drained
	 * through {@link DataBufferBodySource} into the scanner+spool in bounded chunks
	 * (never a whole-body join), then forwarded as a cold {@link ByteStream}. The
	 * multipart Content-Type (with its boundary) rides the policy pass-through untouched.
	 */
	private Mono<Void> handleUpload(ProtocolKind kind, ServerHttpRequest request, ServerHttpResponse response) {
		long arrived = System.nanoTime();
		// T2.7: a declared Content-Length over the cap is rejected before the body
		// is subscribed; unknown length (-1) falls through to the scanner's
		// cumulative bound.
		long declared = request.getHeaders().getContentLength();
		if (declared > properties.getMaxRequestBytes()) {
			return writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
		}
		Map<String, String> clientHeaders = firstValues(request.getHeaders());
		String requestId = GatewayPipeline.requestId(request.getHeaders().getFirst(EngineHeaders.REQUEST_ID));
		response.getHeaders().set(EngineHeaders.REQUEST_ID, requestId);
		String boundary = multipartBoundary(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
		if (boundary == null) {
			return writeError(response, GatewayDecision.deny(400, "missing multipart boundary"));
		}
		MultipartScanner.Limits defaults = MultipartScanner.Limits.defaults();
		MultipartScanner.Limits limits = new MultipartScanner.Limits(defaults.maxFileBytes(),
				Math.max(1, properties.getMaxRequestBytes()), defaults.maxMetadataBytes());
		DataBufferBodySource source = new DataBufferBodySource(request.getBody(), UPLOAD_QUEUE_CAPACITY);
		return Mono.fromCallable(() -> {
			try {
				return MultipartUpload.prepare(source, boundary, limits, spools());
			}
			catch (Exception e) {
				source.close();
				throw e;
			}
		})
			.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
			.flatMap(prepared -> forwardUpload(kind, request, response, prepared, clientHeaders, requestId, arrived)
				.doFinally(signal -> prepared.close()))
			.onErrorResume(e -> {
				if (e instanceof MultipartLimitException || e instanceof SpoolLimitException) {
					return writeError(response, GatewayDecision.deny(413, EngineReasons.REQUEST_TOO_LARGE));
				}
				if (e instanceof IOException) {
					return writeError(response, GatewayDecision.deny(400, "invalid multipart body: " + e.getMessage()));
				}
				return Mono.error(e);
			});
	}

	/** Governance + byte-faithful forwarding of an already-spooled upload. */
	private Mono<Void> forwardUpload(ProtocolKind kind, ServerHttpRequest request, ServerHttpResponse response,
			MultipartUpload.Prepared prepared, Map<String, String> clientHeaders, String requestId, long arrived) {
		// Mirror DefaultProtocols.Multipart routing: FILES keys on "purpose",
		// TRANSCRIPTIONS on "model"; the prompt field rides as probe text.
		String routingField = kind == ProtocolKind.FILES ? "purpose" : "model";
		ParsedRequest parsed = new ParsedRequest("", kind, prepared.fields().getOrDefault(routingField, ""), false,
				prepared.fields().getOrDefault("prompt", ""), (int) Math.max(1L, prepared.totalBytes() / 4), 1, null);
		GatewayDecision decision = chain.enter(request.getHeaders().getFirst("Authorization"), parsed,
				router.resolve(kind, parsed.model()));
		if (!decision.allowed()) {
			return writeError(response, decision);
		}
		var requestResources = resources == null ? null : resources.scoped(decision);
		GatewayContext context = GatewayPipeline.context(decision, requestId, arrived);
		Map<String, String> pass = headerPolicy.toUpstream(kind, clientHeaders);
		AtomicBoolean settled = new AtomicBoolean(false);
		return Mono.defer(() -> {
			if (!breaker.allow(decision.route())) {
				return Mono.<byte[]>error(new CircuitOpenException(decision.route().id()));
			}
			context.markUpstreamStart();
			ByteStream body;
			try {
				body = prepared.body();
			}
			catch (IOException e) {
				return Mono.<byte[]>error(e);
			}
			return Mono.fromFuture(() -> provider.fetchRawAsync(EngineRoutes.postRawBytes(body, prepared.bodyLength(),
					parsed, decision.route(), properties.getUpstream().getTimeoutMs(), pass)));
		}).doOnSuccess(ignored -> breaker.recordSuccess(decision.route())).doOnError(e -> {
			if (!(e instanceof CircuitOpenException)) {
				breaker.recordFailure(decision.route());
			}
		}).flatMap(bytes -> {
			context.markUpstreamEnd();
			if (kind == ProtocolKind.FILES) {
				String resourceId = ProtocolCodec.tryParse(new String(bytes, StandardCharsets.UTF_8))
					.path("id")
					.asText("");
				if (!resourceId.isBlank()) {
					taskRoutes.bind(decision.keyId(), kind.wireName(), resourceId, decision.route().id());
					if (requestResources != null)
						requestResources.created(decision.keyId(), kind, decision.route(),
								new String(bytes, StandardCharsets.UTF_8));
				}
			}
			response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
			response.setStatusCode(HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
		}).onErrorResume(e -> {
			finishOnce(context, settled, false);
			return writeError(response, errorDecision(e));
		}).doFinally(signal -> finishOnce(context, settled, signal == SignalType.ON_COMPLETE));
	}

	@jakarta.annotation.PostConstruct
	public void recoverUploads() throws IOException {
		if (spoolDirectory != null)
			spools();
	}

	/** Lazily create the per-engine spool manager owning one restricted temp dir. */
	private synchronized SpoolManager spools() throws IOException {
		if (spools == null) {
			spoolDir = spoolDirectory == null ? Files.createTempDirectory("llmgw-webflux-spool-")
					: Path.of(spoolDirectory, "webflux");
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

	private Mono<Void> writeJson(ServerHttpResponse response, String body, GatewayContext context) {
		String upstream = EngineTimings.upstreamOrNull(context);
		if (upstream != null) {
			response.getHeaders().set(EngineHeaders.UPSTREAM_MS, upstream);
		}
		response.getHeaders().set(EngineHeaders.OVERHEAD_MS, EngineTimings.total(context));
		response.setStatusCode(HttpStatus.OK);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
		return response.writeWith(Mono.just(buffer));
	}

	private Mono<Void> writeError(ServerHttpResponse response, GatewayDecision decision) {
		response.setStatusCode(HttpStatus.valueOf(decision.status()));
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		// Chapter-9 §9.2.7: every 503 (capacity AND governance-unavailable) carries
		// the same Retry-After backoff contract.
		if (decision.status() == 503) {
			response.getHeaders().set(EngineHeaders.RETRY_AFTER, "1");
		}
		String body = ProtocolCodec.errorJson(decision.reason());
		DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
		return response.writeWith(Mono.just(buffer));
	}

	private void finishOnce(GatewayContext context, AtomicBoolean settled, boolean success) {
		if (settled.compareAndSet(false, true)) {
			GatewayPipeline.finish(chain, context, success);
		}
	}

	/**
	 * T1.12: a conversion failure raised AFTER the upstream call but BEFORE headers are
	 * committed. Carries the original exception so the error handler renders the entry
	 * shape with the exception's own status instead of the generic 502 upstream error.
	 */
	private static final class ConversionFailure extends RuntimeException {

		private final RuntimeException error;

		ConversionFailure(RuntimeException error) {
			super(error);
			this.error = error;
		}

		RuntimeException error() {
			return error;
		}

	}

	/**
	 * T1.12 pre-header conversion error: the exception's own {@code status()} plus the
	 * entry-shaped JSON (matrix §10.2). CapabilityException keeps its 4xx;
	 * UpstreamException is 502.
	 */
	private Mono<Void> writeConversionError(ServerHttpResponse response, ProtocolKind kind, RuntimeException error) {
		int status = error instanceof CapabilityException capability ? capability.status()
				: error instanceof UpstreamException upstream ? upstream.status() : 502;
		response.setStatusCode(HttpStatus.valueOf(status));
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		response.getHeaders().set(EngineHeaders.CACHE_CONTROL, EngineHeaders.NO_STORE);
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
		DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
		return response.writeWith(Mono.just(buffer));
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
	 * Upstream → client header forwarding. The listener fires on the HTTP client's
	 * response thread strictly before any body byte flows; writes then happen-before the
	 * controller's body writes (Reactor queue HB), so mutating the still-uncommitted
	 * headers here is race-free. A cache hit never fires a listener -- cached responses
	 * carry no upstream headers.
	 */
	private HeaderListener egressListener(ServerHttpResponse response) {
		return (status, headers) -> {
			if (response.isCommitted()) {
				return;
			}
			for (String name : headerPolicy.toClient(headers)) {
				List<String> values = headers.get(name);
				if (values != null && !values.isEmpty() && values.get(0) != null) {
					response.getHeaders().add(name, values.get(0));
				}
			}
		};
	}

	private static Map<String, String> firstValues(HttpHeaders headers) {
		Map<String, String> out = new LinkedHashMap<>();
		// Spring Framework 7: HttpHeaders no longer implements Map.
		for (String name : headers.headerNames()) {
			String value = headers.getFirst(name);
			if (value != null) {
				out.put(name.toLowerCase(Locale.ROOT), value);
			}
		}
		return out;
	}

	private Flux<String> upstreamLines(io.github.leohou0430.llmgw.core.upstream.UpstreamRequest request,
			HeaderListener listener) {
		return Flux.defer(() -> {
			int heartbeatMs = properties.getUpstream().getHeartbeatIntervalMs();
			if (heartbeatMs <= 0)
				return reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(provider.streamRaw(request, listener));
			var ready = reactor.core.publisher.Sinks.<Boolean>one();
			var ended = reactor.core.publisher.Sinks.<Void>one();
			HeaderListener headers = (status, values) -> {
				listener.onHeaders(status, values);
				ready.tryEmitValue(status >= 200 && status < 300);
			};
			Flux<String> lines = reactor.adapter.JdkFlowAdapter
				.flowPublisherToFlux(provider.streamRaw(request, headers));
			return Flux.merge(1, lines.doFinally(signal -> ended.tryEmitEmpty()),
					ready.asMono()
						.filter(Boolean::booleanValue)
						.flatMapMany(ignored -> Flux.interval(java.time.Duration.ofMillis(heartbeatMs))
							.onBackpressureDrop()
							.map(tick -> ": keep-alive\n\n"))
						.takeUntilOther(ended.asMono()));
		});
	}

}
