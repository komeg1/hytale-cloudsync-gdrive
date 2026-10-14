package dev.cloudsavesync.plugin;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import dev.cloudsavesync.core.CloudState;
import dev.cloudsavesync.core.SyncEngine;
import dev.cloudsavesync.core.local.DeviceInfo;
import dev.cloudsavesync.core.local.HelperStatus;
import dev.cloudsavesync.core.local.LocalState;
import dev.cloudsavesync.core.local.SyncSettings;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.storage.StorageException;
import dev.cloudsavesync.gdrive.GoogleAuth;
import dev.cloudsavesync.plugin.CloudSyncPlugin.Mode;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * {@code /cloudsync <login|code|logout|status|enable|disable|list|download|upload|auto|keeplocal|usecloud>}. Only the world's owner may use it:
 * in a world opened to friends, guests can't sign it in to their own Drive or switch versions.
 */
final class CloudSyncCommand extends AbstractCommandCollection {

    private final CloudSyncPlugin plugin;

    CloudSyncCommand(CloudSyncPlugin plugin) {
        super("cloudsync", "Sync this world with your Google Drive");
        this.plugin = plugin;
        requireNoPermission();
        sub("status", "Show the sync status of this world", this::status);
        sub("login", "Sign in to Google Drive", this::login);
        sub("code", "Sign in to Google Drive with a code (for devices without a browser)", this::code);
        sub("logout", "Sign out of Google Drive on this device", this::logout);
        sub("enable", "Start syncing this world with your Google Drive", (ctx, out) -> {
            if (requireSignIn(ctx)) {
                plugin.io.execute(plugin::enableSync);
            }
        });
        sub("disable", "Stop syncing this world on this device (its cloud copy stays)", (ctx, out) ->
                plugin.io.execute(plugin::disableSync));
        sub("list", "List your worlds in Google Drive", (ctx, out) -> {
            if (requireSignIn(ctx)) {
                plugin.io.execute(plugin::listCloudWorlds);
            }
        });
        sub("upload", "Back this world up to Google Drive now", this::syncNow);
        sub("keeplocal", "Resolve a conflict by keeping this device's version", this::keepLocal);
        sub("usecloud", "Resolve a conflict by switching to the cloud version at next load", this::useCloud);
        addSubCommand(new AutoCommand());
        addSubCommand(new DownloadCommand());
    }

    private void sub(String name, String description, BiConsumer<CommandContext, PlayerMessages> handler) {
        CommandBase c = new CommandBase(name, description) {
            @Override
            protected void executeSync(@Nonnull CommandContext ctx) {
                if (ctx.isPlayer() && !isOwner(ctx)) {
                    reply(ctx, "Only the world's owner can manage cloud sync.");
                    return;
                }
                if (plugin.mode == Mode.DISABLED) {
                    reply(ctx, "Cloud sync is off: " + plugin.disabledReason + ".");
                    return;
                }
                handler.accept(ctx, plugin.messages());
            }
        };
        c.requireNoPermission();
        addSubCommand(c);
    }

