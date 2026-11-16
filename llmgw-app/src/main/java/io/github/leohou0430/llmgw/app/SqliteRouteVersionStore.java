package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.RouteVersion;
import io.github.leohou0430.llmgw.control.RouteVersionStore;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, append-only route-version store backed by SQLite (the {@code route_version}
 * table from migration V3). Publishing survives process restart; {@code RouteVersionService}
 * recovers the committed version on construction (startup recovery). The shared
 * {@link DatabaseContext} connection is owned by the context and must NOT be closed here.
 */
public final class SqliteRouteVersionStore implements RouteVersionStore {
    private final DatabaseContext database;

    public SqliteRouteVersionStore(DatabaseContext database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<RouteVersion> latest() {
        return queryOne("SELECT version, config, base_version FROM route_version ORDER BY version DESC LIMIT 1", null);
    }

    @Override
    public Optional<RouteVersion> find(long version) {
        return queryOne("SELECT version, config, base_version FROM route_version WHERE version = ?", version);
    }

    @Override
    public List<RouteVersion> all() {
        List<RouteVersion> versions = new ArrayList<>();
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "SELECT version, config, base_version FROM route_version ORDER BY version");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    versions.add(read(rows));
                }
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read route versions", failure);
        }
        return versions;
    }

    @Override
    public void append(RouteVersion version) {
        try {
            PreparedStatement statement = database.connection().prepareStatement(
                    "INSERT OR REPLACE INTO route_version (version, config, base_version, created_at)"
                            + " VALUES (?,?,?,?)");
            try {
                statement.setLong(1, version.version());
                statement.setString(2, version.config());
                statement.setLong(3, version.baseVersion());
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to persist route version", failure);
        }
    }

    private Optional<RouteVersion> queryOne(String sql, Long parameter) {
        try {
            PreparedStatement statement = database.connection().prepareStatement(sql);
            try {
                if (parameter != null) {
                    statement.setLong(1, parameter);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(read(rows)) : Optional.empty();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read route version", failure);
        }
    }

    private static RouteVersion read(ResultSet rows) throws SQLException {
        return new RouteVersion(rows.getLong("version"), rows.getString("config"), rows.getLong("base_version"));
    }
}