package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import io.github.leohou0430.llmgw.persistence.sqlite.SqliteErrors;
import io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal tenant/project directory over the foundational {@code tenant}/{@code project}
 * tables (migration V1). The shared {@link DatabaseContext} connection is never closed here.
 */
public final class OrgStore {
    public record Tenant(String id, String name, long createdAt) {
    }

    public record Project(String id, String tenantId, String name, long createdAt) {
    }

    private final DatabaseContext database;

    public OrgStore(DatabaseContext database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void createTenant(String id, String name) {
        try {
            database.transaction(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tenant (id, name, created_at) VALUES (?,?,?)");
            try {
                statement.setString(1, id);
                statement.setString(2, name);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            } finally {
                statement.close();
            }

            return null;
            });
        } catch (SQLException failure) {
            if (SqliteErrors.isStorageFull(failure)) {
                throw new StorageLimitException("database or disk is full");
            }
            throw new IllegalArgumentException("duplicate or invalid tenant: " + id, failure);
        }
    }

    public List<Tenant> tenants() { return tenants(""); }

    public List<Tenant> tenants(String tenantId) {
        List<Tenant> out = new ArrayList<>();
        try {
            database.read(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, name, created_at FROM tenant WHERE (? = '' OR id = ?) ORDER BY created_at");
            statement.setString(1, tenantId);
            statement.setString(2, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new Tenant(rows.getString("id"), rows.getString("name"), rows.getLong("created_at")));
                }
            } finally {
                statement.close();
            }

            return null;
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read tenants", failure);
        }
        return out;
    }

    public void createProject(String id, String tenantId, String name) {
        try {
            database.transaction(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO project (id, tenant_id, name, created_at) VALUES (?,?,?,?)");
            try {
                statement.setString(1, id);
                statement.setString(2, tenantId);
                statement.setString(3, name);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            } finally {
                statement.close();
            }

            return null;
            });
        } catch (SQLException failure) {
            if (SqliteErrors.isStorageFull(failure)) {
                throw new StorageLimitException("database or disk is full");
            }
            throw new IllegalArgumentException("duplicate or invalid project: " + id, failure);
        }
    }

    public List<Project> projects() { return projects("", ""); }

    public List<Project> projects(String tenantId, String projectId) {
        List<Project> out = new ArrayList<>();
        try {
            database.read(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, tenant_id, name, created_at FROM project WHERE (? = '' OR tenant_id = ?) AND (? = '' OR id = ?) ORDER BY created_at");
            statement.setString(1, tenantId);
            statement.setString(2, tenantId);
            statement.setString(3, projectId);
            statement.setString(4, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new Project(rows.getString("id"), rows.getString("tenant_id"),
                            rows.getString("name"), rows.getLong("created_at")));
                }
            } finally {
                statement.close();
            }

            return null;
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read projects", failure);
        }
        return out;
    }
}
