package io.github.leohou0430.llmgw.core.multipart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * T2.6 per-process spool factory and quota owner. Each manager owns one
 * restricted directory and an independent byte quota; two managers never share
 * state (instance isolation). The constructor clears prior-run leftovers so a
 * crashed process cannot leak disk. Pure JDK, no engine.
 */
public final class SpoolManager implements AutoCloseable {

    /**
     * @param directory             restricted spool directory owned by this manager
     * @param maxTransferBytes      cap for a single transfer (plan: 66 MiB)
     * @param maxTotalBytes         per-process spool quota (plan: 264 MiB)
     * @param diskSafetyMarginBytes refuse new transfers below this usable space (plan: 64 MiB)
     */
    public record Config(Path directory, long maxTransferBytes, long maxTotalBytes,
                         long diskSafetyMarginBytes) {
        public static Config defaults(Path directory) {
            return new Config(directory, 66L << 20, 264L << 20, 64L << 20);
        }
    }

    private final Config config;
    private final AtomicLong total = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SpoolManager(Config config) throws IOException {
        this.config = config;
        Files.createDirectories(config.directory());
        restrictToOwner(config.directory());
        cleanLeftovers(config.directory());
    }

    private static void restrictToOwner(Path dir) throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(dir, ownerOnly);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem: directory privacy is best-effort.
        }
    }

    private static void cleanLeftovers(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    public Config config() {
        return config;
    }

    public Path directory() {
        return config.directory();
    }

    public long totalBytes() {
        return total.get();
    }

    /** Create a new empty spool, refusing up front if the disk margin is not met. */
    public Spool create() throws IOException {
        if (closed.get()) {
            throw new IOException("spool manager closed");
        }
        long usable = Files.getFileStore(config.directory()).getUsableSpace();
        if (usable < config.diskSafetyMarginBytes()) {
            throw new SpoolLimitException(SpoolLimitException.Reason.DISK,
                    "usable disk " + usable + " below safety margin " + config.diskSafetyMarginBytes());
        }
        Path file = Files.createTempFile(config.directory(), "spool-", ".tmp");
        return new Spool(this, file);
    }

    /** Reserve quota; rolls back and throws TOTAL if the per-process cap would be exceeded. */
    void reserve(long n) throws SpoolLimitException {
        long now = total.addAndGet(n);
        if (now > config.maxTotalBytes()) {
            total.addAndGet(-n);
            throw new SpoolLimitException(SpoolLimitException.Reason.TOTAL,
                    "spool quota " + config.maxTotalBytes() + " exceeded by " + n + " bytes");
        }
    }

    void release(long n) {
        total.addAndGet(-n);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try (Stream<Path> entries = Files.list(config.directory())) {
                for (Path p : entries.toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException ignored) {
                // Best-effort directory cleanup on shutdown.
            }
        }
    }
}