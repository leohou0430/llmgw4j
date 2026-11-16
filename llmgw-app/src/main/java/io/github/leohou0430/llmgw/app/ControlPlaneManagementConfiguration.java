package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.local.DefaultConfigValidator;
import io.github.leohou0430.llmgw.control.local.RouteVersionService;
import io.github.leohou0430.llmgw.persistence.sqlite.Migrations;
import io.github.leohou0430.llmgw.persistence.sqlite.SchemaMigrator;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteContext;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.CsrfTokenService;
import io.github.leohou0430.llmgw.identity.PasswordHasher;
import io.github.leohou0430.llmgw.identity.SessionStore;
import io.github.leohou0430.llmgw.identity.local.AdministratorBootstrap;
import io.github.leohou0430.llmgw.identity.local.CredentialIssuer;
import io.github.leohou0430.llmgw.identity.local.HmacCsrfTokenService;
import io.github.leohou0430.llmgw.identity.local.InMemoryAccountStore;
import io.github.leohou0430.llmgw.identity.local.InMemorySessionStore;
import io.github.leohou0430.llmgw.identity.local.ManagedKeyService;
import io.github.leohou0430.llmgw.identity.local.Pbkdf2PasswordHasher;
import io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import io.github.leohou0430.llmgw.identity.local.SessionService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the control-plane foundation into the running app: identity (accounts, sessions,
 * CSRF, RBAC, key lifecycle) and route versioning. The first platform admin is created
 * ONLY from explicitly provided environment credentials (no default credential, no public
 * self-registration). In-memory stores here; durable SQLite backing is a follow-up.
 */
@Configuration
public class ControlPlaneManagementConfiguration {

    private static final long SESSION_TTL_MILLIS = 3_600_000L;

    @Bean
    AccountStore accountStore(SqliteContext controlDatabase) {
        return new SqliteAccountStore(controlDatabase);
    }

    @Bean
    PasswordHasher passwordHasher() {
        return Pbkdf2PasswordHasher.withIterations(120_000);
    }

    @Bean
    SessionStore sessionStore(SqliteContext controlDatabase) {
        return new SqliteSessionStore(controlDatabase);
    }

    @Bean
    CsrfTokenService csrfTokenService() {
        return HmacCsrfTokenService.randomKey();
    }

    @Bean
    AuthorizationService authorizationService() {
        return new RbacAuthorizationService();
    }

    @Bean
    SessionService sessionService(SessionStore sessions, PasswordHasher hasher) {
        return new SessionService(sessions, hasher, SESSION_TTL_MILLIS);
    }

    @Bean
    ManagedKeyService managedKeyService(AuthorizationService authorization, SqliteContext controlDatabase) {
        return new ManagedKeyService(new io.github.leohou0430.llmgw.persistence.sqlite.SqliteKeyStore(controlDatabase, true),
                authorization, new CredentialIssuer());
    }

    @Bean
    RequestAuthorizer requestAuthorizer(SessionStore sessions, CsrfTokenService csrf,
                                        AccountStore accounts, AuthorizationService authorization) {
        return new RequestAuthorizer(sessions, csrf, accounts, authorization, System::currentTimeMillis);
    }

    @Bean(destroyMethod = "close")
    SqliteContext controlDatabase() throws java.io.IOException, java.sql.SQLException {
        String configured = System.getenv("LLMGW_CONTROL_DB");
        java.nio.file.Path file = java.nio.file.Path.of(
                configured == null || configured.isBlank() ? "./data/llmgw-control.db" : configured);
        java.nio.file.Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        SqliteContext database = new SqliteContext(file, 2000L);
        new SchemaMigrator(database).migrate(Migrations.all());
        return database;
    }

    @Bean
    RouteVersionService routeVersionService(SqliteContext controlDatabase) {
        return new RouteVersionService(new SqliteRouteVersionStore(controlDatabase));
    }

    @Bean
    DefaultConfigValidator configValidator() {
        return new DefaultConfigValidator();
    }

    @Bean
    io.github.leohou0430.llmgw.control.PlatformPolicy platformPolicy() {
        return io.github.leohou0430.llmgw.control.PlatformPolicy.strict();
    }

    @Bean
    io.github.leohou0430.llmgw.control.local.RouteCandidateValidator routeCandidateValidator(
            io.github.leohou0430.llmgw.control.ConfigValidator configValidator) {
        return new io.github.leohou0430.llmgw.control.local.RouteCandidateValidator(configValidator);
    }

    @Bean
    io.github.leohou0430.llmgw.control.SecretCipher secretCipher() {
        String encoded = System.getenv("LLMGW_ROOT_KEY");
        io.github.leohou0430.llmgw.control.RootKeyProvider rootKey =
                encoded == null || encoded.isBlank()
                        ? io.github.leohou0430.llmgw.control.local.StaticRootKey.missing()
                        : new io.github.leohou0430.llmgw.control.local.StaticRootKey(
                                java.util.Base64.getDecoder().decode(encoded));
        return new io.github.leohou0430.llmgw.control.local.AesGcmSecretCipher(rootKey);
    }

    @Bean
    io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storageGuard() {
        String configured = System.getenv("LLMGW_CONTROL_DB");
        java.nio.file.Path file = java.nio.file.Path.of(
                configured == null || configured.isBlank() ? "./data/llmgw-control.db" : configured);
        String cap = System.getenv("LLMGW_CONTROL_DB_MAX_BYTES");
        long maxBytes = cap == null || cap.isBlank() ? 512L * 1024 * 1024 : Long.parseLong(cap);
        return new io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard(
                maxBytes, 256L * 1024 * 1024, file);
    }

    @Bean
    SecretStore secretStore(SqliteContext controlDatabase, io.github.leohou0430.llmgw.control.SecretCipher secretCipher,
                            io.github.leohou0430.llmgw.persistence.sqlite.StorageGuard storageGuard) {
        return new SecretStore(controlDatabase, secretCipher, storageGuard::requireWritable);
    }

    @Bean
    AuditLog auditLog(SqliteContext controlDatabase) {
        return new AuditLog(controlDatabase);
    }

    @Bean
    OrgStore orgStore(SqliteContext controlDatabase) {
        return new OrgStore(controlDatabase);
    }

    @Bean
    io.github.leohou0430.llmgw.catalog.ResourceCatalog resourceCatalog(SqliteContext controlDatabase) {
        return new io.github.leohou0430.llmgw.catalog.local.SqliteResourceCatalog(controlDatabase, projectId -> true);
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
            // Optional project viewer (for role-based acceptance); disabled unless env-provided.
            String viewer = System.getenv("LLMGW_VIEWER_USERNAME");
            String viewerPassword = System.getenv("LLMGW_VIEWER_PASSWORD");
            if (viewer != null && !viewer.isBlank() && viewerPassword != null && !viewerPassword.isBlank()
                    && accounts.findByUsername(viewer).isEmpty()) {
                accounts.create(new io.github.leohou0430.llmgw.identity.Account(
                        java.util.UUID.randomUUID().toString(), viewer, hasher.hash(viewerPassword),
                        io.github.leohou0430.llmgw.identity.Role.PROJECT_VIEWER,
                        io.github.leohou0430.llmgw.identity.Scope.project("t1", "p1"), true));
            }
        };
    }
}