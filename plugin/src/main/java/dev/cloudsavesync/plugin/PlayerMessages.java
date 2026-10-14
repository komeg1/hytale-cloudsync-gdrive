package dev.cloudsavesync.plugin;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chat messages to the world's owner. Messages produced before the player is in the world (during
 * load-time sync) are queued and shown when the player is ready. Every message is also logged.
 */
final class PlayerMessages {

    static final String ACCENT = "#5fa8ff";
    static final String WARN = "#ffb347";
    static final String ERROR = "#ff6b6b";

    private final PluginSyncLog log;
    private final List<Message> queued = new ArrayList<>();
    private boolean ready;

    PlayerMessages(PluginSyncLog log) {
        this.log = log;
    }

    static Message line(String color, String text) {
        return Message.join(Message.raw("[Cloud Sync] ").color(color), Message.raw(text));
    }

    static Message link(String url) {
        return Message.raw(url).link(url).color(ACCENT);
    }

    void info(String text) {
        send(line(ACCENT, text), text);
    }

    void warn(String text) {
        send(line(WARN, text), text);
    }

    void error(String text) {
        send(line(ERROR, text), text);
    }

    synchronized void send(Message message, String plainForLog) {
        log.info("[to player] " + plainForLog);
        PlayerRef owner = ready ? owner() : null;
        if (owner == null) {
            queued.add(message);
            return;
        }
        owner.sendMessage(message);
    }

    synchronized void playerReady() {
        ready = true;
        PlayerRef owner = owner();
        if (owner == null) {
            return;
        }
        for (Message m : queued) {
            owner.sendMessage(m);
        }
        queued.clear();
    }

    synchronized void playerLeft() {
        ready = false;
    }

    private static PlayerRef owner() {
        try {
            UUID uuid = SingleplayerModule.getUuid();
            Universe u = Universe.get();
            return uuid == null || u == null ? null : u.getPlayer(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String ago(Instant then, Instant now) {
        if (then == null) {
            return "at an unknown time";
        }
        Duration d = Duration.between(then, now);
        if (d.isNegative() || d.toMinutes() < 1) {
            return "just now";
        }
        if (d.toMinutes() < 60) {
            return d.toMinutes() + " min ago";
        }
        if (d.toHours() < 48) {
            return d.toHours() + (d.toHours() == 1 ? " hour ago" : " hours ago");
        }
        return d.toDays() + " days ago";
    }

    static String megabytes(long bytes) {
        return bytes < 1024 * 1024 ? "<1 MB" : (bytes / (1024 * 1024)) + " MB";
    }
}
