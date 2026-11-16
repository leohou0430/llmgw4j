package io.github.leohou0430.llmgw.identity;

import java.util.Objects;

/**
 * Authorization scope of an account. A platform admin is global (both blank); a tenant
 * admin is bound to a tenant; project admins/observers are bound to one project. Blank
 * means "not scoped at that level".
 */
public record Scope(String tenantId, String projectId) {

    public Scope {
        tenantId = tenantId == null ? "" : tenantId;
        projectId = projectId == null ? "" : projectId;
    }

    public static Scope platform() {
        return new Scope("", "");
    }

    public static Scope tenant(String tenantId) {
        return new Scope(Objects.requireNonNull(tenantId, "tenantId"), "");
    }

    public static Scope project(String tenantId, String projectId) {
        return new Scope(Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(projectId, "projectId"));
    }

    public boolean isPlatform() {
        return tenantId.isEmpty() && projectId.isEmpty();
    }
}