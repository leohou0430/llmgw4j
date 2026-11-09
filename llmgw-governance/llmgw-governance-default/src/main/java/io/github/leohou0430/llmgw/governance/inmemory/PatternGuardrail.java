package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.Guardrail;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Config-driven deny-pattern guardrail -- the Kong {@code ai-prompt-guard}
 * shape: regexes matched against the extracted prompt text; any hit denies
 * with 400. Disabled by default ({@code gateway.guardrails.deny-patterns}
 * empty); patterns are case-insensitive. This is the in-process baseline;
 * deploy the HTTP engine adapter (external moderation service) for anything
 * heavier -- same SPI, same hook point.
 */
public class PatternGuardrail implements Guardrail {

    private final List<Pattern> denyPatterns;

    public PatternGuardrail(GatewayProperties properties) {
        this.denyPatterns = new ArrayList<>();
        for (String raw : properties.getGuardrails().getDenyPatterns()) {
            if (raw != null && !raw.isBlank()) {
                this.denyPatterns.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE));
            }
        }
    }

    @Override
    public String name() {
        return "pattern-deny";
    }

    @Override
    public Verdict inspectRequest(ParsedRequest request) {
        if (denyPatterns.isEmpty()) {
            return Verdict.allow();
        }
        String text = request.promptText();
        if (text == null || text.isEmpty()) {
            return Verdict.allow();
        }
        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(text).find()) {
                return Verdict.deny("request blocked by guardrail " + name()
                        + " (pattern " + pattern.pattern() + ")");
            }
        }
        return Verdict.allow();
    }
}
