package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.engine.EngineKind;
import java.util.Optional;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Publishes the active data-plane identity before ANY bean is instantiated.
 *
 * <p>{@code DefaultGovernanceChain} reads {@code System.getProperty("llmgw.engine")}
 * once when it is constructed, to label Micrometer meters. Publishing the value
 * from an engine {@code @Configuration} constructor was too late: the
 * governance auto-configuration is created first, so every meter was stamped
 * with {@code engine=unknown}. An {@link EnvironmentPostProcessor} runs during
 * {@code ApplicationEnvironmentPreparedEvent}, before the context refresh, so
 * the property is visible to every bean regardless of wiring order.
 *
 * <p>Runs last (after {@code ConfigDataEnvironmentPostProcessor}, which resolves
 * {@code spring.profiles.active} from config files) so profile-driven activation
 * is handled the same way as command-line activation.
 *
 * <p>The engine {@code @Configuration} still calls {@code EngineKind#activate()}
 * as a no-op idempotent backstop; this class owns the timing guarantee.
 */
public class EngineKindEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        for (String profile : environment.getActiveProfiles()) {
            Optional<EngineKind> kind = EngineKind.fromProfile(profile);
            if (kind.isPresent()) {
                kind.get().activate();
                return;
            }
        }
        for (String profile : application.getAdditionalProfiles()) {
            Optional<EngineKind> kind = EngineKind.fromProfile(profile);
            if (kind.isPresent()) {
                kind.get().activate();
                return;
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
