package io.github.leohou0430.llmgw.core.governance;

/**
 * Embedding SPI (v13, W4.2). The semantic cache's key generator: when an
 * embedder is configured, cache keys come from a quantized embedding of the
 * prompt instead of the exact body hash, so phrasing variants of the same
 * question share one entry. Implementations must be deterministic (same text
 * -> same vector) or the cache loses its mind.
 */
public interface Embedder {

    /** Deterministic embedding of the prompt text. */
    double[] embed(String text);

    /** Bucketed signature of a vector: the cache KEY. Coarser buckets = a
     * looser similarity threshold. Empty array = embedder failed (fall back). */
    static String signature(double[] vector, double bucketScale) {
        if (vector == null || vector.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(vector.length * 4);
        for (double v : vector) {
            out.append(Math.round(v * bucketScale)).append('.');
        }
        return out.toString();
    }
}
