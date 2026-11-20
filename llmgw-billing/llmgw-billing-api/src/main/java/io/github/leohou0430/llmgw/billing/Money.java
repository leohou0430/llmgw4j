package io.github.leohou0430.llmgw.billing;

import java.util.Objects;

/**
 * 金额：整数 nano-CNY（1e-9 元）与显式币种。入账粒度 1 micro-CNY（1000 nano），
 * 不足单位的尾数按租户持久累计，不逐次丢弃（ADR-0007）。 零价必须显式（显式 FREE），未知成本不记为零——由调用方区分 MISSING 与 FREE。
 */
public final class Money implements Comparable<Money> {

	public static final String CNY = "CNY";

	public static final Money ZERO_CNY = new Money(0, CNY);

	private final long amountNano;

	private final String currency;

	public Money(long amountNano, String currency) {
		this.amountNano = amountNano;
		this.currency = Objects.requireNonNull(currency, "currency");
		if (currency.isBlank())
			throw new IllegalArgumentException("currency must not be blank");
	}

	public static Money ofNanoCny(long nano) {
		return new Money(nano, CNY);
	}

	public long amountNano() {
		return amountNano;
	}

	public String currency() {
		return currency;
	}

	/** 加法；币种不同抛 IllegalArgumentException（错币种不隐式换算）。 */
	public Money add(Money other) {
		requireSameCurrency(other);
		long sum = Math.addExact(amountNano, other.amountNano);
		return new Money(sum, currency);
	}

	public Money subtract(Money other) {
		requireSameCurrency(other);
		return new Money(Math.subtractExact(amountNano, other.amountNano), currency);
	}

	/** 估算预留向上取整到完整 micro-CNY（ADR：预留向上取整）。 */
	public Money reserveCeil() {
		long rounded = ((amountNano + 999) / 1000) * 1000;
		return new Money(rounded, currency);
	}

	public boolean isNegative() {
		return amountNano < 0;
	}

	private void requireSameCurrency(Money other) {
		if (!currency.equals(other.currency)) {
			throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
		}
	}

	@Override
	public int compareTo(Money other) {
		requireSameCurrency(other);
		return Long.compare(amountNano, other.amountNano);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Money m && amountNano == m.amountNano && currency.equals(m.currency);
	}

	@Override
	public int hashCode() {
		return Objects.hash(amountNano, currency);
	}

	@Override
	public String toString() {
		return currency + " " + String.format(java.util.Locale.ROOT, "%.9f", amountNano / 1e9);
	}

}
