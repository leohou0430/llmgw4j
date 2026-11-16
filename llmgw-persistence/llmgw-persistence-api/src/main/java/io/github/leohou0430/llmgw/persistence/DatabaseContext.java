package io.github.leohou0430.llmgw.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns one database engine instance and the resources behind it. Implementations
 * are {@link AutoCloseable} so an {@code ExtensionContext} that resolves one takes
 * ownership and releases it on runtime close. Each context is isolated: closing or
 * mutating one never affects another.
 *
 * <p>The JDBC {@link Connection} is the only seam exposed; callers run short
 * transactions on it and must not hold it across event-loop turns. Engines that
 * block (SQLite) are bridged off the reactive event loop by the data planes.
 */
public interface DatabaseContext extends AutoCloseable {

    /** The live connection for short transactions; never null while open. */
    Connection connection() throws SQLException;

    /** Releases the connection and any engine resources. Idempotent. */
    @Override
    void close();
}