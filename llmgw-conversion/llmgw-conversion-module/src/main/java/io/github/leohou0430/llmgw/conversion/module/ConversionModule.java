package io.github.leohou0430.llmgw.conversion.module;

import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.ConversionService;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.conversion.UpstreamCodec;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.core.extension.Extensions;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Named conversion assembly (T1.11): discovers the codec implementations by the project-owned SPI and
 * composes them with explicit constructors. No Spring, no scanning, no static stateful registry --
 * every instance and every key belongs to the {@link ExtensionContext} being configured.
 *
 * <p>The codec impl jars are supplied at runtime by the application classpath; this module compiles
 * against the api SPI only and never depends on a sibling implementation.
 */
public final class ConversionModule implements ExtensionModule {

    /** Stage 1 has exactly one upstream dialect; the name is an explicit SPI choice, not a scan. */
    private static final String UPSTREAM_CODEC_NAME = "deepseek-chat";
    private static final int GENERATED_KEY_BYTES = 32;

    @Override
    public void configure(ExtensionContext c) {
        GatewayProperties properties = c.get(GatewayProperties.class);
        Map<ProtocolKind, EntryCodec> entryCodecs = discoverEntryCodecs();
        UpstreamCodec upstream = Extensions.load(UpstreamCodec.class).getExtension(UPSTREAM_CODEC_NAME);
        c.bindDefault(ContinuationEnvelope.class, () -> new ContinuationEnvelope(continuationKey(properties)));
        c.bindDefault(ConversionService.class, () -> new DefaultConversionService(
                entryCodecs, upstream, c.get(ContinuationEnvelope.class)));
    }

    /**
     * Key the entry codecs by the protocol they declare, not by their extension name: the name -&gt;
     * kind mapping stays owned by each codec. Two codecs claiming one kind is an assembly conflict.
     */
    private static Map<ProtocolKind, EntryCodec> discoverEntryCodecs() {
        Extensions<EntryCodec> extensions = Extensions.load(EntryCodec.class);
        Map<ProtocolKind, EntryCodec> byKind = new EnumMap<>(ProtocolKind.class);
        for (String name : extensions.getSupportedExtensions()) {
            EntryCodec codec = extensions.getExtension(name);
            ProtocolKind kind = Objects.requireNonNull(codec.kind(), "entry codec kind: " + name);
            EntryCodec previous = byKind.putIfAbsent(kind, codec);
            if (previous != null) {
                throw new IllegalStateException("duplicate entry codec for protocol " + kind.wireName()
                        + ": " + previous.getClass().getName() + " and " + codec.getClass().getName());
            }
        }
        if (byKind.isEmpty()) {
            throw new IllegalStateException("no entry codec discovered for " + EntryCodec.class.getName());
        }
        return Map.copyOf(byKind);
    }

    /**
     * The HMAC key belongs to the runtime, never to a codec. A blank configured key means continuation
     * tokens are process-scoped (stage-1 default); production injects a stable secret via env
     * (gateway.conversion.continuation-key). The key is never logged.
     */
    private static byte[] continuationKey(GatewayProperties properties) {
        String configured = properties.getConversion().getContinuationKey();
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        byte[] generated = new byte[GENERATED_KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        return generated;
    }
}