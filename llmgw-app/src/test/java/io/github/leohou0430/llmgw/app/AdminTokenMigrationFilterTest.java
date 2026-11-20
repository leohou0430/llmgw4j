package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.AdminTokenMode;
import io.github.leohou0430.llmgw.control.local.DefaultAdminTokenPolicy;
import io.github.leohou0430.llmgw.control.local.RestrictedNetworks;
import io.github.leohou0430.llmgw.persistence.sqlite.Migrations;
import io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTokenMigrationFilterTest {

	@TempDir
	Path tempDir;

	private SqliteContext database;

	private AuditLog audit;

	private ObjectProvider<AuditLog> auditProvider;

	@BeforeEach
	void setUp() throws Exception {
		database = new SqliteContext(tempDir.resolve("audit.db"), 1000L);
		new SchemaMigrator(database).migrate(Migrations.all());
		audit = new AuditLog(database);
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("audit", audit);
		auditProvider = beans.getBeanProvider(AuditLog.class);
	}

	@AfterEach
	void tearDown() {
		database.close();
	}

	private AdminTokenMigrationConfiguration.ServletAdminTokenFilter servlet(AdminTokenMode mode, String networks,
			boolean configured) {
		AdminTokenGuard guard = new AdminTokenGuard(new DefaultAdminTokenPolicy(), mode,
				new RestrictedNetworks(networks), configured);
		return new AdminTokenMigrationConfiguration.ServletAdminTokenFilter(guard, auditProvider);
	}

	private AdminTokenMigrationConfiguration.ReactiveAdminTokenFilter reactive(AdminTokenMode mode, String networks,
			boolean configured) {
		AdminTokenGuard guard = new AdminTokenGuard(new DefaultAdminTokenPolicy(), mode,
				new RestrictedNetworks(networks), configured);
		return new AdminTokenMigrationConfiguration.ReactiveAdminTokenFilter(guard, auditProvider);
	}

	@Test
	void servletDisabledRejectsLegacyTokenEvenFromLoopback() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/routes");
		request.setRemoteAddr("127.0.0.1");
		request.addHeader("X-Admin-Token", "legacy");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		servlet(AdminTokenMode.DISABLED, "", true).doFilter(request, response, chain);

		assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
		assertNull(chain.getRequest());
	}

	@Test
	void servletMigrationAllowsLoopbackAndAuditsEmergency() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/routes");
		request.setRemoteAddr("127.0.0.1");
		request.addHeader("X-Admin-Token", "legacy");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		servlet(AdminTokenMode.MIGRATION, "", true).doFilter(request, response, chain);

		assertNotNull(chain.getRequest());
		assertTrue(
				audit.recent("platform", 10).stream().anyMatch(entry -> entry.action().equals("admin.legacy-token")));
	}

	@Test
	void servletMigrationRejectsPublicClient() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/routes");
		request.setRemoteAddr("203.0.113.5");
		request.addHeader("X-Admin-Token", "legacy");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		servlet(AdminTokenMode.MIGRATION, "", true).doFilter(request, response, chain);

		assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
		assertNull(chain.getRequest());
	}

	@Test
	void servletLeavesControlPlaneAndTokenlessCallsAlone() throws Exception {
		MockHttpServletRequest control = new MockHttpServletRequest("GET", "/control/status");
		control.setRemoteAddr("203.0.113.5");
		control.addHeader("X-Admin-Token", "legacy");
		MockFilterChain controlChain = new MockFilterChain();
		servlet(AdminTokenMode.DISABLED, "", true).doFilter(control, new MockHttpServletResponse(), controlChain);
		assertNotNull(controlChain.getRequest());

		MockHttpServletRequest tokenless = new MockHttpServletRequest("GET", "/admin/routes");
		tokenless.setRemoteAddr("203.0.113.5");
		MockFilterChain tokenlessChain = new MockFilterChain();
		servlet(AdminTokenMode.DISABLED, "", true).doFilter(tokenless, new MockHttpServletResponse(), tokenlessChain);
		assertNotNull(tokenlessChain.getRequest());
	}

	@Test
	void reactiveDisabledRejectsAndMigrationAuditsLoopback() {
		MockServerWebExchange denied = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/routes")
			.header("X-Admin-Token", "legacy")
			.remoteAddress(new InetSocketAddress("127.0.0.1", 5555)));
		AtomicBoolean deniedChain = new AtomicBoolean();
		reactive(AdminTokenMode.DISABLED, "", true).filter(denied, exchange -> {
			deniedChain.set(true);
			return Mono.empty();
		}).block();

		assertEquals(HttpStatus.FORBIDDEN, denied.getResponse().getStatusCode());
		assertTrue(!deniedChain.get());

		MockServerWebExchange allowed = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/routes")
			.header("X-Admin-Token", "legacy")
			.remoteAddress(new InetSocketAddress("127.0.0.1", 5555)));
		AtomicBoolean allowedChain = new AtomicBoolean();
		reactive(AdminTokenMode.MIGRATION, "", true).filter(allowed, exchange -> {
			allowedChain.set(true);
			return Mono.empty();
		}).block();

		assertTrue(allowedChain.get());
		assertTrue(
				audit.recent("platform", 10).stream().anyMatch(entry -> entry.action().equals("admin.legacy-token")));
	}

}
