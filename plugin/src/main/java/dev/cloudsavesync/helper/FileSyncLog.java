package dev.cloudsavesync.helper;

import dev.cloudsavesync.core.SyncLog;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The helper's log. Unbuffered, one write() per line, so if the helper is killed the last line
 * written shows how far it got. Rotated at 1 MB, keeping one previous file.
 */
final class FileSyncLog implements SyncLog, AutoCloseable {

    private static final long MAX_BYTES = 1024 * 1024;

    private final FileOutputStream out;

    FileSyncLog(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        out = new FileOutputStream(file.toFile(), true);
    }

    @Override
    public synchronized void info(String message) {
        write("INFO  " + message);
    }

    @Override
    public synchronized void warn(String message, Throwable error) {
        if (error == null) {
            write("WARN  " + message);
            return;
        }
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        write("WARN  " + message + ": " + error + System.lineSeparator() + sw);
    }

    private void write(String line) {
        try {
            out.write(("[" + Instant.now() + "] [pid " + ProcessHandle.current().pid() + "] " + line
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nowhere left to report it
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
