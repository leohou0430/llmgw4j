import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Network-free A/B for the two response-buffer strategies in the WebFlux
 * controller. Measures ns/op AND allocated bytes/op (ThreadMXBean), with
 * ABBA-interleaved rounds so JIT warm-up and host load cannot favour one arm.
 *
 * wrap(byte[])   -> Unpooled HEAP ByteBuf; a NIO socket write must copy a heap
 *                   ByteBuffer into a temporary direct buffer before the syscall.
 * allocateBuffer -> the channel's PooledByteBufAllocator (direct by default),
 *                   already writable to the socket with no conversion copy.
 *
 * The conversion is simulated explicitly because this benchmark has no socket.
 * It is therefore an UPPER BOUND on the end-to-end win, never an over-claim.
 */
public class BufferMicrobench {

    private static final com.sun.management.ThreadMXBean BEAN =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;

    public static void main(String[] args) {
        if (!BEAN.isThreadAllocatedMemoryEnabled()) {
            BEAN.setThreadAllocatedMemoryEnabled(true);
        }
        int iterations = Integer.parseInt(args[0]);
        NettyDataBufferFactory factory =
                new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

        String[] payloads = {
                "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok7\"}}]}\n",
                "{\"id\":\"mock\",\"object\":\"chat.completion\",\"created\":1,"
                        + "\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"answer\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,"
                        + "\"completion_tokens\":8,\"total_tokens\":20}}"
        };

        // Warm both paths and both JIT tiers before measuring anything.
        for (int i = 0; i < iterations; i++) {
            for (String p : payloads) {
                wrapPath(factory, p);
                directPath(factory, p);
            }
        }

        System.out.println("payload_bytes,wrap_ns,direct_ns,speedup,"
                + "wrap_alloc_bytes,direct_alloc_bytes,alloc_reduction_pct");

        for (String payload : payloads) {
            int size = payload.getBytes(StandardCharsets.UTF_8).length;
            List<Double> wn = new ArrayList<>(), dn = new ArrayList<>();
            List<Double> wb = new ArrayList<>(), db = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                boolean wrapFirst = round % 2 == 0;
                for (int pass = 0; pass < 2; pass++) {
                    boolean doWrap = (pass == 0) == wrapFirst;
                    Measurement m = measure(iterations, n -> {
                        for (int i = 0; i < n; i++) {
                            if (doWrap) {
                                wrapPath(factory, payload);
                            } else {
                                directPath(factory, payload);
                            }
                        }
                    });
                    if (doWrap) {
                        wn.add(m.nsPerOp());
                        wb.add(m.bytesPerOp());
                    } else {
                        dn.add(m.nsPerOp());
                        db.add(m.bytesPerOp());
                    }
                }
            }
            double wns = median(wn), dns = median(dn);
            double wby = median(wb), dby = median(db);
            System.out.printf("%d,%.1f,%.1f,%.2fx,%.0f,%.0f,%.1f%%%n",
                    size, wns, dns, wns / dns, wby, dby, (1 - dby / wby) * 100);
        }
        if (sink == 0) {
            System.out.println("sink was zero");
        }
    }

    /** Old path: wrap into an Unpooled heap ByteBuf, then the NIO write copies it. */
    private static void wrapPath(NettyDataBufferFactory factory, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = factory.wrap(bytes);
        ByteBuf heap = ((NettyDataBuffer) buffer).getNativeBuffer();
        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(heap.readableBytes());
        direct.writeBytes(heap);
        sink += direct.readableBytes();
        direct.release();
        DataBufferUtils.release(buffer);
    }

    /** New path: pooled direct buffer, writable to the socket as-is. */
    private static void directPath(NettyDataBufferFactory factory, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = factory.allocateBuffer(bytes.length).write(bytes);
        sink += ((NettyDataBuffer) buffer).getNativeBuffer().readableBytes();
        DataBufferUtils.release(buffer);
    }

    private record Measurement(double nsPerOp, double bytesPerOp) {
    }

    private static Measurement measure(int iterations, LongConsumer body) {
        long tid = Thread.currentThread().threadId();
        long memBefore = BEAN.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        body.accept(iterations);
        long elapsed = System.nanoTime() - t0;
        long allocated = BEAN.getThreadAllocatedBytes(tid) - memBefore;
        return new Measurement(elapsed / (double) iterations,
                allocated / (double) iterations);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        return n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);
    }
}
