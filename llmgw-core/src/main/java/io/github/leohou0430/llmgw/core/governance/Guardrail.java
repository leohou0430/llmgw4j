package io.github.leohou0430.llmgw.core.governance;

import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;

/**
 * Content governance hook (v13, W4.1). Runs INSIDE the governance chain at
 * admission, before any upstream I/O: a guardrail can validate (deny with a
 * reason) or mutate (rewrite the JSON body that reaches the upstream).
 *
 * <p>Contract notes, learned from the market survey (Kong ai-prompt-guard,
 * TrueFoundry's validate/mutate, Portkey's guards): the hot path needs a
 * synchronous verdict BEFORE the request is forwarded, and a guardrail must
 * never see re-serialized bytes it did not ask for -- {@code mutatedJson} is
 * returned verbatim to the forward path, everything else passes through.
 * Response-side inspection is deliberately NOT in the hot path: streaming
 * bodies are byte-faithful and would have to be buffered to inspect.
 */
public interface Guardrail {

    enum Action { ALLOW, DENY }

    record Verdict(Action action, String reason, String mutatedJson) {

        public static Verdict allow() {
            return new Verdict(Action.ALLOW, "", null);
        }

        public static Verdict deny(String reason) {
            return new Verdict(Action.DENY, reason, null);
        }

        public static Verdict mutate(String mutatedJson) {
            return new Verdict(Action.ALLOW, "", mutatedJson);
        }
    }

    /** Human-readable name for audit events and metrics. */
    String name();

    Verdict inspectRequest(ParsedRequest request);
}
