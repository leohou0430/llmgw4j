package io.github.leohou0430.llmgw.engine.webflux;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

/** Socket prefetch counts buffers, so bound their bytes rather than forwarding whole SSE lines. */
final class SseResponseBuffers {
    private SseResponseBuffers() {}

    static Flux<DataBuffer> encode(Flux<String> lines, DataBufferFactory factory) {
        return lines.concatMapIterable(line -> {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            return (Iterable<DataBuffer>) () -> new Iterator<>() {
                private int offset;
                public boolean hasNext() { return offset < bytes.length; }
                public DataBuffer next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    int size = Math.min(8192, bytes.length - offset);
                    DataBuffer part = factory.wrap(ByteBuffer.wrap(bytes, offset, size));
                    offset += size;
                    return part;
                }
            };
        }, 1).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }
}
