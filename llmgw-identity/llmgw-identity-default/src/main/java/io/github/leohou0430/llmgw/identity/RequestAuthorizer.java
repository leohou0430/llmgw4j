package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.CsrfTokenService;
import io.github.leohou0430.llmgw.identity.Session;
import io.github.leohou0430.llmgw.identity.SessionStore;
import io.github.leohou0430.llmgw.identity.Subject;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Authorizes a control-plane request in order: a live session (else UNAUTHENTICATED), an
 * enabled owning account, CSRF for mutating calls (else CSRF_FAILED), then RBAC on the
 * action/target (else FORBIDDEN). Reads do not require CSRF. All checks are server-side.
 */
public final class RequestAuthorizer {
    private final SessionStore sessions;
    private final CsrfTokenService csrf;
    private final AccountStore accounts;
    private final AuthorizationService authorization;
    private final LongSupplier clock;

    public RequestAuthorizer(SessionStore sessions, CsrfTokenService csrf, AccountStore accounts,
                             AuthorizationService authorization, LongSupplier clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.csrf = Objects.requireNonNull(csrf, "csrf");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<Account> account(String sessionId) {
        return sessions.find(sessionId, clock.getAsLong()).flatMap(session -> accounts.findById(session.accountId()))
                .filter(Account::enabled);
    }

    public io.github.leohou0430.llmgw.identity.Target target(String sessionId, String tenantId, String projectId) {
        Account account = account(sessionId).orElseThrow(() -> new SecurityException("unauthenticated"));
        return new io.github.leohou0430.llmgw.identity.Target(
                tenantId == null ? account.scope().tenantId() : tenantId,
                projectId == null ? account.scope().projectId() : projectId);
    }

    public Subject subject(String sessionId) {
        Account account = account(sessionId).orElseThrow(() -> new SecurityException("unauthenticated"));
        return new Subject(account.role(), account.scope());
    }

    public boolean verifySessionMutation(String sessionId, String token) {
        return account(sessionId).isPresent() && csrf.verify(sessionId, token);
    }

    public AuthOutcome authorize(AuthenticatedRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Session> session = sessions.find(request.sessionId(), clock.getAsLong());
        if (session.isEmpty()) {
            return AuthOutcome.UNAUTHENTICATED;
        }
        Optional<Account> account = accounts.findById(session.get().accountId());
        if (account.isEmpty() || !account.get().enabled()) {
            return AuthOutcome.UNAUTHENTICATED;
        }
        if (request.mutating() && !csrf.verify(request.sessionId(), request.csrfToken())) {
            return AuthOutcome.CSRF_FAILED;
        }
        Subject subject = new Subject(account.get().role(), account.get().scope());
        return authorization.decide(subject, request.action(), request.target()).allowed()
                ? AuthOutcome.ALLOW
                : AuthOutcome.FORBIDDEN;
    }
}
