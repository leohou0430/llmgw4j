package io.github.leohou0430.llmgw.control;

import java.util.List;

/**
 * Validates a proposed upstream configuration against a platform policy. Returns the list
 * of violations (empty = accepted). The policy is authoritative: a project candidate can
 * never relax it.
 */
public interface ConfigValidator {

	List<String> validate(UpstreamCandidate candidate, PlatformPolicy policy);

}