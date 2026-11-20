package io.github.leohou0430.llmgw.identity.local;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedLoginRateLimiterTest {

	@Test
	void limitRecoversAndExpiredIdentitiesDoNotFillCapacity() {
		var limiter = new FixedWindowRateLimiter(2, 100);
		assertTrue(limiter.allow("account", 0));
		assertTrue(limiter.allow("account", 1));
		assertFalse(limiter.allow("account", 2));
		assertTrue(limiter.allow("account", 100));
		for (int i = 0; i < 4095; i++)
			assertTrue(limiter.allow("name" + i, 100));
		assertFalse(limiter.allow("overflow", 100));
		assertTrue(limiter.allow("recovered", 200));
	}

}
