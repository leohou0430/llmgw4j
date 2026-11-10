package io.github.leohou0430.llmgw.governance.inmemory;

import io.github.leohou0430.llmgw.core.governance.Guardrail;
import io.github.leohou0430.llmgw.core.protocol.ParsedRequest;

import java.util.List;

/**
 * Composite guardrail: first DENY wins, order = pattern-deny (cheap, local)
 * before the HTTP engine (network). Empty configuration allows everything at
 * the cost of two emptiness checks.
 */
public class GuardrailChain implements Guardrail {

    private final List<Guardrail> chain;

    public GuardrailChain(List<Guardrail> chain) {
        this.chain = List.copyOf(chain);
    }

    @Override
    public String name() {
        return "chain";
    }

    @Override
    public Verdict inspectRequest(ParsedRequest request) {
        for (Guardrail guardrail : chain) {
            Verdict verdict = guardrail.inspectRequest(request);
            if (verdict.action() != Action.ALLOW) {
                return verdict;
            }
        }
        return Verdict.allow();
    }
}
