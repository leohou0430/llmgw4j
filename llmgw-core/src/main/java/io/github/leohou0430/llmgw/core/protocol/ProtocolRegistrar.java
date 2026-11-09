package io.github.leohou0430.llmgw.core.protocol;

import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Bridges Spring to the static protocol registry: every {@link Protocol} bean
 * in the context overrides the default implementation for its kind at startup.
 * A deployment adds a protocol behavior variant by declaring one bean -- no
 * core switch to touch.
 */
public class ProtocolRegistrar implements ApplicationRunner {

    private final ObjectProvider<Protocol> protocols;

    public ProtocolRegistrar(ObjectProvider<Protocol> protocols) {
        this.protocols = protocols;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        for (Protocol protocol : protocols) {
            Protocols.register(protocol);
        }
    }
}
