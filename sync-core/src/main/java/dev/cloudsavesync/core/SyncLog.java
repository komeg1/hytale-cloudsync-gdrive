package dev.cloudsavesync.core;

/** Logging sink so the core doesn't depend on Hytale's logger or the helper's log file. */
public interface SyncLog {

    void info(String message);

    void warn(String message, Throwable error);

    default void warn(String message) {
        warn(message, null);
    }
}
