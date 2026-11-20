package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.*;
import io.github.leohou0430.llmgw.control.local.RouteVersionService;
import io.github.leohou0430.llmgw.core.routing.Route;
import io.github.leohou0430.llmgw.persistence.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VersionedRouteConfigSourceTest {

	@TempDir
	Path directory;

	private static VersionedRouteConfigSource source() {
		return new VersionedRouteConfigSource(
				List.of(new Route("initial", "*", "", "mock://initial", "", 1, List.of())),
				(t, p, id) -> "resolved-secret", true);
	}

	private static String config(String id) {
		return "{\"routes\":[{\"id\":\"" + id + "\",\"targetUrl\":\"mock://" + id + "\"}]}";
	}

	@Test
	void validatesActualConfigAndResolvesOnlySecretReferences() {
		var source = source();
		assertThrows(IllegalArgumentException.class, () -> source.validate("not JSON"));
		assertThrows(IllegalArgumentException.class, () -> source
			.validate("{\"routes\":[{\"id\":\"a\",\"targetUrl\":\"mock://a\",\"apiKey\":\"plaintext\"}]}"));
		source.activate(new RouteVersion(1, config("next"), 0));
		assertEquals("mock://next", source.current().routes().getFirst().targetUrl());
		assertEquals(1, source.current().version());
	}

	@Test
	void productionRejectsLocalTargetsAndSecretReferencesRemainOutOfPersistedConfig() {
		var strict = new VersionedRouteConfigSource(source().current().routes(), (t, p, id) -> "resolved-secret",
				false);
		assertThrows(IllegalArgumentException.class, () -> strict.validate(config("mock")));
		assertThrows(IllegalArgumentException.class, () -> strict.validate(
				"{\"routes\":[{\"id\":\"local\",\"targetUrl\":\"https://127.0.0.1\",\"secretId\":\"s\",\"tenantId\":\"t\",\"projectId\":\"p\"}]}"));
		var source = source();
		String referenced = "{\"routes\":[{\"id\":\"ref\",\"targetUrl\":\"mock://ref\",\"secretId\":\"s\",\"tenantId\":\"t\",\"projectId\":\"p\"}]}";
		source.activate(new RouteVersion(1, referenced, 0));
		assertEquals("resolved-secret", source.current().routes().getFirst().apiKey());
		assertFalse(referenced.contains("resolved-secret"));
	}

	@Test
	void committedRouteChangesActualSourceAndRecoversWhileAuditFailureDoesNotActivate() throws Exception {
		Path path = directory.resolve("routes.db");
		try (var db = new SqliteContext(path, 1000)) {
			new SchemaMigrator(db).migrate(Migrations.all());
			var source = source();
			var store = new SqliteRouteVersionStore(db);
			var service = new RouteVersionService(store, source::validate, source::activate);
			assertThrows(IllegalStateException.class, () -> db.transaction(c -> {
				service.publish(config("aborted"), 0);
				throw new IllegalStateException("audit unavailable");
			}));
			assertEquals(0, service.activeVersion());
			assertEquals("initial", source.current().routes().getFirst().id());
			assertTrue(store.latest().isEmpty());
			db.transaction(c -> {
				service.publish(config("published"), 0);
				assertEquals(0, source.current().version());
				return null;
			});
			assertEquals("published", source.current().routes().getFirst().id());
			assertThrows(VersionConflictException.class,
					() -> store.append(new RouteVersion(1, config("overwrite"), 0)));
		}
		try (var db = new SqliteContext(path, 1000)) {
			var source = source();
			var service = new RouteVersionService(new SqliteRouteVersionStore(db), source::validate, source::activate);
			assertEquals(1, service.activeVersion());
			assertEquals("published", source.current().routes().getFirst().id());
		}
	}

}
