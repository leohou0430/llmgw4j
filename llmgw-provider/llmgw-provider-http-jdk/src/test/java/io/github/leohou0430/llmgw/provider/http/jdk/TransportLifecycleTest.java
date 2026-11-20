package io.github.leohou0430.llmgw.provider.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportLifecycleTest {

	@Test
	void normalCompletionRemovesItsTimerBeforeCallingUserCode() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var workers = Executors.newVirtualThreadPerTaskExecutor();
				var client = new JdkHttpUpstream(new GatewayProperties())) {
			var source = new CompletableFuture<String>();
			var result = deadline(client, source, 4000);
			result.whenComplete((value, error) -> hold(entered, release));
			var completion = workers.submit(() -> source.complete("ok"));
			try {
				assertTrue(entered.await(1, TimeUnit.SECONDS));
				var field = JdkHttpUpstream.class.getDeclaredField("watchdog");
				field.setAccessible(true);
				assertTrue(((ScheduledThreadPoolExecutor) field.get(client)).getQueue().isEmpty());
			}
			finally {
				release.countDown();
				completion.get(2, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void abortAfterNormalHttpCompletionDoesNotCancelAReusedTransport() throws Exception {
		try (var client = new JdkHttpUpstream(new GatewayProperties())) {
			Class<?> type = Class.forName(JdkHttpUpstream.class.getName() + "$LineSubscription");
			var constructor = type.getDeclaredConstructor(JdkHttpUpstream.class, Flow.Subscriber.class,
					HeaderListener.class);
			constructor.setAccessible(true);
			var subscription = constructor.newInstance(client, new JdkFlowTransportTest.Probe(0),
					HeaderListener.none());
			var futureField = type.getDeclaredField("future");
			futureField.setAccessible(true);
			@SuppressWarnings("unchecked")
			var future = (java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>>) futureField
				.get(subscription);
			future.set(CompletableFuture.completedFuture(null));
			var demandField = type.getDeclaredField("demand");
			demandField.setAccessible(true);
			Object demand = demandField.get(subscription);
			var onSubscribe = demand.getClass().getDeclaredMethod("onSubscribe", Flow.Subscription.class);
			onSubscribe.setAccessible(true);
			var cancelled = new java.util.concurrent.atomic.AtomicInteger();
			onSubscribe.invoke(demand, new Flow.Subscription() {
				public void request(long n) {
				}

				public void cancel() {
					cancelled.incrementAndGet();
				}
			});
			var abort = type.getDeclaredMethod("abortTransport");
			abort.setAccessible(true);
			abort.invoke(subscription);
			assertEquals(0, cancelled.get(), "a delayed timeout must not cancel a normally completed body");
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> CompletableFuture<T> deadline(JdkHttpUpstream client, CompletableFuture<T> source, int ms)
			throws Exception {
		var method = JdkHttpUpstream.class.getDeclaredMethod("withDeadline", CompletableFuture.class, int.class);
		method.setAccessible(true);
		return (CompletableFuture<T>) method.invoke(client, source, ms);
	}

	private static void hold(CountDownLatch entered, CountDownLatch release) {
		entered.countDown();
		try {
			release.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static void assertDeadline(CompletableFuture<?> result) {
		var error = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
		assertInstanceOf(UpstreamTimeoutException.class, error.getCause());
	}

	@Test
	void cancellingTheMappedFutureAbortsBeforeUserCancellationCallbacks() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var workers = Executors.newVirtualThreadPerTaskExecutor();
				var client = new JdkHttpUpstream(new GatewayProperties())) {
			var source = new CompletableFuture<String>();
			var bounded = deadline(client, source, 4000);
			var method = JdkHttpUpstream.class.getDeclaredMethod("mapResponse", CompletableFuture.class,
					java.util.function.Function.class);
			method.setAccessible(true);
			var mapped = (CompletableFuture<?>) method.invoke(null, bounded, java.util.function.Function.identity());
			mapped.whenComplete((value, error) -> hold(entered, release));
			var cancelling = workers.submit(() -> mapped.cancel(true));
			try {
				assertTrue(entered.await(1, TimeUnit.SECONDS));
				assertTrue(source.isCancelled(), "all cancellation layers must abort before notifying the caller");
			}
			finally {
				release.countDown();
				cancelling.get(2, TimeUnit.SECONDS);
			}
			assertTrue(mapped.isCancelled());
		}
	}

	@Test
	void timeoutCallbackCanCloseItsOwnTransport() throws Exception {
		try (var fixture = new Fixture()) {
			var closed = new CompletableFuture<Void>();
			fixture.client.completeRawAsync(fixture.request(400)).whenComplete((value, error) -> {
				try {
					fixture.client.close();
					closed.complete(null);
				}
				catch (Throwable failure) {
					closed.completeExceptionally(failure);
				}
			});
			try {
				closed.get(1, TimeUnit.SECONDS);
			}
			finally {
				fixture.release.countDown();
				closed.get(3, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void normalCompletionCallbackCanCloseItsOwnTransport() throws Exception {
		runCloseProbe(NormalCloseProbe.class);
	}

	@Test
	void normalFlowCompletionCanCloseItsOwnTransport() throws Exception {
		runCloseProbe(FlowCloseProbe.class);
	}

	private static void runCloseProbe(Class<?> probe) throws Exception {
		String javaCommand = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
		var process = new ProcessBuilder(javaCommand, "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), probe.getName())
			.redirectErrorStream(true)
			.start();
		try {
			assertTrue(process.waitFor(8, TimeUnit.SECONDS), "reentrant close must not deadlock its own HTTP worker");
			assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor();
			}
		}
	}

	public static class FlowCloseProbe {

		public static void main(String[] args) {
			try {
				var fixture = new Fixture();
				var closed = new CompletableFuture<Void>();
				fixture.client.streamRaw(fixture.request(4000))
					.subscribe(new JdkFlowTransportTest.Probe(Long.MAX_VALUE) {
						@Override
						public void onComplete() {
							try {
								fixture.client.close();
								closed.complete(null);
							}
							catch (Throwable failure) {
								closed.completeExceptionally(failure);
							}
						}

						@Override
						public void onError(Throwable error) {
							closed.completeExceptionally(error);
						}
					});
				fixture.release.countDown();
				closed.get(2, TimeUnit.SECONDS);
				System.exit(0);
			}
			catch (Throwable error) {
				error.printStackTrace();
				System.exit(2);
			}
		}

	}

	public static class NormalCloseProbe {

		public static void main(String[] args) {
			try {
				var fixture = new Fixture();
				var closed = new CompletableFuture<Void>();
				fixture.client.completeRawAsync(fixture.request(4000)).whenComplete((value, error) -> {
					try {
						fixture.client.close();
						closed.complete(null);
					}
					catch (Throwable failure) {
						closed.completeExceptionally(failure);
					}
				});
				fixture.release.countDown();
				closed.get(2, TimeUnit.SECONDS);
				System.exit(0);
			}
			catch (Throwable error) {
				error.printStackTrace();
				System.exit(2);
			}
		}

	}

	@Test
	void blockedFutureCallbackCannotDelayCancellationOrOtherDeadlines() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var client = new JdkHttpUpstream(new GatewayProperties())) {
			var source = new CompletableFuture<String>();
			var first = deadline(client, source, 100);
			first.whenComplete((value, error) -> hold(entered, release));
			try {
				assertTrue(entered.await(2, TimeUnit.SECONDS));
				var second = deadline(client, new CompletableFuture<>(), 100);
				assertAll(() -> assertTrue(source.isCancelled(), "transport must cancel before publishing timeout"),
						() -> assertDeadline(second));
			}
			finally {
				release.countDown();
				source.cancel(true);
			}
		}
	}

	@Test
	void blockedFlowErrorCannotStallOtherDeadlines() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var fixture = new Fixture()) {
			var probe = new JdkFlowTransportTest.Probe(Long.MAX_VALUE) {
				@Override
				public void onError(Throwable error) {
					hold(entered, release);
					super.onError(error);
				}
			};
			fixture.client.streamRaw(fixture.request(400)).subscribe(probe);
			try {
				assertTrue(entered.await(2, TimeUnit.SECONDS));
				assertDeadline(deadline(fixture.client, new CompletableFuture<>(), 100));
			}
			finally {
				release.countDown();
				probe.subscription.cancel();
			}
		}
	}

	@Test
	void closeDoesNotWaitForABlockedTimeoutCallback() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var fixture = new Fixture()) {
			var result = fixture.client.completeRawAsync(fixture.request(400));
			result.whenComplete((value, error) -> hold(entered, release));
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			var closing = fixture.workers.submit(fixture.client::close);
			try {
				closing.get(1, TimeUnit.SECONDS);
			}
			finally {
				release.countDown();
				fixture.release.countDown();
				closing.get(3, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void explicitCancellationLetsGracefulCloseFinishWithoutClosingAnotherTransport() throws Exception {
		try (var fixture = new Fixture(); var other = new JdkHttpUpstream(new GatewayProperties())) {
			var result = fixture.client.completeRawAsync(fixture.request(4000));
			assertTrue(fixture.headers.await(2, TimeUnit.SECONDS));
			var closing = fixture.workers.submit(fixture.client::close);
			result.cancel(true);
			closing.get(1, TimeUnit.SECONDS);
			fixture.client.close();
			var field = JdkHttpUpstream.class.getDeclaredField("watchdog");
			field.setAccessible(true);
			assertTrue(((ScheduledThreadPoolExecutor) field.get(fixture.client)).isShutdown());
			assertFalse(((ScheduledThreadPoolExecutor) field.get(other)).isShutdown());
			for (String name : java.util.List.of("timeoutSignals", "ownedExecutor")) {
				var resource = JdkHttpUpstream.class.getDeclaredField(name);
				resource.setAccessible(true);
				var first = (ExecutorService) resource.get(fixture.client);
				var second = (ExecutorService) resource.get(other);
				assertTrue(first.isShutdown());
				if (second != null)
					assertFalse(second.isShutdown());
				assertTrue(first.awaitTermination(1, TimeUnit.SECONDS));
			}
		}
	}

	private static class Fixture implements AutoCloseable {

		final CountDownLatch release = new CountDownLatch(1), headers = new CountDownLatch(1);

		final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

		final JdkHttpUpstream client = new JdkHttpUpstream(properties());

		final HttpServer server;

		static GatewayProperties properties() {
			var properties = new GatewayProperties();
			properties.getUpstream().setExecutorThreads(2);
			return properties;
		}

		Fixture() throws Exception {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(workers);
			server.createContext("/", exchange -> {
				exchange.sendResponseHeaders(200, 0);
				try (var out = exchange.getResponseBody()) {
					out.write(':');
					out.flush();
					headers.countDown();
					release.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				catch (java.io.IOException cancelled) {
					/* Transport was cancelled by the deadline. */ }
				finally {
					exchange.close();
				}
			});
			server.start();
		}

		UpstreamRequest request(int ms) {
			return new UpstreamRequest("{}", "http://127.0.0.1:" + server.getAddress().getPort(), "", "m", true, ms,
					"/", UpstreamMethod.POST);
		}

		public void close() {
			release.countDown();
			server.stop(0);
			client.close();
			workers.close();
		}

	}

}
