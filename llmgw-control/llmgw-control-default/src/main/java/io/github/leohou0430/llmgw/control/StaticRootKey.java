package io.github.leohou0430.llmgw.control.local;

import io.github.leohou0430.llmgw.control.RootKeyProvider;

/** Externally injected fixed root key, or an explicit missing provider that fails closed. */
public final class StaticRootKey implements RootKeyProvider {
    private final byte[] key;
    private final boolean missing;

    public StaticRootKey(byte[] key) {
        this.key = key.clone();
        this.missing = false;
    }

    private StaticRootKey() {
        this.key = null;
        this.missing = true;
    }

    public static StaticRootKey missing() {
        return new StaticRootKey();
    }

    @Override
    public byte[] requireRootKey() {
        if (missing) {
            throw new io.github.leohou0430.llmgw.control.RootKeyMissingException(
                    "root key not configured; provider secrets cannot be stored or read");
        }
        return key.clone();
    }
}