    private static boolean isOwner(CommandContext ctx) {
        try {
            return SingleplayerModule.isOwner(ctx.sender().getUuid());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean requireSignIn(CommandContext ctx) {
        if (plugin.services.auth().isSignedIn()) {
            return true;
        }
        reply(ctx, "Sign in first: /cloudsync login");
        return false;
    }

    private static void reply(CommandContext ctx, String text) {
        ctx.sendMessage(PlayerMessages.line(PlayerMessages.ACCENT, text));
    }

    // ------------------------------------------------------------------ sign-in

    private void login(CommandContext ctx, PlayerMessages out) {
        if (plugin.services.auth().isSignedIn()) {
            reply(ctx, "Already signed in. Use /cloudsync logout first to switch accounts.");
            return;
        }
        if (DeviceInfo.isSteamDeck() || !plugin.services.auth().supportsBrowserSignIn()) {
            code(ctx, out);
            return;
        }
        plugin.authPool.execute(() -> {
            try {
                GoogleAuth.BrowserSignIn s = plugin.services.auth().startBrowserSignIn();
                boolean opened = BrowserOpener.open(s.url());
                out.send(Message.join(PlayerMessages.line(PlayerMessages.ACCENT, opened
                        ? "A browser window opened to sign in with Google. If you don't see it, open: "
                        : "Open this link to sign in with Google: "), PlayerMessages.link(s.url())), "sign-in link");
                out.info("No browser on this device? Type /cloudsync code instead.");
                s.result().whenComplete((ok, err) -> signInFinished(err, out));
            } catch (IOException | RuntimeException e) {
                out.error("Couldn't start sign-in (" + e.getMessage() + "). Try /cloudsync code instead.");
            }
        });
    }

    private void code(CommandContext ctx, PlayerMessages out) {
        if (plugin.services.auth().isSignedIn()) {
            reply(ctx, "Already signed in. Use /cloudsync logout first to switch accounts.");
            return;
        }
        reply(ctx, "Getting a sign-in code...");
        plugin.authPool.execute(() -> {
            try {
                GoogleAuth.CodeSignIn s = plugin.services.auth().startCodeSignIn();
                out.send(Message.join(PlayerMessages.line(PlayerMessages.ACCENT,
                        "On your phone or computer, go to "), PlayerMessages.link(s.verificationUrl()),
                        Message.raw(" and enter the code ")), "device code instructions");
                out.send(PlayerMessages.line(PlayerMessages.ACCENT, s.userCode()).bold(true), "code " + s.userCode());
                out.info("The code works for " + s.expiresIn().toMinutes() + " minutes. This chat will tell you when"
                        + " you're signed in.");
                s.result().whenComplete((ok, err) -> signInFinished(err, out));
            } catch (IOException | RuntimeException e) {
                out.error("Couldn't get a sign-in code (" + CloudSyncPlugin.rootMessage(e) + "). Check your connection"
                        + " and try again.");
            }
        });
    }

    private void signInFinished(Throwable err, PlayerMessages out) {
        if (err == null) {
            out.info("Signed in to Google Drive. Your worlds go in the \"Hytale Cloud Saves\" folder;"
                    + " the mod can't see anything else in your Drive.");
            plugin.afterSignIn();
        } else if (!(err instanceof java.util.concurrent.CancellationException)) {
            out.error("Sign-in didn't complete: " + CloudSyncPlugin.rootMessage(err));
        }
    }

    private void logout(CommandContext ctx, PlayerMessages out) {
        plugin.authPool.execute(() -> {
            try {
                plugin.services.auth().signOut();
                plugin.mode = Mode.SIGNED_OUT;
                out.info("Signed out on this device. Your cloud saves stay in your Google Drive.");
            } catch (IOException e) {
                out.error("Sign-out failed: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ status and sync

    private void status(CommandContext ctx, PlayerMessages out) {
        SyncEngine engine = plugin.engine;
        try {
            LocalState s = engine.localState();
            reply(ctx, "World '" + plugin.paths.worldKey() + "' on " + engine.device().deviceName() + ". "
                    + (plugin.services.auth().isSignedIn() ? "Signed in to Google Drive." : "Not signed in."));
            reply(ctx, s.lastSyncAt() == null ? "Never synced from this device."
                    : "Last synced " + PlayerMessages.ago(s.lastSyncAt(), Instant.now()) + " (cloud revision "
                    + s.lastSyncedRevision() + ").");
            reply(ctx, switch (plugin.mode) {
                case CONFLICT -> "Waiting for you to choose: /cloudsync usecloud or /cloudsync keeplocal.";
                case SWITCH_PENDING -> "The cloud version will replace this world when you reopen it.";
                case SIGNED_OUT -> "Type /cloudsync login to start syncing.";
                case NOT_SYNCED -> "This world isn't synced on this device. Type /cloudsync enable to sync it.";
                default -> plugin.settings.autoUpload()
                        ? "Changes are uploaded every " + plugin.settings.autoUploadMinutes()
                        + " minutes and when you leave the world (/cloudsync auto to change)."
                        : "Uploads while playing are off; the world is uploaded when you leave it"
                        + " (/cloudsync auto on to turn them back on).";
            });
            reply(ctx, "Type /cloudsync list to see all your worlds in Google Drive.");
            HelperStatus.read(plugin.paths.helperStatusFile()).ifPresent(h ->
                    reply(ctx, "Last upload after leaving: " + h.phase().name().toLowerCase().replace('_', ' ')
                            + " - " + h.message() + " (" + PlayerMessages.ago(h.updatedAt(), Instant.now()) + ")."));
        } catch (IOException e) {
            reply(ctx, "Couldn't read the sync status: " + e.getMessage());
            return;
        }
        if (!plugin.services.auth().isSignedIn()) {
            return;
        }
        plugin.io.execute(() -> {
            try {
                CloudState cloud = engine.fetch();
                if (cloud.manifest() == null || cloud.manifest().head().isEmpty()) {
                    out.info("This world isn't in your Google Drive yet.");
                    return;
                }
                SnapshotInfo head = cloud.manifest().head().get();
                long versions = cloud.manifest().snapshots().size();
                out.info("Cloud: current version from " + head.deviceName() + ", "
                        + PlayerMessages.ago(head.createdAt(), Instant.now()) + " ("
                        + PlayerMessages.megabytes(head.sizeBytes()) + "); " + versions + " versions kept.");
            } catch (IOException e) {
                out.warn("Couldn't reach Google Drive: " + e.getMessage());
            }
        });
    }

    private void syncNow(CommandContext ctx, PlayerMessages out) {
        switch (plugin.mode) {
            case SIGNED_OUT -> {
                reply(ctx, "Sign in first: /cloudsync login");
                return;
            }
            case NOT_SYNCED -> {
                reply(ctx, "This world isn't synced. Type /cloudsync enable to start syncing it.");
                return;
            }
            case CONFLICT -> {
                reply(ctx, "Choose a version first: /cloudsync usecloud or /cloudsync keeplocal.");
                return;
            }
            case SWITCH_PENDING -> {
                reply(ctx, "Reopen the world to switch to the cloud version first.");
                return;
            }
            default -> {
            }
        }
        reply(ctx, "Uploading this world...");
        int[] lastQuarter = {0};
        plugin.io.execute(() -> plugin.upload(SyncEngine.REASON_MANUAL, (done, total) -> {
            int quarter = total <= 0 ? 0 : (int) (4 * done / total);
            if (quarter > lastQuarter[0] && quarter < 4) {
                lastQuarter[0] = quarter;
                out.info("Uploading... " + quarter * 25 + "%");
            }
        }, true));
    }

    // ------------------------------------------------------------------ settings

    /** {@code /cloudsync auto <on|off|minutes>}: uploads while playing. */
    private final class AutoCommand extends CommandBase {
        private final RequiredArg<String> value = withRequiredArg("setting",
                "on, off, or how many minutes between uploads while playing (e.g. 10)", ArgTypes.STRING);

        AutoCommand() {
            super("auto", "Turn uploads while playing on/off or set how often they happen");
            requireNoPermission();
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            if (ctx.isPlayer() && !isOwner(ctx)) {
                reply(ctx, "Only the world's owner can manage cloud sync.");
                return;
            }
            if (plugin.mode == Mode.DISABLED) {
                reply(ctx, "Cloud sync is off: " + plugin.disabledReason + ".");
                return;
            }
            String v = ctx.get(value).trim().toLowerCase(java.util.Locale.ROOT);
            SyncSettings current = plugin.settings;
            SyncSettings next;
            if (v.equals("off") || v.equals("0")) {
                next = current.withAutoUpload(false);
            } else if (v.equals("on")) {
                next = current.withAutoUpload(true);
            } else {
                int minutes;
                try {
                    minutes = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    reply(ctx, "Use /cloudsync auto on, /cloudsync auto off, or a number of minutes like"
                            + " /cloudsync auto 10.");
                    return;
                }
                if (minutes < SyncSettings.MIN_MINUTES || minutes > SyncSettings.MAX_MINUTES) {
                    reply(ctx, "Pick between " + SyncSettings.MIN_MINUTES + " and " + SyncSettings.MAX_MINUTES
                            + " minutes.");
                    return;
                }
                next = current.withMinutes(minutes);
            }
            try {
                plugin.updateSettings(next);
            } catch (IOException e) {
                reply(ctx, "Couldn't save the setting: " + e.getMessage());
                return;
            }
            reply(ctx, next.autoUpload()
                    ? "Uploads while playing: every " + next.autoUploadMinutes() + " minutes (all worlds on this"
                    + " device). Your world is also uploaded when you leave it."
                    : "Uploads while playing: off (all worlds on this device). Your world is still uploaded when"
                    + " you leave it.");
        }
    }

    /** {@code /cloudsync download 1,3}: download worlds by their number in {@code /cloudsync list}. */
    private final class DownloadCommand extends CommandBase {
        private final RequiredArg<String> numbers = withRequiredArg("numbers",
                "the numbers from /cloudsync list, e.g. 2 or 1,3, or all", ArgTypes.GREEDY_STRING);

        DownloadCommand() {
            super("download", "Download worlds from your Google Drive by their number in /cloudsync list");
            requireNoPermission();
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            if (ctx.isPlayer() && !isOwner(ctx)) {
                reply(ctx, "Only the world's owner can manage cloud sync.");
                return;
            }
            if (plugin.mode == Mode.DISABLED) {
                reply(ctx, "Cloud sync is off: " + plugin.disabledReason + ".");
                return;
            }
            if (!requireSignIn(ctx)) {
                return;
            }
            String spec = ctx.get(numbers);
            plugin.io.execute(() -> plugin.downloadCloudWorlds(spec));
        }
    }

    // ------------------------------------------------------------------ conflict resolution

    private void keepLocal(CommandContext ctx, PlayerMessages out) {
        if (plugin.mode != Mode.CONFLICT) {
            reply(ctx, "There's no conflict to resolve right now.");
            return;
        }
        plugin.io.execute(() -> {
            try {
                CloudState cloud = plugin.engine.fetch();
                String other = cloud.manifest() == null ? "the other device"
                        : cloud.manifest().head().map(SnapshotInfo::deviceName).orElse("the other device");
                plugin.engine.chooseLocal(cloud);
                plugin.mode = Mode.NORMAL;
                out.info("Keeping this device's version. The version from " + other
                        + " stays in your cloud history. Uploading now...");
                plugin.upload(SyncEngine.REASON_MANUAL, (d, t) -> {
                }, true);
            } catch (IOException e) {
                out.error("Couldn't update the cloud (" + e.getMessage() + "). Nothing changed; try again.");
            }
        });
    }

    private void useCloud(CommandContext ctx, PlayerMessages out) {
        if (plugin.mode != Mode.CONFLICT) {
            reply(ctx, "There's no conflict to resolve right now.");
            return;
        }
        plugin.io.execute(() -> {
            try {
                CloudState cloud = plugin.engine.fetch();
                plugin.engine.chooseCloud(cloud);
                plugin.mode = Mode.SWITCH_PENDING;
                String other = cloud.manifest().head().map(SnapshotInfo::deviceName).orElse("the cloud");
                out.info("OK. Quit to the menu and reopen this world to switch to the version from " + other
                        + ". This device's current version will be kept as a local backup.");
            } catch (StorageException e) {
                out.error("Couldn't read the cloud version (" + e.getMessage() + "). Nothing changed; try again.");
            } catch (IOException e) {
                out.error("Couldn't save your choice (" + e.getMessage() + "). Nothing changed; try again.");
            }
        });
    }
}
