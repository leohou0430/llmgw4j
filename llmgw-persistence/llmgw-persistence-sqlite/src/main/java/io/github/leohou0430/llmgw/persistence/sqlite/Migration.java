package io.github.leohou0430.llmgw.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One ordered, versioned schema step. Migrations are applied in ascending
 * {@link #version()} order; each runs inside its own transaction so a failure rolls the
 * whole step back and never advances {@code schema_version}.
 */
public interface Migration {

	/**
	 * Strictly increasing version; the migrator applies every version above the recorded
	 * one.
	 */
	int version();

	/** Applies this step's DDL/DML to the given (transactional) connection. */
	void apply(Connection connection) throws SQLException;

}