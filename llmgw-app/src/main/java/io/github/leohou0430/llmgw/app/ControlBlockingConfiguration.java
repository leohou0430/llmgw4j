package io.github.leohou0430.llmgw.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.config.BlockingExecutionConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Dispatch the entire synchronous management invocation, including auth and JDBC, off
 * Netty.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ControlBlockingConfiguration implements WebFluxConfigurer {

	private final ThreadPoolTaskExecutor executor;

	public ControlBlockingConfiguration() {
		executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(8);
		executor.setQueueCapacity(128);
		executor.setThreadNamePrefix("control-blocking-");
		executor.initialize();
	}

	@Bean(destroyMethod = "shutdown")
	ThreadPoolTaskExecutor controlBlockingExecutor() {
		return executor;
	}

	@Override
	public void configureBlockingExecution(BlockingExecutionConfigurer configurer) {
		configurer.setExecutor(executor)
			.setControllerMethodPredicate(
					method -> method.getBeanType().getPackageName().equals(getClass().getPackageName())
							&& method.getBeanType().getSimpleName().startsWith("Control"));
	}

}
