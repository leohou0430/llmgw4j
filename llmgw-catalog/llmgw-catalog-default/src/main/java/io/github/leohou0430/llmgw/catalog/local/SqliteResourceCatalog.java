package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogCursorException;
import io.github.leohou0430.llmgw.catalog.CatalogPage;
import io.github.leohou0430.llmgw.catalog.PageRequest;
import io.github.leohou0430.llmgw.catalog.ProjectAuthorizer;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Durable {@link ResourceCatalog} over the {@code resource_catalog} table. The stored
 * key is {@code projectId + '\u0001' + gatewayId} so the same gateway id can exist in
 * two projects without colliding (cross-project isolation is still enforced by the
 * project-scoped queries and the authorization precheck). Ordering uses SQLite's
 * implicit {@code rowid}; a re-create deletes and re-inserts so the entry moves to
 * the end, matching the in-memory implementation. Pagination reuses {@link CursorCodec}
 * so pages are signed, scope-bound and high-water-marked exactly like the local store.
 */
public final class SqliteResourceCatalog implements ResourceCatalog {
    private static final char KEY_SEP = '\u0001';

    private final DatabaseContext database;
    private final ProjectAuthorizer authorizer;
    private final CursorCodec codec;

    public SqliteResourceCatalog(DatabaseContext database, ProjectAuthorizer authorizer) {
        this(database, authorizer, CursorCodec.random());
    }

    public SqliteResourceCatalog(DatabaseContext database, ProjectAuthorizer authorizer, CursorCodec codec) {
        this.database = Objects.requireNonNull(database, "database");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public ResourceEntry create(ResourceEntry entry) {
        Objects.requireNonNull(entry, "entry");
        authorizer.requireAuthorized(entry.projectId());
        mutate(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM resource_catalog WHERE id = ?")) {
                delete.setString(1, storedId(entry.projectId(), entry.gatewayId()));
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO resource_catalog (id, project_id, type, upstream_id, source_route, provider,"
                            + " config_version, created_at, observable_status, metadata, last_synced_at, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, storedId(entry.projectId(), entry.gatewayId()));
                insert.setString(2, entry.projectId());
                insert.setString(3, entry.type());
                insert.setString(4, entry.upstreamId() == null ? "" : entry.upstreamId());
                insert.setString(5, entry.sourceRoute());
                insert.setString(6, entry.provider() == null ? "" : entry.provider());
                insert.setString(7, entry.configVersion() == null ? "" : entry.configVersion());
                insert.setLong(8, entry.createdAt());
                insert.setString(9, entry.observableStatus() == null ? "" : entry.observableStatus());
                insert.setString(10, encodeMetadata(entry.metadata()));
                insert.setLong(11, entry.lastSyncedAt());
                insert.setInt(12, entry.deleted() ? 1 : 0);
                insert.executeUpdate();
            }
        });
        return entry;
    }

