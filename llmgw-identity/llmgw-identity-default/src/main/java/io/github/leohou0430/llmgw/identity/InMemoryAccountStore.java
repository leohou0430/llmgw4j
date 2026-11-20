package io.github.leohou0430.llmgw.identity.local;

import io.github.leohou0430.llmgw.identity.Account;
import io.github.leohou0430.llmgw.identity.AccountStore;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link AccountStore} behind the SPI. Durable SQLite persistence for accounts
 * (and the session store) is a follow-up wiring slice; this default keeps the identity
 * contract testable without a database.
 */
public final class InMemoryAccountStore implements AccountStore {

	private final Map<String, Account> byUsername = new ConcurrentHashMap<>();

	@Override
	public Account create(Account account) {
		Objects.requireNonNull(account, "account");
		if (byUsername.putIfAbsent(account.username(), account) != null) {
			throw new IllegalArgumentException("duplicate username: " + account.username());
		}
		return account;
	}

	@Override
	public Optional<Account> findByUsername(String username) {
		return Optional.ofNullable(username == null ? null : byUsername.get(username));
	}

	@Override
	public Optional<Account> findById(String accountId) {
		if (accountId == null) {
			return Optional.empty();
		}
		return byUsername.values().stream().filter(account -> account.id().equals(accountId)).findFirst();
	}

	@Override
	public void updatePassword(String username, String newPasswordHash) {
		byUsername.compute(username, (ignored, existing) -> existing == null ? null : new Account(existing.id(),
				existing.username(), newPasswordHash, existing.role(), existing.scope(), existing.enabled()));
	}

	@Override
	public boolean existsAny() {
		return !byUsername.isEmpty();
	}

}