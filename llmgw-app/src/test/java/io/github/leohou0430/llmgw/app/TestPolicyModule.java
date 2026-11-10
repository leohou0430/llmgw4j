package io.github.leohou0430.llmgw.app;
import io.github.leohou0430.llmgw.core.extension.*;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
public final class TestPolicyModule implements ExtensionModule {
    public void configure(ExtensionContext c) { c.replace(TokenLimiter.class,DenyLimiter::new); }
    public static final class DenyLimiter implements TokenLimiter {
        public boolean tryAcquire(String key,int tokens) { return false; }
        public void refund(String key,int tokens) {}
        public long remaining(String key) { return 0; }
    }
}
