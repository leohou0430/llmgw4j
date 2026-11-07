package io.github.leohou0430.llmgw.governance;

/**
 * Compile-time constants that wire governance implementation packs together.
 * All are valid in annotations, so no pack has to spell another pack's class
 * name or property key as a string literal.
 *
 * <p>Ordering contract: an opt-in pack registers its beans BEFORE the default
 * pack. Both guard every bean with {@code @ConditionalOnMissingBean} on the SPI
 * type, so the default pack silently backs off and the data planes stay unaware
 * of which pack won.
 */
public final class GovernancePacks {

    private GovernancePacks() {
    }

    /** Referenced by {@code @AutoConfigureBefore} in opt-in packs. */
    public static final String DEFAULT_AUTO_CONFIGURATION =
            "io.github.leohou0430.llmgw.governance.inmemory.GovernanceDefaultsConfiguration";

    /** {@code @ConditionalOnProperty} switch for the Redis pack. */
    public static final String REDIS_PROPERTY_PREFIX = "gateway.redis";
    public static final String REDIS_PROPERTY_NAME = "enabled";

    /** Key namespace shared by the three Redis-backed SPIs. */
    public static final String DEFAULT_REDIS_KEY_PREFIX = "llmgw:";

    public static final String IN_MEMORY = "in-memory";
    public static final String REDIS = "redis";
}
