package io.github.leohou0430.llmgw.tokenizer.jtokkit;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.Encoding;
import io.github.leohou0430.llmgw.core.governance.TokenCounter;
import org.springframework.stereotype.Component;

/**
 * B6: real tokenizer via jtokkit (tiktoken BPE, cl100k_base). Deterministic
 * and thread-safe (the registry caches encodings). Registered only when the
 * -Pjtokkit profile puts this module on the classpath AND
 * {@code gateway.tokenizer=jtokkit} selects it over the heuristic default.
 */
@Component
public class JtokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    public JtokkitTokenCounter() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    @Override
    public String name() {
        return "jtokkit-cl100k";
    }
}
