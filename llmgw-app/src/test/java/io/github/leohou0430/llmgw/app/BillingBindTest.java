package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class BillingBindTest {

	@Test
	void billingEnabledBindsIntoNestedBillingClass() {
		var env = new StandardEnvironment();
		var values = new java.util.HashMap<String, Object>();
		values.put("gateway.billing.enabled", "true");
		env.getPropertySources().addFirst(new MapPropertySource("t", values));
		var p = Binder.get(env).bind("gateway", Bindable.of(GatewayProperties.class)).get();
		assertTrue(p.getBilling().isEnabled(), "gateway.billing.enabled 必须绑定到 Billing 嵌套类");
	}

}
