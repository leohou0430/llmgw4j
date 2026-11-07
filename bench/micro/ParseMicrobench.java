import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;

/**
 * Network-free A/B for the v8 request-parsing change.
 *
 * <p>Compares the v7.2 implementation (full JsonNode tree + second walk over
 * messages) against the v8 streaming parser. Both call the SAME fingerprint()
 * and estimateTokens(), so the only variable is tree-vs-streaming traversal.
 *
 * <p>Run via bin/run-parse-microbench.sh. Interleaved rounds cancel JIT/CPU
 * drift; ThreadMXBean reports allocated bytes per operation.
 */
public final class ParseMicrobench {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static volatile long sink;

    /** Exact v7.2 logic, kept here only as the A/B reference. */
    private static ParsedRequest treeParse(String rawJson) {
        JsonNode root = MAPPER.readTree(rawJson);
        String model = root.has("model") ? root.get("model").asText() : "default";
        boolean stream = root.has("stream") && root.get("stream").asBoolean();
        StringBuilder prompt = new StringBuilder();
        JsonNode messages = root.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode content = message.get("content");
                if (content != null && content.isTextual()) {
                    prompt.append(content.asText());
                }
            }
        }
        String text = prompt.toString();
        return new ParsedRequest(rawJson, model, stream, text,
                ProtocolCodec.estimateTokens(text), oldFingerprint(rawJson));
    }

    /** Exact v7.2 fingerprint: fresh JCA provider lookup on every request. */
    private static String oldFingerprint(String rawJson) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String payload(int pad, boolean streaming) {
        StringBuilder sb = new StringBuilder(128 + pad);
        sb.append("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"");
        sb.append("benchmark non-stream run=abc123 vu=1 iter=42");
        sb.append("x".repeat(pad));
        sb.append("\"}],\"stream\":").append(streaming)
          .append(",\"temperature\":0.7,\"max_tokens\":2048}");
        return sb.toString();
    }

    private static String payload(int pad) {
        return payload(pad, false);
    }

    private static long consume(ParsedRequest p) {
        String fp = p.cacheFingerprint();
        return p.model().length() + (p.stream() ? 1 : 0) + p.estimatedTokens()
                + (fp == null ? 0 : fp.length()) + p.promptText().length();
    }

    /** Cost of the v7.2 per-request URL construction vs the v8 cached URI. */
    private static void urlBreakdown(int iterations) {
        String base = "http://127.0.0.1:9090/mock/v1";
        java.net.URI cached = java.net.URI.create(base + "/chat/completions");
        long oldNs = 0, newNs = 0;
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            String url = base.endsWith("/chat/completions") ? base
                    : base.replaceAll("/+$", "") + "/chat/completions";
            sink += java.net.URI.create(url).toString().length();
            oldNs += System.nanoTime() - t;
        }
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            sink += cached.toString().length();
            newNs += System.nanoTime() - t;
        }
        System.out.printf("# url_ns_op: v7.2_regex_plus_URI=%.0f v8_cached_URI=%.0f speedup=%.1fx%n",
                oldNs / (double) iterations, newNs / (double) iterations,
                oldNs / (double) newNs);
    }

    /** Isolated cost centres, so optimisation effort follows measurement. */
    private static void breakdown(String body, int iterations) {
        // 1. tree build only; 2. streaming scan only; 3. SHA-256 with fresh
        // JCA lookup (v7.2); 4. SHA-256 with per-thread digest (v8);
        // 5. raw UTF-8 encoding; 6. token-estimate loop over the prompt.
        long[] totals = new long[6];
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            JsonNode root = MAPPER.readTree(body);
            sink += root.size();
            totals[0] += System.nanoTime() - t;

            t = System.nanoTime();
            sink += consume(ProtocolCodec.parse(body));
            totals[1] += System.nanoTime() - t;
        }
        // The rest are measured separately because parse() already includes them.
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            try {
                sink += java.security.MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).length;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            totals[2] += System.nanoTime() - t;

            t = System.nanoTime();
            sink += ProtocolCodec.fingerprint(body).length();
            totals[3] += System.nanoTime() - t;

            t = System.nanoTime();
            sink += body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            totals[4] += System.nanoTime() - t;
        }
        String prompt = "x".repeat(Math.max(1, body.length() / 2));
        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            sink += ProtocolCodec.estimateTokens(prompt);
            totals[5] += System.nanoTime() - t;
        }
        System.out.printf("# breakdown @%dB (ns/op): tree_only=%.0f full_v8_parse=%.0f "
                        + "sha256_fresh=%.0f sha256_threadlocal=%.0f utf8_encode=%.0f "
                        + "token_loop=%.0f%n",
                body.length(), totals[0] / (double) iterations, totals[1] / (double) iterations,
                totals[2] / (double) iterations, totals[3] / (double) iterations,
                totals[4] / (double) iterations, totals[5] / (double) iterations);
    }

    private static long runParse(boolean streaming, String body, int iterations) {
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            acc += consume(streaming ? ProtocolCodec.parse(body) : treeParse(body));
        }
        return acc;
    }

    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 50_000;
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        System.out.printf("payload_bytes,tree_ns_op,stream_ns_op,speedup,tree_bytes_op,stream_bytes_op,alloc_reduction%n");
        for (int pad : new int[]{0, 1024, 4096, 16384}) {
            String body = payload(pad);
            // Warm up BOTH implementations so neither gets an unfair JIT head start.
            for (int i = 0; i < 20_000; i++) {
                sink += consume(treeParse(body));
                sink += consume(ProtocolCodec.parse(body));
            }
            long treeNs = 0, streamNs = 0, treeBytes = 0, streamBytes = 0;
            for (int round = 0; round < 5; round++) {
                long t0 = System.nanoTime();
                long b0 = bean.getThreadAllocatedBytes(tid);
                sink += runParse(false, body, iterations);
                long b1 = bean.getThreadAllocatedBytes(tid);
                treeNs += System.nanoTime() - t0;
                treeBytes += b1 - b0;

                long s0 = System.nanoTime();
                long b2 = bean.getThreadAllocatedBytes(tid);
                sink += runParse(true, body, iterations);
                long b3 = bean.getThreadAllocatedBytes(tid);
                streamNs += System.nanoTime() - s0;
                streamBytes += b3 - b2;
            }
            int ops = iterations * 5;
            double tn = treeNs / (double) ops, sn = streamNs / (double) ops;
            double tb = treeBytes / (double) ops, sb = streamBytes / (double) ops;
            System.out.printf("%d,%.1f,%.1f,%.2fx,%.0f,%.0f,%.1f%%%n",
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    tn, sn, tn / sn, tb, sb, (1 - sb / tb) * 100);
            if (pad >= 4096) {
                breakdown(body, Math.max(1000, iterations / 5));
            }
        }
        if (sink == 0) {
            System.out.println("sink was zero");
        }

        // Streaming requests are never cached: v8 skips the whole-body hash.
        urlBreakdown(Math.max(10_000, iterations));
        System.out.println("# streaming payload: v7.2 always hashes, v8 skips it");
        System.out.println("stream_payload_bytes,v72_ns_op,v8_ns_op,speedup");
        for (int pad : new int[]{0, 4096, 16384}) {
            String body = payload(pad, true);
            for (int i = 0; i < 20_000; i++) {
                sink += consume(treeParse(body));
                sink += consume(ProtocolCodec.parse(body));
            }
            long oldNs = 0, newNs = 0;
            for (int round = 0; round < 5; round++) {
                long t0 = System.nanoTime();
                sink += runParse(false, body, iterations);
                oldNs += System.nanoTime() - t0;
                long t1 = System.nanoTime();
                sink += runParse(true, body, iterations);
                newNs += System.nanoTime() - t1;
            }
            double on = oldNs / (double) (iterations * 5);
            double nn = newNs / (double) (iterations * 5);
            System.out.printf("%d,%.1f,%.1f,%.2fx%n", body.length(), on, nn, on / nn);
        }
    }
}
