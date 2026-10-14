package dev.cloudsavesync.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.cloudsavesync.core.SyncLog;

import java.util.logging.Level;

/** Routes sync-core logging into the server log. */
final class PluginSyncLog implements SyncLog {

    private final HytaleLogger logger;

    PluginSyncLog(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.at(Level.INFO).log(message);
    }

    @Override
    public void warn(String message, Throwable error) {
        if (error == null) {
            logger.at(Level.WARNING).log(message);
        } else {
            logger.at(Level.WARNING).withCause(error).log(message);
        }
    }
}