    @Override
    public Optional<ResourceEntry> get(String projectId, String gatewayId) {
        authorizer.requireAuthorized(projectId);
        try {
            return read(connection -> {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT rowid AS seq, * FROM resource_catalog WHERE id = ? AND project_id = ?")) {
                    select.setString(1, storedId(projectId, gatewayId));
                    select.setString(2, projectId);
                    try (ResultSet rows = select.executeQuery()) {
                        return rows.next() ? Optional.of(map(rows)) : Optional.<ResourceEntry>empty();
                    }
                }
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to read catalog entry", failure);
        }
    }

    @Override
    public boolean delete(String projectId, String gatewayId) {
        authorizer.requireAuthorized(projectId);
        int[] removed = new int[1];
        mutate(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM resource_catalog WHERE id = ? AND project_id = ?")) {
                delete.setString(1, storedId(projectId, gatewayId));
                delete.setString(2, projectId);
                removed[0] = delete.executeUpdate();
            }
        });
        return removed[0] > 0;
    }

    @Override
    public List<ResourceEntry> list(String projectId) {
        authorizer.requireAuthorized(projectId);
        try {
            return read(connection -> {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT rowid AS seq, * FROM resource_catalog WHERE project_id = ? AND deleted = 0"
                                + " ORDER BY rowid")) {
                    select.setString(1, projectId);
                    try (ResultSet rows = select.executeQuery()) {
                        List<ResourceEntry> out = new ArrayList<>();
                        while (rows.next()) {
                            out.add(map(rows));
                        }
                        return out;
                    }
                }
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to list catalog entries", failure);
        }
    }

    @Override
    public CatalogPage page(String tenantId, String projectId, PageRequest request, String cursor) {
        authorizer.requireAuthorized(projectId);
        Objects.requireNonNull(request, "request");
        long highWaterMark;
        long afterSequence;
        if (cursor == null || cursor.isEmpty()) {
            try {
                highWaterMark = read(connection -> {
                    try (PreparedStatement max = connection.prepareStatement(
                            "SELECT COALESCE(MAX(rowid), 0) FROM resource_catalog WHERE project_id = ?")) {
                        max.setString(1, projectId);
                        try (ResultSet row = max.executeQuery()) {
                            return row.next() ? row.getLong(1) : 0L;
                        }
                    }
                });
            } catch (SQLException failure) {
                throw new IllegalStateException("failed to read catalog high-water mark", failure);
            }
            afterSequence = 0L;
        } else {
            CursorCodec.Token token = codec.decode(cursor);
            if (!Objects.equals(token.tenantId(), tenantId)
                    || !Objects.equals(token.projectId(), projectId)
                    || !Objects.equals(token.typeFilter(), request.typeFilter())) {
                throw new CatalogCursorException("cursor is bound to a different tenant/project/filter");
            }
            highWaterMark = token.highWaterMark();
            afterSequence = token.lastSequence();
        }

        String sql = "SELECT rowid AS seq, * FROM resource_catalog"
                + " WHERE project_id = ? AND deleted = 0 AND rowid > ? AND rowid <= ?"
                + (request.typeFilter() == null ? "" : " AND type = ?")
                + " ORDER BY rowid LIMIT ?";
        List<ResourceEntry> items;
        List<Long> sequences = new ArrayList<>();
        try {
            items = read(connection -> {
                try (PreparedStatement select = connection.prepareStatement(sql)) {
                    int index = 1;
                    select.setString(index++, projectId);
                    select.setLong(index++, afterSequence);
                    select.setLong(index++, highWaterMark);
                    if (request.typeFilter() != null) {
                        select.setString(index++, request.typeFilter());
                    }
                    select.setInt(index, request.limit() + 1);
                    try (ResultSet rows = select.executeQuery()) {
                        List<ResourceEntry> out = new ArrayList<>();
                        while (rows.next()) {
                            out.add(map(rows));
                            sequences.add(rows.getLong("seq"));
                        }
                        return out;
                    }
                }
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to page catalog entries", failure);
        }
        if (items.size() <= request.limit()) {
            return new CatalogPage(items, null);
        }
        List<ResourceEntry> page = List.copyOf(items.subList(0, request.limit()));
        String next = codec.encode(new CursorCodec.Token(tenantId, projectId, request.typeFilter(),
                highWaterMark, sequences.get(request.limit() - 1)));
        return new CatalogPage(page, next);
    }

    private static String storedId(String projectId, String gatewayId) {
        return projectId + KEY_SEP + gatewayId;
    }

    private static ResourceEntry map(ResultSet rows) throws SQLException {
        String stored = rows.getString("id");
        int sep = stored.indexOf(KEY_SEP);
        String gatewayId = sep < 0 ? stored : stored.substring(sep + 1);
        return new ResourceEntry(
                gatewayId,
                rows.getString("project_id"),
                rows.getString("type"),
                rows.getString("upstream_id"),
                rows.getString("source_route"),
                rows.getString("provider"),
                rows.getString("config_version"),
                rows.getLong("created_at"),
                rows.getString("observable_status"),
                decodeMetadata(rows.getString("metadata")),
                rows.getLong("last_synced_at"),
                rows.getInt("deleted") != 0);
    }

    private static String encodeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        StringJoiner joined = new StringJoiner("&");
        metadata.forEach((key, value) -> joined.add(encode(key) + "=" + encode(value)));
        return joined.toString();
    }

    private static Map<String, String> decodeMetadata(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            out.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
        }
        return Map.copyOf(out);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void mutate(SqlBody body) {
        Connection connection;
        try {
            connection = database.connection();
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to open catalog connection", failure);
        }
        synchronized (connection) {
            boolean previousAutoCommit;
            try {
                previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
            } catch (SQLException failure) {
                throw new IllegalStateException("failed to begin catalog transaction", failure);
            }
            try {
                body.run(connection);
                connection.commit();
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    failure.addSuppressed(ignored);
                }
                throw new IllegalStateException("failed to persist catalog state", failure);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // transaction already finished; restore is best-effort
                }
            }
        }
    }

    private <T> T read(SqlRead<T> body) throws SQLException {
        Connection connection = database.connection();
        synchronized (connection) {
            return body.read(connection);
        }
    }

    @FunctionalInterface
    private interface SqlBody {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRead<T> {
        T read(Connection connection) throws SQLException;
    }
}
