package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.CsrfTokenService;
import io.github.leohou0430.llmgw.identity.Decision;
import io.github.leohou0430.llmgw.identity.PasswordHasher;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import io.github.leohou0430.llmgw.identity.local.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlLoginControllerCookieTest {

    private static final Account ACCOUNT = new Account("a1", "admin", "hash",
            Role.PLATFORM_ADMIN, Scope.platform(), true);

    @Test
    void localDefaultKeepsCookieNonSecure() {
        String cookie = loginCookie(new GatewayProperties());

        assertFalse(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/control"));
    }

    @Test
    void tlsDeploymentMarksCookieSecure() {
        GatewayProperties properties = new GatewayProperties();
        properties.setControlCookieSecure(true);

        assertTrue(loginCookie(properties).contains("Secure"));
    }

    @Test
    void logoutClearsCookieWithConfiguredSecureAttribute() {
        GatewayProperties properties = new GatewayProperties();
        properties.setControlCookieSecure(true);

        ResponseEntity<Map<String, Object>> response = controller(properties).logout("sid-1", "csrf-1");
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        assertEquals(Boolean.TRUE, response.getBody().get("loggedOut"));
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
    }

    @Test
    void loginReturnsSessionBoundCsrfToken() {
        ResponseEntity<Map<String, Object>> response = controller(new GatewayProperties())
                .login(new ControlLoginController.LoginBody("admin", "pw"));

        assertEquals("csrf-1", response.getBody().get("csrfToken"));
    }

    @Test
    void realHttpLoginMappingEnforcesLimitBeforePasswordWork() {
        var client = org.springframework.test.web.reactive.server.WebTestClient
                .bindToController(controller(new GatewayProperties())).build();
        for (int i=0;i<10;i++) client.post().uri("/control/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username","admin","password","pw"))
                .exchange().expectStatus().isOk();
        client.post().uri("/control/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username","admin","password","pw"))
                .exchange().expectStatus().isEqualTo(429);
        client.get().uri("/control/session").exchange().expectStatus().isUnauthorized();
    }

    private static String loginCookie(GatewayProperties properties) {
        ResponseEntity<Map<String, Object>> response = controller(properties)
                .login(new ControlLoginController.LoginBody("admin", "pw"));
        return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    }

    private static ControlLoginController controller(GatewayProperties properties) {
        FakeSessions sessionStore = new FakeSessions();
        PasswordHasher hasher = new PasswordHasher() {
            @Override
            public String hash(String rawPassword) {
                return "hash";
            }

            @Override
            public boolean verify(String rawPassword, String encodedHash) {
                return true;
            }
        };
        CsrfTokenService csrf = new CsrfTokenService() {
            @Override
            public String issue(String sessionId) {
                return "csrf-1";
            }

            @Override
            public boolean verify(String sessionId, String token) {
                return true;
            }
        };
        AccountStore accounts = new AccountStore() {
            @Override
            public Account create(Account account) {
                return account;
            }

            @Override
            public Optional<Account> findByUsername(String username) {
                return "admin".equals(username) ? Optional.of(ACCOUNT) : Optional.empty();
            }

            @Override
            public Optional<Account> findById(String accountId) {
                return ACCOUNT.id().equals(accountId) ? Optional.of(ACCOUNT) : Optional.empty();
            }

            @Override
            public void updatePassword(String username, String newPasswordHash) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean existsAny() {
                return true;
            }
        };
        AuthorizationService authorization = new AuthorizationService() {
            @Override
            public Decision decide(Subject subject, io.github.leohou0430.llmgw.identity.Action action,
                                   io.github.leohou0430.llmgw.identity.Target target) {
                return Decision.ALLOW;
            }

            @Override
            public Decision grantRole(Subject actor, io.github.leohou0430.llmgw.identity.Target target,
                                      Role grantedRole) {
                return Decision.ALLOW;
            }
        };
        SessionService sessions = new SessionService(sessionStore, hasher, 3_600_000L);
        RequestAuthorizer authorizer = new RequestAuthorizer(
                sessionStore, csrf, accounts, authorization, () -> 1_000L);
        return new ControlLoginController(accounts, sessions, csrf, sessionStore, authorizer, properties);
    }

    private static final class FakeSessions implements SessionStore {
        @Override
        public Session create(String accountId, long ttlMillis, long nowEpochMs) {
            return new Session("sid-1", accountId, nowEpochMs + ttlMillis, 0);
        }

        @Override
        public Optional<Session> find(String sessionId, long nowEpochMs) {
            return "sid-1".equals(sessionId)
                    ? Optional.of(new Session(sessionId, ACCOUNT.id(), Long.MAX_VALUE, 0))
                    : Optional.empty();
        }

        @Override
        public void revoke(String sessionId) {
            // cleared-cookie test only needs authorization to succeed
        }

        @Override
        public void revokeAllForAccount(String accountId) {
            // no-op for this unit scope
        }
    }
}
