package io.github.leohou0430.llmgw.provider.http.jdk;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SseLineLimitSubscriberTest {

	@Test
	void errorAndCancellationDoNotFlushAPendingCompletionLine() throws Exception {
		for (boolean cancel : List.of(false, true)) {
			var probe = new JdkFlowTransportTest.Probe(1) {
				@Override
				public void onComplete() {
					ended.complete(null);
				}
			};
			var guard = new SseLineLimitSubscriber<>(BodySubscribers.fromLineSubscriber(probe), 64);
			var transport = new Subscription();
			guard.onSubscribe(transport);
			guard.onNext(List.of(ByteBuffer.wrap("data: one\ndata: [DONE]".getBytes(StandardCharsets.UTF_8))));
			if (cancel) {
				probe.subscription.cancel();
				guard.onComplete();
				assertFalse(probe.ended.isDone());
				assertEquals(1, transport.cancels);
			}
			else {
				var failure = new java.io.IOException("disconnected");
				guard.onError(failure);
				guard.onComplete();
				var error = assertThrows(java.util.concurrent.ExecutionException.class,
						() -> probe.ended.get(1, TimeUnit.SECONDS));
				assertSame(failure, error.getCause());
			}
			assertEquals(List.of("data: one"), List.copyOf(probe.values));
		}
	}

	@Test
	void finiteLineDemandRetainsAnUnterminatedCompletionFrame() throws Exception {
		for (String ending : List.of("", "\r", "\n", "\r\n")) {
			var probe = new JdkFlowTransportTest.Probe(1) {
				@Override
				public void onComplete() {
					ended.complete(null);
				}
			};
			var guard = new SseLineLimitSubscriber<>(BodySubscribers.fromLineSubscriber(probe), 64);
			var transport = new Subscription();
			guard.onSubscribe(transport);
			guard.onNext(List
				.of(ByteBuffer.wrap(("data: one\ndata: two\ndata: [DONE]" + ending).getBytes(StandardCharsets.UTF_8))));
			guard.onComplete();
			probe.subscription.request(1);
			probe.subscription.request(1);
			probe.ended.get(1, TimeUnit.SECONDS);
			assertEquals(List.of("data: one", "data: two", "data: [DONE]"), List.copyOf(probe.values),
					"EOF with ending " + ending.replace("\r", "CR").replace("\n", "LF"));
			assertEquals(0, transport.cancels, "normal EOF must not cancel HTTP transport");
		}
	}

	static class Subscription implements Flow.Subscription {

		int cancels;

		long requested;

		public void request(long n) {
			requested += n;
		}

		public void cancel() {
			cancels++;
		}

	}

	@Test
	void splitUtf8AndCrLfAndFinalUnterminatedLineKeepJdkSemantics() throws Exception {
		var probe = new JdkFlowTransportTest.Probe(Long.MAX_VALUE) {
			@Override
			public void onComplete() {
				ended.complete(null);
			}
		};
		var guard = new SseLineLimitSubscriber<>(BodySubscribers.fromLineSubscriber(probe), 4);
		var transport = new Subscription();
		guard.onSubscribe(transport);
		for (byte b : "😀\r\n中\rx\n\nlast".getBytes(StandardCharsets.UTF_8)) {
			// Nonzero positions, read-only buffers and one-byte UTF-8 fragments.
			var buffer = ByteBuffer.wrap(new byte[] { 99, b, 99 }).asReadOnlyBuffer();
			buffer.position(1);
			buffer.limit(2);
			guard.onNext(List.of(buffer));
		}
		guard.onComplete();
		guard.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS);
		assertEquals(List.of("😀", "中", "x", "", "last"), List.copyOf(probe.values));
		assertEquals(0, transport.cancels, "normal completion must not cancel HTTP transport");
	}

	@Test
	void byteLimitIsNotACharacterLimitAndFailureIsTerminal() throws Exception {
		var errors = new AtomicInteger();
		var probe = new JdkFlowTransportTest.Probe(Long.MAX_VALUE) {
			@Override
			public void onError(Throwable error) {
				errors.incrementAndGet();
				super.onError(error);
			}
		};
		var guard = new SseLineLimitSubscriber<>(BodySubscribers.fromLineSubscriber(probe), 5);
		var transport = new Subscription();
		guard.onSubscribe(transport);
		byte[] input = "中中".getBytes(StandardCharsets.UTF_8);
		guard.onNext(List.of(ByteBuffer.wrap(input, 0, 5)));
		assertFalse(probe.ended.isDone());
		guard.onNext(List.of(ByteBuffer.wrap(input, 5, 1)));
		guard.onComplete();
		guard.onError(new IllegalStateException("late"));
		guard.onNext(List.of(ByteBuffer.wrap(new byte[] { '\n' })));
		assertThrows(java.util.concurrent.ExecutionException.class, () -> probe.ended.get(2, TimeUnit.SECONDS));
		assertEquals(1, transport.cancels);
		assertEquals(1, errors.get());
		assertTrue(probe.values.isEmpty());
	}

	@Test
	void aggregateStreamCanExceedNonStreamingLimitWithoutRetainingLines() throws Exception {
		var count = new AtomicInteger();
		var delegate = BodySubscribers.fromLineSubscriber(new Flow.Subscriber<String>() {
			public void onSubscribe(Flow.Subscription s) {
				s.request(Long.MAX_VALUE);
			}

			public void onNext(String line) {
				assertEquals(1024, line.length());
				count.incrementAndGet();
			}

			public void onError(Throwable error) {
				fail(error);
			}

			public void onComplete() {
			}
		});
		var guard = new SseLineLimitSubscriber<>(delegate, 1024);
		var transport = new Subscription();
		guard.onSubscribe(transport);
		byte[] input = ("x".repeat(1024) + "\n").getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < 8192; i++)
			guard.onNext(List.of(ByteBuffer.wrap(input)));
		guard.onComplete();
		guard.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS);
		assertEquals(8192, count.get());
		assertEquals(0, transport.cancels);
	}

	@Test
	void delegatesDemandAndCancellationWithoutRequestingExtraBuffers() throws Exception {
		var probe = new JdkFlowTransportTest.Probe(0);
		var guard = new SseLineLimitSubscriber<>(BodySubscribers.fromLineSubscriber(probe), 8);
		var transport = new Subscription();
		guard.onSubscribe(transport);
		assertEquals(0, transport.requested);
		probe.subscription.request(1);
		assertTrue(transport.requested > 0);
		guard.onNext(List.of(ByteBuffer.wrap("a\nb\n".getBytes(StandardCharsets.UTF_8))));
		assertEquals("a", probe.values.poll(2, TimeUnit.SECONDS));
		assertTrue(probe.values.isEmpty());
		probe.subscription.request(1);
		assertEquals("b", probe.values.poll(2, TimeUnit.SECONDS));
		probe.subscription.cancel();
		assertEquals(1, transport.cancels);
	}

	@Test
	void requestLimitsAreIndependentAndInvalidConfigurationFails() {
		var properties = new GatewayProperties();
		assertEquals(1048576, properties.getUpstream().getMaxSseLineBytes());
		assertThrows(IllegalArgumentException.class, () -> properties.getUpstream().setMaxSseLineBytes(0));
		assertThrows(IllegalArgumentException.class, () -> properties.getUpstream().setMaxSseLineBytes(-1));
		properties.getUpstream().setMaxSseLineBytes(2);
		assertEquals(2, properties.getUpstream().getMaxSseLineBytes());
		var first = new SseLineLimitSubscriber<>(BodySubscribers.discarding(), 2);
		var second = new SseLineLimitSubscriber<>(BodySubscribers.discarding(), 3);
		var one = new Subscription();
		var two = new Subscription();
		first.onSubscribe(one);
		second.onSubscribe(two);
		first.onNext(List.of(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
		second.onNext(List.of(ByteBuffer.wrap(new byte[] { 1, 2, 3 })));
		assertEquals(1, one.cancels);
		assertEquals(0, two.cancels);
		second.onComplete();
	}

}
