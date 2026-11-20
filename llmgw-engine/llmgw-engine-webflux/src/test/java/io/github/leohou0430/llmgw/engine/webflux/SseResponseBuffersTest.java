package io.github.leohou0430.llmgw.engine.webflux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.*;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;

class SseResponseBuffersTest {

	@SuppressWarnings("unchecked")
	private Flux<DataBuffer> encode(Flux<String> source) throws Exception {
		var type = Class.forName("io.github.leohou0430.llmgw.engine.webflux.SseResponseBuffers");
		var method = type.getDeclaredMethod("encode", Flux.class, DataBufferFactory.class);
		method.setAccessible(true);
		return (Flux<DataBuffer>) method.invoke(null, source, DefaultDataBufferFactory.sharedInstance);
	}

	@Test
	void slowWriterCannotPrefetchHundredsOfLargeLines() throws Exception {
		var produced = new AtomicInteger();
		var held = new ArrayList<DataBuffer>();
		String line = "data: " + "x".repeat(1048448) + "\n\n";
		// HTTP Flow is not a synchronously fused, infinite pull queue.
		Flux<String> source = Flux.range(0, 1024).map(i -> {
			produced.incrementAndGet();
			return line;
		}).hide();
		var writer = new BaseSubscriber<DataBuffer>() {
			protected void hookOnSubscribe(Subscription s) {
				request(128);
			}

			protected void hookOnNext(DataBuffer b) {
				held.add(b);
			}
		};
		encode(source).subscribe(writer);
		try {
			assertEquals(128, held.size());
			assertTrue(held.stream().allMatch(b -> b.readableByteCount() <= 8192));
			assertTrue(produced.get() <= 3, "socket item demand must not become demand for 128 MiB lines: " + produced);
		}
		finally {
			writer.cancel();
			held.forEach(DataBufferUtils::release);
		}
	}

	@Test
	void chunkingPreservesExactUtf8WireBytesAndLineOrder() throws Exception {
		String text = "data: " + "中文🙂".repeat(15000) + "\r\n\r\ndata: [DONE]\n\n";
		var bytes = new ByteArrayOutputStream();
		encode(Flux.just(text.substring(0, text.length() - 1), "\n")).doOnNext(buffer -> {
			byte[] part = new byte[buffer.readableByteCount()];
			buffer.read(part);
			bytes.writeBytes(part);
			DataBufferUtils.release(buffer);
		}).blockLast();
		assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), bytes.toByteArray());
	}

}
