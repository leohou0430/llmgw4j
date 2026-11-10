package io.github.leohou0430.llmgw.governance;

/** Stable Dubbo module names and Redis namespace shared by independent implementation packs. */
public final class GovernancePacks {
    private GovernancePacks() {}
    public static final String DEFAULT_REDIS_KEY_PREFIX = "llmgw:";
    public static final String IN_MEMORY = "in-memory";
    public static final String REDIS = "redis";
}
