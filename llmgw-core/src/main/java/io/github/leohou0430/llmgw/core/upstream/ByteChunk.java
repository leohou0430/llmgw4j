package io.github.leohou0430.llmgw.core.upstream;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Ownership envelope for one byte chunk (T2.1). The buffer is handed out exactly
 * once via {@link #transfer()}; afterwards this envelope is spent and the original
 * reference can no longer read or re-transfer the bytes. This makes the
 * source&rarr;stream&rarr;subscriber ownership move explicit so engine bridges never
 * touch a buffer they have already released (e.g. pooled DataBuffer memory).
 */
public final class ByteChunk {

    private ByteBuffer buffer;

    public ByteChunk(ByteBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    /** Move ownership of the buffer out. Throws if already transferred. */
    public ByteBuffer transfer() {
        ByteBuffer b = buffer;
        if (b == null) {
            throw new IllegalStateException("byte chunk already transferred");
        }
        buffer = null;
        return b;
    }

    public boolean transferred() {
        return buffer == null;
    }
}