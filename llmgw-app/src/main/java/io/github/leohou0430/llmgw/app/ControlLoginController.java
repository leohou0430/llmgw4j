package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.CsrfTokenService;
import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.AuthOutcome;
import io.github.leohou0430.llmgw.identity.local.AuthenticatedRequest;
import io.github.leohou0430.llmgw.identity.local.RequestAuthorizer;
import io.github.leohou0430.llmgw.identity.local.SessionService;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Login endpoint: verifies the account password, creates a random server-side session, sets
 * an HttpOnly/SameSite session cookie, and returns a session-bound CSRF token. No default
 * credentials; accounts exist only via explicit bootstrap. The Secure attribute follows
 * {@code gateway.control-cookie-secure} so plain-http local runs work while TLS deployments
 * set it to true.
 */
@RestController
@RequestMapping("/control")
public class ControlLoginController {
    private static final long SESSION_TTL_MILLIS = 3_600_000L;

    private final io.github.leohou0430.llmgw.identity.LoginRateLimiter limiter =
            new io.github.leohou0430.llmgw.identity.local.FixedWindowRateLimiter(10, 60_000L);
    private final AccountStore accounts;
    private final SessionService sessions;
    private final CsrfTokenService csrf;
    private final SessionStore sessionStore;
    private final RequestAuthorizer authorizer;
    private final boolean cookieSecure;

    public ControlLoginController(AccountStore accounts, SessionService sessions, CsrfTokenService csrf,
                                  SessionStore sessionStore, RequestAuthorizer authorizer,
                                  GatewayProperties properties) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.csrf = csrf;
        this.sessionStore = sessionStore;
        this.authorizer = authorizer;
        this.cookieSecure = properties.isControlCookieSecure();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginBody body) {
        if (body.username() == null || body.password() == null || body.username().length() > 256
                || body.password().length() > 4096) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid login input");
        }
        if (!limiter.allow(body.username(), System.currentTimeMillis())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "login rate limit exceeded");
        }
        Optional<Session> session = accounts.findByUsername(body.username())
                .flatMap(account -> sessions.login(account, body.password(), System.currentTimeMillis()));
        if (session.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        ResponseCookie cookie = ResponseCookie.from("llmgw_sid", session.get().id())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/control")
                .maxAge(Duration.ofMillis(SESSION_TTL_MILLIS))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("csrfToken", csrf.issue(session.get().id())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken) {
        if (authorizer.account(sessionId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
        }
        if (!authorizer.verifySessionMutation(sessionId, csrfToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CSRF_FAILED");
        }
        sessionStore.revoke(sessionId);
        ResponseCookie cleared = ResponseCookie.from("llmgw_sid", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/control")
                .maxAge(Duration.ZERO)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(Map.of("loggedOut", true));
    }

    @org.springframework.web.bind.annotation.GetMapping("/session")
    public ResponseEntity<Map<String, Object>> restore(
            @CookieValue(name = "llmgw_sid", required = false) String sessionId) {
        var account = authorizer.account(sessionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session"));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("csrfToken", csrf.issue(sessionId), "account", Map.of(
                        "id", account.id(), "username", account.username(), "role", account.role().name(),
                        "tenantId", account.scope().tenantId(), "projectId", account.scope().projectId())));
    }

    public record LoginBody(String username, String password) {
    }
}
