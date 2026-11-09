package io.github.leohou0430.llmgw.governance.inmemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B2: bounded in-process vector store backing the semantic cache's similarity
 * lookup. Linear cosine scan — deliberately simple: at the intended cache
 * scale (thousands of entries) a linear pass is far cheaper than an ANN index
 * and has zero extra dependencies. Entries are evicted oldest-insertion when
 * the capacity is reached.
 */
public final class InMemoryVectorStore {

    private record Entry(double[] vector, String cacheKey) {
    }

    private final Map<String, Entry> byCacheKey = new ConcurrentHashMap<>();
    private final List<String> insertionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
    private final int capacity;

    public InMemoryVectorStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void put(String cacheKey, double[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        if (byCacheKey.size() >= capacity && !byCacheKey.containsKey(cacheKey)) {
            synchronized (insertionOrder) {
                if (!insertionOrder.isEmpty()) {
                    byCacheKey.remove(insertionOrder.remove(0));
                }
            }
        }
        byCacheKey.put(cacheKey, new Entry(vector.clone(), cacheKey));
        synchronized (insertionOrder) {
            insertionOrder.remove(cacheKey);
            insertionOrder.add(cacheKey);
        }
    }

    /**
     * Linear cosine-similarity scan; returns the cache key of the best match
     * above the threshold, or null.
     */
    public String findSimilar(double[] vector, double threshold) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        String best = null;
        double bestScore = threshold;
        for (Map.Entry<String, Entry> e : byCacheKey.entrySet()) {
            double score = cosine(vector, e.getValue().vector());
            if (score > bestScore) {
                bestScore = score;
                best = e.getValue().cacheKey();
            }
        }
        return best;
    }

    public int size() {
        return byCacheKey.size();
    }

    public void clear() {
        byCacheKey.clear();
        insertionOrder.clear();
    }

    static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
