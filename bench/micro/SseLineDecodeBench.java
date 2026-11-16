import java.lang.management.ManagementFactory;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;

/** Local decoder CPU scaling probe, not a gateway throughput benchmark. */
class SseLineDecodeBench {
    static void decode(byte[] input, String decoder) throws Exception {
        int[] count = {0};
        var lines = new Flow.Subscriber<String>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(String line) { if (line.length() != input.length - 1) throw new AssertionError(); count[0]++; }
            public void onError(Throwable error) { throw new AssertionError(error); }
            public void onComplete() {}
        };
        HttpResponse.BodySubscriber<?> body;
        if (decoder.equals("jdk")) body = HttpResponse.BodySubscribers.fromLineSubscriber(lines);
        else {
            var constructor = Class.forName(decoder).getDeclaredConstructor(Flow.Subscriber.class);
            constructor.setAccessible(true);
            body = (HttpResponse.BodySubscriber<?>) constructor.newInstance(lines);
        }
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() {} });
        for (int offset = 0; offset < input.length; offset += 8192)
            body.onNext(List.of(ByteBuffer.wrap(input, offset, Math.min(8192, input.length - offset))));
        body.onComplete(); body.getBody().toCompletableFuture().join();
        if (count[0] != 1) throw new AssertionError("line missing or duplicated");
    }
    static byte[] input(int length) {
        byte[] bytes = new byte[length + 1]; Arrays.fill(bytes, (byte) 'x'); bytes[length] = '\n'; return bytes;
    }
    public static void main(String[] args) throws Exception {
        String decoder = args.length == 0 ? "jdk" : args[0];
        var cpu = ManagementFactory.getThreadMXBean();
        byte[] warm = input(16384);
        for (int i = 0; i < 500; i++) decode(warm, decoder);
        for (int round = 0; round < 3; round++) {
            var sizes = new ArrayList<>(List.of(16384, 65536, 262144, 1048448));
            if (round % 2 != 0) Collections.reverse(sizes);
            for (int size : sizes) {
                byte[] bytes = input(size); int iterations = 10;
                long startCpu = cpu.getCurrentThreadCpuTime(), start = System.nanoTime();
                for (int i = 0; i < iterations; i++) decode(bytes, decoder);
                System.out.printf(Locale.ROOT, "{\"decoder\":\"%s\",\"round\":%d,\"line_bytes\":%d,\"iterations\":%d,\"cpu_ns_per_line\":%.1f,\"wall_ns_per_line\":%.1f}%n",
                        decoder, round, size, iterations, (cpu.getCurrentThreadCpuTime() - startCpu) / (double) iterations,
                        (System.nanoTime() - start) / (double) iterations);
            }
        }
    }
}
