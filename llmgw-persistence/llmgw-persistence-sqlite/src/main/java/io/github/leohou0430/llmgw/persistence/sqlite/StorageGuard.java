package io.github.leohou0430.llmgw.persistence.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Disk protection for the catalog database. Callers run {@link #requireWritable()} before
 * any write that needs persistence: when the database file exceeds its size cap or free
 * disk falls under the safety margin, the write is rejected (audit and active resources
 * are never silently lost or deleted).
 */
public final class StorageGuard {
    private final long maxDatabaseBytes;
    private final long minFreeDiskBytes;
    private final Path databaseFile;

    public StorageGuard(long maxDatabaseBytes, long minFreeDiskBytes, Path databaseFile) {
        this.maxDatabaseBytes = maxDatabaseBytes;
        this.minFreeDiskBytes = minFreeDiskBytes;
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile");
    }

    public void requireWritable() { requireWritable(0); }

    public void requireWritable(long additionalBytes) {
        if (additionalBytes < 0) throw new IllegalArgumentException("negative storage reservation");
        try {
            long size = 0;
            try {
                for (String suffix : new String[]{"", "-wal", "-shm"}) {
                    Path part = Path.of(databaseFile.toString() + suffix);
                    size = Math.addExact(size, Files.exists(part) ? Files.size(part) : 0);
                }
                size = Math.addExact(size, additionalBytes);
            } catch (ArithmeticException overflow) { throw new StorageLimitException("database size overflow"); }
            if (size > maxDatabaseBytes) {
                throw new StorageLimitException(
                        "database size " + size + " exceeds cap " + maxDatabaseBytes);
            }
            Path directory = databaseFile.toAbsolutePath().getParent();
            long free = Files.getFileStore(directory).getUsableSpace();
            if (free < minFreeDiskBytes || additionalBytes > free - minFreeDiskBytes) {
                throw new StorageLimitException(
                        "free disk " + free + " is below margin " + minFreeDiskBytes);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to check storage limits for " + databaseFile, failure);
        }
    }
}
