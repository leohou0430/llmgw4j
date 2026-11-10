package io.github.leohou0430.llmgw.tokenizer.jtokkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B6: real tokenizer determinism + non-zero counts (heuristic parity smoke). */
class JtokkitTokenCounterTest {

    private final JtokkitTokenCounter counter = new JtokkitTokenCounter();

    @Test
    void deterministicCounts() {
        assertEquals(counter.count("hello world, this is a benchmark"),
                counter.count("hello world, this is a benchmark"));
    }

    @Test
    void englishTextCountsReasonably() {
        int tokens = counter.count("The quick brown fox jumps over the lazy dog");
        assertTrue(tokens >= 5 && tokens <= 20, "cl100k for this sentence: " + tokens);
    }

    @Test
    void emptyAndNullAreZero() {
        assertEquals(0, counter.count(""));
        assertEquals(0, counter.count(null));
    }

    @Test
    void nameIsLabeled() {
        assertEquals("jtokkit-cl100k", counter.name());
    }
}
