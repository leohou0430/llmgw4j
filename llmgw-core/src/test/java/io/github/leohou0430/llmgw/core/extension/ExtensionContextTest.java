package io.github.leohou0430.llmgw.core.extension;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionContextTest {
    @Test void definitionsAreLazyReplaceableAndScopedToOneRuntime() {
        var calls = new AtomicInteger();
        try (var a = new ExtensionContext(); var b = new ExtensionContext()) {
            a.bind(String.class, () -> { calls.incrementAndGet(); return "before"; });
            a.replace(String.class, () -> "after");
            b.bind(String.class, () -> "other");
            assertEquals(0, calls.get());
            assertThrows(IllegalStateException.class, () -> a.loadModule("missing-module"));
            a.seal(); b.seal();
            assertEquals("after", a.get(String.class));
            assertEquals("other", b.get(String.class));
            assertThrows(IllegalStateException.class, () -> a.bind(Integer.class, () -> 1));
            assertThrows(IllegalStateException.class, () -> a.loadModule("missing-module"));
        }
    }
    @Test void duplicateAndCircularDefinitionsFailClearly() {
        try (var context = new ExtensionContext()) {
            context.bind(String.class, () -> context.get(String.class));
            assertThrows(IllegalStateException.class, () -> context.bind(String.class, () -> "duplicate"));
            assertThrows(IllegalStateException.class, () -> context.get(String.class));
        }
    }
    @Test void ownedResourcesCloseInReverseOrderOnceAndExternalOnesStayOpen() {
        var events = new ArrayList<String>();
        class First implements AutoCloseable { public void close() { events.add("first"); } }
        class Last implements AutoCloseable { public void close() { events.add("last"); } }
        var context = new ExtensionContext();
        context.bind(First.class, First::new);
        context.bind(Last.class, Last::new);
        context.external(AutoCloseable.class, () -> events.add("external"));
        assertSame(context.get(First.class),context.get(First.class));
        context.get(Last.class);context.get(AutoCloseable.class);
        context.close();context.close();
        assertEquals(List.of("last","first"),events);
        assertThrows(IllegalStateException.class, () -> context.get(First.class));
    }
}
