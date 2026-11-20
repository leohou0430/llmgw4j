package io.github.leohou0430.llmgw.engine.webflux;

import io.github.leohou0430.llmgw.engine.AbstractEngineConfiguration;
import io.github.leohou0430.llmgw.engine.EngineKind;
import io.github.leohou0430.llmgw.engine.EngineProfiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile(EngineProfiles.WEBFLUX)
public class WebfluxEngineConfiguration extends AbstractEngineConfiguration {

	@Bean
	public WebfluxCapacityAdmission webfluxCapacityAdmission(GatewayProperties properties,
			RequestMappingHandlerAdapter delegate) {
		return new WebfluxCapacityAdmission(properties, delegate);
	}

	public WebfluxEngineConfiguration() {
		super(EngineKind.WEBFLUX);
	}

}
