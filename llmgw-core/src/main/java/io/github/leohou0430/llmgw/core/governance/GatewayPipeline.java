package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.Router;

/** Engine-neutral request pipeline: both data planes call exactly these three methods. */
public final class GatewayPipeline {

    private GatewayPipeline() {
    }

    public static GatewayDecision enter(GovernanceChain chain, Router router,
                                        String authorizationHeader, String rawJson) {
        ParsedRequest request;
        try {
            request = ProtocolCodec.parse(rawJson);
        } catch (RuntimeException e) {
            return GatewayDecision.deny(400, "invalid json: " + e.getMessage());
        }
        return chain.enter(authorizationHeader, request, router.resolve(request.model()));
    }

    /** Reuses the request parsed during enter(); no second JSON traversal. */
    public static GatewayContext context(GatewayDecision decision) {
        ParsedRequest request = decision.parsed();
        return new GatewayContext(decision.keyId(), request.model(),
                decision.route() == null ? "" : decision.route().id(),
                request.stream(), request.estimatedTokens());
    }

    /** Same as {@link #context(GatewayDecision)} plus the correlation id. */
    public static GatewayContext context(GatewayDecision decision, String requestId) {
        ParsedRequest request = decision.parsed();
        return new GatewayContext(decision.keyId(), request.model(),
                decision.route() == null ? "" : decision.route().id(),
                request.stream(), request.estimatedTokens(), requestId);
    }

    /**
     * v10: start the clock when the request entered the data plane rather than
     * after admission, so TTFT and gateway overhead include body read + parse.
     */
    public static GatewayContext context(GatewayDecision decision, String requestId,
                                         long arrivedNanos) {
        ParsedRequest request = decision.parsed();
        return new GatewayContext(decision.keyId(), request.model(),
                decision.route() == null ? "" : decision.route().id(),
                request.stream(), request.estimatedTokens(), requestId, arrivedNanos);
    }

    /**
     * Echo the caller's {@code X-Request-Id} or mint a short correlation id.
     * Both engines use this so every response header, audit event and log line
     * for one request can be joined end to end.
     * <p>Correlation ids need uniqueness, not cryptographic strength:
     * {@link java.util.UUID#randomUUID} synchronizes on a shared SecureRandom,
     * which is a measurable contention point at ~10k RPS across 16 cores.
     * Two 32-bit hex halves from a thread-local PRNG give 64 random bits.
     */
    public static String requestId(String incoming) {
        if (incoming != null && !incoming.isBlank()) {
            return incoming.trim();
        }
        // Fixed-width 64-bit hex, thread-local PRNG, zero synchronization.
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        long bits = ((long) random.nextInt() << 32) | (random.nextInt() & 0xFFFFFFFFL);
        char[] hex = new char[16];
        for (int i = 15; i >= 0; i--) {
            hex[i] = Character.forDigit((int) (bits & 0xF), 16);
            bits >>>= 4;
        }
        return "gw-" + new String(hex);
    }

    public static void finish(GovernanceChain chain, GatewayContext context, boolean success) {
        chain.finish(context, success);
    }

    /**
     * One error contract for both engines: unwrap the JDK HttpClient / Reactor
     * wrappers so the wire error says "upstream: Connection refused" instead of
     * a nested CompletionException chain. Never returns null.
     */
    public static String errorMessage(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == null) {
            return "unknown upstream error";
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : message;
    }
}
