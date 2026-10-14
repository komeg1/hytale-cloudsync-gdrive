package dev.cloudsavesync.core.local;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Cross-process lock for one world's sync work (reading the save folder, uploading, restoring).
 * Held by the upload helper or by the plugin. The OS releases it if the holder dies, so a killed
 * helper can never leave a world locked.
 */
public final class LocalSyncLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private LocalSyncLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Optional<LocalSyncLock> tryAcquire(Path lockFile) throws IOException {
        Files.createDirectories(lockFile.getParent());
        FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock l = ch.tryLock();
            if (l == null) {
                ch.close();
                return Optional.empty();
            }
            return Optional.of(new LocalSyncLock(ch, l));
        } catch (OverlappingFileLockException heldInThisJvm) {
            ch.close();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /** Polls until acquired or {@code timeout} passes. */
    public static Optional<LocalSyncLock> acquire(Path lockFile, Duration timeout) throws IOException,
            InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<LocalSyncLock> l = tryAcquire(lockFile);
            if (l.isPresent() || System.nanoTime() >= deadline) {
                return l;
            }
            Thread.sleep(250);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
