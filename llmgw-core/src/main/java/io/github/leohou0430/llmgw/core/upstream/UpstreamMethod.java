package io.github.leohou0430.llmgw.core.upstream;

/**
 * Upstream HTTP verb: POST for create-style calls, GET for poll/list/content,
 * DELETE for resource removal (W4.3 files).
 */
public enum UpstreamMethod {
    POST,
    GET,
    DELETE
}
