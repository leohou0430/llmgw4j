package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.core.upstream.ByteChunk;
import io.github.leohou0430.llmgw.core.upstream.ByteStream;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2.4 adapter seam: {@link DataBufferBodySource} bridges a reactive
 * {@code Flux<DataBuffer>} to the pull {@link ByteStream.Source} contract with
 * bounded demand (never retains the whole body), propagates errors, reaches EOF,
 * and cancels the upstream subscription on close (idempotent).
 */
class DataBufferBodySourceTest {

    private static final NettyDataBufferFactory ALLOC = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);

    private static DataBuffer buffer(String s) {
        return ALLOC.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String drain(ByteStream.Source source) throws Exception {
        StringBuilder out = new StringBuilder();
        ByteChunk chunk;
        while ((chunk = source.next()) != null) {
            var buffer = chunk.transfer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            out.append(new String(bytes, StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    @Test
    void drainsAllChunksInOrderAndReachesEof() throws Exception {
        var source = new DataBufferBodySource(Flux.just(buffer("alpha"), buffer("beta"), buffer("gamma")), 2);
        assertEquals("alphabetagamma", drain(source));
        assertNull(source.next(), "EOF is sticky");
        source.close();
    }

    @Test
    void emptyFluxReturnsEofImmediately() throws Exception {
        var source = new DataBufferBodySource(Flux.empty(), 2);
        assertNull(source.next());
        source.close();
    }

    @Test
    void errorPropagatesAsIoException() {
        var source = new DataBufferBodySource(Flux.error(new IllegalStateException("client gone")), 2);
        assertThrows(IOException.class, source::next);
        source.close();
    }

    @Test
    void closeCancelsSubscriptionAndIsIdempotent() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        var source = new DataBufferBodySource(
                Flux.<DataBuffer>never().doOnCancel(() -> cancelled.set(true)), 2);
        source.close();
        source.close();
        assertTrue(cancelled.get(), "close must cancel the upstream subscription");
        assertNull(source.next(), "next after close returns EOF without blocking");
    }

    @Test
    void demandIsBoundedByQueueCapacity() throws Exception {
        AtomicLong requested = new AtomicLong();
        long consumed = 0;
        int capacity = 2;
        var source = new DataBufferBodySource(
                Flux.range(0, 40).map(i -> buffer("chunk-" + i))
                        .doOnRequest(requested::addAndGet), capacity);
        ByteChunk chunk;
        while ((chunk = source.next()) != null) {
            consumed++;
            assertTrue(requested.get() - consumed <= capacity + 2,
                    "outstanding demand must stay bounded, was " + (requested.get() - consumed));
        }
        assertEquals(40, consumed);
        source.close();
    }

    @Test
    void releasesEachClientBufferAfterCopying() throws Exception {
        List<DataBuffer> buffers = new ArrayList<>();
        Flux<DataBuffer> body = Flux.range(0, 10).map(i -> {
            DataBuffer b = buffer("data-" + i);
            buffers.add(b);
            return b;
        });
        var source = new DataBufferBodySource(body, 2);
        assertNotNull(drain(source));
        source.close();
        for (DataBuffer b : buffers) {
            assertEquals(0, ((org.springframework.core.io.buffer.NettyDataBuffer) b).getNativeBuffer().refCnt(),
                    "every client buffer must be released");
        }
    }
}