package io.github.leohou0430.llmgw.core.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionsTest {
    @ExtensionPoint("first") public interface Strategy {}
    public static class First implements Strategy {
        static final AtomicInteger calls = new AtomicInteger();
        public First() { calls.incrementAndGet(); }
    }
    public static class Broken implements Strategy {
        public Broken() { throw new IllegalArgumentException("constructor failed"); }
    }
    public static class NoPublicConstructor implements Strategy { private NoPublicConstructor() {} }
    @TempDir Path directory;

    private URLClassLoader loader(String folder, String mapping) throws Exception {
        var root = directory.resolve(folder);
        var resource = root.resolve(Extensions.DIRECTORY + Strategy.class.getName());
        Files.createDirectories(resource.getParent());Files.writeString(resource, mapping);
        return new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, getClass().getClassLoader());
    }
    @Test void discoveryIsLazyAndAliasesShareAnInstanceOnlyWithinTheirCatalog() throws Exception {
        try (var loader = loader("one", "# comment\nfirst,alias="+First.class.getName()+"\n")) {
            int before=First.calls.get();
            var a=Extensions.load(Strategy.class,loader);var b=Extensions.load(Strategy.class,loader);
            assertEquals(java.util.Set.of("first","alias"),a.getSupportedExtensions());
            assertEquals(before,First.calls.get());
            assertSame(a.getDefaultExtension(),a.getExtension("alias"));
            assertNotSame(a.getDefaultExtension(),b.getDefaultExtension());
            assertEquals(before+2,First.calls.get());
        }
    }
    @Test void concurrentLookupsConstructExactlyOnce() throws Exception {
        try (var loader=loader("one","first="+First.class.getName());var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var extensions=Extensions.load(Strategy.class,loader);int before=First.calls.get();
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<Strategy>>();
            for(int i=0;i<40;i++)tasks.add(pool.submit(()->extensions.getExtension("first")));
            var expected=tasks.getFirst().get();
            for(var task:tasks)assertSame(expected,task.get());
            assertEquals(before+1,First.calls.get());
        }
    }
    @Test void duplicateNamesAcrossResourcesNeverDependOnClasspathOrder() throws Exception {
        try(var a=loader("a","first="+First.class.getName());var b=loader("b","first="+First.class.getName());
            var combined=new URLClassLoader(new java.net.URL[]{a.getURLs()[0],b.getURLs()[0]},getClass().getClassLoader())) {
            var failure=assertThrows(IllegalStateException.class,()->Extensions.load(Strategy.class,combined));
            assertTrue(failure.getMessage().contains("duplicate"));
        }
    }
    @Test void malformedAndUnknownNamesFailClearly() throws Exception {
        try(var bad=loader("bad","first,"+"="+First.class.getName())) {
            assertThrows(IllegalStateException.class,()->Extensions.load(Strategy.class,bad));
        }
        try(var good=loader("good","first="+First.class.getName())) {
            var extensions=Extensions.load(Strategy.class,good);
            assertThrows(IllegalStateException.class,()->extensions.getExtension("missing"));
            assertThrows(IllegalStateException.class,()->extensions.getExtension(" "));
        }
    }
    @Test void invalidImplementationsAndConstructorCausesRemainVisible() throws Exception {
        for(var implementation:new Class<?>[]{String.class,NoPublicConstructor.class,Broken.class}) {
            try(var loader=loader(implementation.getSimpleName(),"first="+implementation.getName())) {
                var extensions=Extensions.load(Strategy.class,loader);
                var failure=assertThrows(IllegalStateException.class,()->extensions.getDefaultExtension());
                assertTrue(failure.getMessage().contains(implementation.getName()));
                if(implementation==Broken.class)assertInstanceOf(IllegalArgumentException.class,failure.getCause());
            }
        }
    }
    @Test void classLoaderMetadataAndDefaultNamesAreIsolated() throws Exception {
        try(var a=loader("a","first="+First.class.getName());var b=loader("b","other="+Broken.class.getName())) {
            assertTrue(Extensions.load(Strategy.class,a).hasExtension("first"));
            var other=Extensions.load(Strategy.class,b);
            assertFalse(other.hasExtension("first"));
            assertThrows(IllegalStateException.class,other::getDefaultExtension);
        }
    }
}
