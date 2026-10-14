package dev.cloudsavesync.core.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Crash-safe small-file writes: write a temp file, fsync, then rename over the target. */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void writeString(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        move(tmp, target);
    }

    public static String readString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Replaces {@code target} with {@code source}, atomically where the file system allows. */
    public static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (NoSuchFileException ignored) {
            // already gone
        } catch (IOException e) {
            // best effort; callers only use this for temp files
        } catch (UncheckedIOException ignored) {
            // best effort
        }
    }
}
