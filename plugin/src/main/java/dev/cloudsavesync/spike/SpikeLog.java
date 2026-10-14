package dev.cloudsavesync.spike;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Writes spike lines to the server log and to spike-log.txt.
 *
 * <p>The file is written with an unbuffered {@link FileOutputStream}: every line is a single
 * write() system call, so it is in the OS page cache as soon as {@link #log} returns and
 * survives the process being killed (Task Manager, kill -9). Only a power loss could drop it.
 */
final class SpikeLog {

    private final long t0 = System.nanoTime();
    private final HytaleLogger logger;
    private final Path file;
    private FileOutputStream out;

    SpikeLog(HytaleLogger logger, Path dataDirectory) {
        this.logger = logger;
        this.file = dataDirectory.resolve("spike-log.txt").toAbsolutePath();
        try {
            Files.createDirectories(dataDirectory);
            this.out = new FileOutputStream(file.toFile(), true);
        } catch (IOException e) {
            logger.at(Level.WARNING).log("[SPIKE] cannot open " + file + ": " + e);
        }
    }

    Path file() {
        return file;
    }

    void log(String event, String details) {
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String line = "[SPIKE +" + ms + "ms " + Instant.now() + "] [" + Thread.currentThread().getName() + "] "
                + event + (details == null || details.isEmpty() ? "" : " " + details);
        logger.at(Level.INFO).log(line);
        writeLine(line);
    }

    /** Writes a line to the file only, for bulky probe output that would flood the server log. */
    void detail(String line) {
        writeLine("    " + line);
    }

    private synchronized void writeLine(String line) {
        if (out == null) {
            return;
        }
        try {
            out.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            logger.at(Level.WARNING).log("[SPIKE] write to " + file + " failed: " + e);
            out = null;
        }
    }
}
