package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.persistence.DatabaseContext;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Consistent online backup of a live SQLite database. {@code VACUUM INTO} writes a
 * transactionally-consistent snapshot (safe while the database is live, WAL included) to
 * a new file; the resulting file is a normal, restorable SQLite database. Restore is
 * opening the backup as a DatabaseContext. (OS-level kill / real full-disk /
 * root-key-missing tests are separate ops experiments; not claimed here.)
 */
public final class OnlineBackup {

	private OnlineBackup() {
	}

	public static void backup(DatabaseContext source, Path targetFile) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(targetFile, "targetFile");
		String escaped = targetFile.toAbsolutePath().toString().replace("'", "''");
		try {
			source.read(connection -> {
				if (!connection.getAutoCommit())
					throw new SQLException("backup requires an idle transaction boundary");
				try (Statement statement = connection.createStatement()) {
					statement.execute("VACUUM INTO '" + escaped + "'");
				}
				return null;
			});
		}
		catch (SQLException failure) {
			throw new IllegalStateException("online backup failed: " + targetFile, failure);
		}
	}

	/**
	 * Task-9 backup with a tamper-evident manifest: after {@code VACUUM INTO}, write
	 * {@code <target>.manifest.json} carrying the SHA-256 of the backup file.
	 * {@link #verifyManifest} recomputes the digest before any restore; a missing or
	 * mismatching manifest keeps the restore gate CLOSED.
	 */
	public static Path backupWithManifest(DatabaseContext source, Path targetFile) {
		backup(source, targetFile);
		Path manifest = manifestPath(targetFile);
		String sha = sha256(targetFile);
		long now = System.currentTimeMillis();
		String json = "{\"file\":\"" + targetFile.getFileName() + "\",\"sha256\":\"" + sha + "\",\"createdAtEpochMs\":"
				+ now + "}";
		try {
			java.nio.file.Files.writeString(manifest, json);
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException("cannot write backup manifest: " + manifest, e);
		}
		return manifest;
	}

	public static Path manifestPath(Path backupFile) {
		return backupFile.resolveSibling(backupFile.getFileName() + ".manifest.json");
	}

	/** Extracts the recorded digest from the manifest JSON (self-authored shape). */
	private static String recordedDigest(Path manifest) {
		try {
			String json = java.nio.file.Files.readString(manifest);
			String key = "\"sha256\":\"";
			int start = json.indexOf(key);
			if (start < 0) {
				throw new IllegalStateException("manifest lacks sha256: " + manifest);
			}
			int from = start + key.length();
			int to = json.indexOf('"', from);
			return json.substring(from, to);
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(
					"backup manifest missing or unreadable: " + manifest + " (restore gate stays closed)", e);
		}
	}

	/**
	 * Manifest verification gate: fails when the manifest is missing, unreadable, or the
	 * digest does not match the backup bytes. Restores MUST call this before opening the
	 * backup; a failure keeps the recovery gate CLOSED.
	 */
	public static void verifyManifest(Path backupFile) {
		Path manifest = manifestPath(backupFile);
		String recorded = recordedDigest(manifest);
		String actual = sha256(backupFile);
		if (!recorded.equals(actual)) {
			throw new IllegalStateException("backup digest mismatch: manifest " + recorded + " vs actual " + actual
					+ " (restore gate stays closed)");
		}
	}

	private static String sha256(Path file) {
		try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			StringBuilder hex = new StringBuilder();
			for (byte b : digest.digest()) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (Exception e) {
			throw new IllegalStateException("cannot hash backup: " + file, e);
		}
	}

}