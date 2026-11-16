package io.github.leohou0430.llmgw.identity;

import java.util.Objects;

/** The object an action targets: its owning tenant and (optionally) project. */
public record Target(String tenantId, String projectId) {

    public Target {
        tenantId = Objects.requireNonNull(tenantId, "tenantId");
        projectId = projectId == null ? "" : projectId;
    }
}