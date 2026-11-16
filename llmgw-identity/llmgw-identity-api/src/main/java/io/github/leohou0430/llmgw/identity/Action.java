package io.github.leohou0430.llmgw.identity;

/** Control-plane actions checked server-side against the caller's role and scope. */
public enum Action {
    TENANT_MANAGE,
    SOURCE_MANAGE,
    MEMBER_GRANT,
    KEY_MANAGE,
    ROUTE_PUBLISH,
    ROUTE_ROLLBACK,
    CATALOG_READ,
    CATALOG_WRITE,
    USAGE_READ,
    AUDIT_READ,
    FILE_DELETE,
    TASK_CANCEL,
    READ_CREDENTIAL,
    READ_PASSWORD_HASH
}