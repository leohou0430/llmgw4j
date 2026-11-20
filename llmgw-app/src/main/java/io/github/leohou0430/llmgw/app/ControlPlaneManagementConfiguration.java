package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.identity.*;
import io.github.leohou0430.llmgw.identity.local.*;
import io.github.leohou0430.llmgw.persistence.sqlite.*;
import io.github.leohou0430.llmgw.control.*;
import io.github.leohou0430.llmgw.control.local.*;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP bridge exporting only the already composed native control/data graph. */
@Configuration(proxyBeanMethods = false)
public class ControlPlaneManagementConfiguration {

	@Bean(destroyMethod = "")
	AccountStore accountStore(ExtensionContext context) {
		return context.get(AccountStore.class);
	}

	@Bean(destroyMethod = "")
	PasswordHasher passwordHasher(ExtensionContext context) {
		return context.get(PasswordHasher.class);
	}

	@Bean(destroyMethod = "")
	SessionStore sessionStore(ExtensionContext context) {
		return context.get(SessionStore.class);
	}

	@Bean(destroyMethod = "")
	CsrfTokenService csrfTokenService(ExtensionContext context) {
		return context.get(CsrfTokenService.class);
	}

	@Bean(destroyMethod = "")
	AuthorizationService authorizationService(ExtensionContext context) {
		return context.get(AuthorizationService.class);
	}

	@Bean(destroyMethod = "")
	SessionService sessionService(ExtensionContext context) {
		return context.get(SessionService.class);
	}

	@Bean(destroyMethod = "")
	ManagedKeyService managedKeyService(ExtensionContext context) {
		return context.get(ManagedKeyService.class);
	}

	@Bean(destroyMethod = "")
	RequestAuthorizer requestAuthorizer(ExtensionContext context) {
		return context.get(RequestAuthorizer.class);
	}

	@Bean(destroyMethod = "")
	SqliteContext sqliteContext(ExtensionContext context) {
		return context.get(SqliteContext.class);
	}

	@Bean(destroyMethod = "")
	RouteVersionService routeVersionService(ExtensionContext context) {
		return context.get(RouteVersionService.class);
	}

	@Bean(destroyMethod = "")
	DefaultConfigValidator defaultConfigValidator(ExtensionContext context) {
		return context.get(DefaultConfigValidator.class);
	}

	@Bean(destroyMethod = "")
	PlatformPolicy platformPolicy(ExtensionContext context) {
		return context.get(PlatformPolicy.class);
	}

	@Bean(destroyMethod = "")
	RouteCandidateValidator routeCandidateValidator(ExtensionContext context) {
		return context.get(RouteCandidateValidator.class);
	}

	@Bean(destroyMethod = "")
	io.github.leohou0430.llmgw.persistence.PayloadRecordStore payloadRecordStore(ExtensionContext context) {
		return context.get(io.github.leohou0430.llmgw.persistence.PayloadRecordStore.class);
	}

	@Bean(destroyMethod = "")
	SecretCipher secretCipher(ExtensionContext context) {
		return context.get(SecretCipher.class);
	}

	@Bean(destroyMethod = "")
	StorageGuard storageGuard(ExtensionContext context) {
		return context.get(StorageGuard.class);
	}

	@Bean(destroyMethod = "")
	SecretStore secretStore(ExtensionContext context) {
		return context.get(SecretStore.class);
	}

	@Bean(destroyMethod = "")
	AuditLog auditLog(ExtensionContext context) {
		return context.get(AuditLog.class);
	}

	@Bean(destroyMethod = "")
	OrgStore orgStore(ExtensionContext context) {
		return context.get(OrgStore.class);
	}

	@Bean(destroyMethod = "")
	ResourceCatalog resourceCatalog(ExtensionContext context) {
		return context.get(ResourceCatalog.class);
	}

	@Bean(destroyMethod = "")
	SqliteUsageLedger sqliteUsageLedger(ExtensionContext context) {
		return context.get(SqliteUsageLedger.class);
	}

	@Bean
	ApplicationRunner bootstrapFirstAdministrator(AccountStore accounts, PasswordHasher hasher) {
		return args -> {
			String username = System.getenv("LLMGW_ADMIN_USERNAME");
			String password = System.getenv("LLMGW_ADMIN_PASSWORD");
			if (username != null && !username.isBlank() && password != null && !password.isBlank()
					&& !accounts.existsAny()) {
				new AdministratorBootstrap(accounts, hasher).bootstrap(username, password);
			}
			// Optional project viewer (for role-based acceptance); disabled unless
			// env-provided.
			String viewer = System.getenv("LLMGW_VIEWER_USERNAME");
			String viewerPassword = System.getenv("LLMGW_VIEWER_PASSWORD");
			if (viewer != null && !viewer.isBlank() && viewerPassword != null && !viewerPassword.isBlank()
					&& accounts.findByUsername(viewer).isEmpty()) {
				accounts.create(new io.github.leohou0430.llmgw.identity.Account(java.util.UUID.randomUUID().toString(),
						viewer, hasher.hash(viewerPassword), io.github.leohou0430.llmgw.identity.Role.PROJECT_VIEWER,
						io.github.leohou0430.llmgw.identity.Scope.project("t1", "p1"), true));
			}
		};
	}

}
