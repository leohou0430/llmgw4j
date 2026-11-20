package io.github.leohou0430.llmgw.billing.sqlite;

import io.github.leohou0430.llmgw.billing.MissingPriceException;
import io.github.leohou0430.llmgw.billing.Price;
import io.github.leohou0430.llmgw.billing.PriceBook;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.sql.SQLException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite price book (B2/B10): per-model versions frozen at admission; a new freeze
 * inserts the next version for the model (versions are per-model monotonic). Reading a
 * frozen version is immutable history — price changes never rewrite past admissions.
 */
public final class SqlitePriceBook implements PriceBook {

	private final DatabaseContext database;

	public SqlitePriceBook(DatabaseContext database) {
		this.database = database;
	}

	private <T> T inTx(DatabaseContext.SqlWork<T> work) {
		try {
			return database.transaction(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("price book transaction failed", e);
		}
	}

	private <T> T inRead(DatabaseContext.SqlWork<T> work) {
		try {
			return database.read(work);
		}
		catch (SQLException e) {
			throw new IllegalStateException("price book read failed", e);
		}
	}

	@Override
	public Price freeze(String model) {
		long now = System.currentTimeMillis();
		return inTx(conn -> {
			long next;
			try (var query = conn
				.prepareStatement("SELECT COALESCE(MAX(version),0)+1 FROM price_version WHERE model = ?")) {
				query.setString(1, model);
				try (var rows = query.executeQuery()) {
					next = rows.next() ? rows.getLong(1) : 1;
				}
			}
			// First version of a model must be explicitly configured by the
			// operator before traffic is admitted; freeze() seeds from the
			// previous version so price progression stays auditable.
			long in;
			long out;
			long image;
			long task;
			long second;
			try (var prev = conn
				.prepareStatement("SELECT input_nano_per_million, output_nano_per_million, nano_per_image,"
						+ " nano_per_task, nano_per_second"
						+ " FROM price_version WHERE model = ? ORDER BY version DESC LIMIT 1")) {
				prev.setString(1, model);
				try (var rows = prev.executeQuery()) {
					if (rows.next()) {
						in = rows.getLong(1);
						out = rows.getLong(2);
						image = rows.getLong(3);
						task = rows.getLong(4);
						second = rows.getLong(5);
					}
					else {
						// 缺价拒绝合同：准入即 402，不静默记零（MissingPriceException）。
						throw new MissingPriceException(model, 0);
					}
				}
			}
			try (var insert = conn.prepareStatement("INSERT INTO price_version (model, version, input_nano_per_million,"
					+ " output_nano_per_million, nano_per_image, nano_per_task, nano_per_second,"
					+ " frozen_at) VALUES (?,?,?,?,?,?,?,?)")) {
				insert.setString(1, model);
				insert.setLong(2, next);
				insert.setLong(3, in);
				insert.setLong(4, out);
				insert.setLong(5, image);
				insert.setLong(6, task);
				insert.setLong(7, second);
				insert.setLong(8, now);
				insert.executeUpdate();
			}
			return new Price(next, model, in, out, now, image, task, second);
		});
	}

	/** Operator entry point: seed or update the latest price for a model. */
	public Price seed(String model, long inputNanoPerMillion, long outputNanoPerMillion) {
		return seed(model, inputNanoPerMillion, outputNanoPerMillion, 0, 0, 0);
	}

	/** Operator entry point with non-token unit prices (0 = 该单位未定价）。 */
	public Price seed(String model, long inputNanoPerMillion, long outputNanoPerMillion, long nanoPerImage,
			long nanoPerTask, long nanoPerSecond) {
		long now = System.currentTimeMillis();
		return inTx(conn -> {
			long next;
			try (var query = conn
				.prepareStatement("SELECT COALESCE(MAX(version),0)+1 FROM price_version WHERE model = ?")) {
				query.setString(1, model);
				try (var rows = query.executeQuery()) {
					next = rows.next() ? rows.getLong(1) : 1;
				}
			}
			try (var insert = conn.prepareStatement("INSERT INTO price_version (model, version, input_nano_per_million,"
					+ " output_nano_per_million, nano_per_image, nano_per_task, nano_per_second,"
					+ " frozen_at) VALUES (?,?,?,?,?,?,?,?)")) {
				insert.setString(1, model);
				insert.setLong(2, next);
				insert.setLong(3, inputNanoPerMillion);
				insert.setLong(4, outputNanoPerMillion);
				insert.setLong(5, nanoPerImage);
				insert.setLong(6, nanoPerTask);
				insert.setLong(7, nanoPerSecond);
				insert.setLong(8, now);
				insert.executeUpdate();
			}
			return new Price(next, model, inputNanoPerMillion, outputNanoPerMillion, now, nanoPerImage, nanoPerTask,
					nanoPerSecond);
		});
	}

	@Override
	public Optional<Price> priceFor(String model, long version) {
		return inRead(conn -> {
			try (var query = conn.prepareStatement("SELECT input_nano_per_million, output_nano_per_million, frozen_at,"
					+ " nano_per_image, nano_per_task, nano_per_second"
					+ " FROM price_version WHERE model = ? AND version = ?")) {
				query.setString(1, model);
				query.setLong(2, version);
				try (var rows = query.executeQuery()) {
					if (!rows.next()) {
						return Optional.<Price>empty();
					}
					return Optional.of(new Price(version, model, rows.getLong(1), rows.getLong(2), rows.getLong(3),
							rows.getLong(4), rows.getLong(5), rows.getLong(6)));
				}
			}
		});
	}

	@Override
	public List<Price> all() {
		return inRead(conn -> {
			var out = new ArrayList<Price>();
			try (var query = conn
				.prepareStatement("SELECT model, version, input_nano_per_million, output_nano_per_million,"
						+ " frozen_at, nano_per_image, nano_per_task, nano_per_second"
						+ " FROM price_version ORDER BY model, version DESC")) {
				try (var rows = query.executeQuery()) {
					while (rows.next()) {
						out.add(new Price(rows.getLong(2), rows.getString(1), rows.getLong(3), rows.getLong(4),
								rows.getLong(5), rows.getLong(6), rows.getLong(7), rows.getLong(8)));
					}
				}
			}
			return List.copyOf(out);
		});
	}

}
