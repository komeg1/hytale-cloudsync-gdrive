package dev.cloudsavesync.helper;

import dev.cloudsavesync.core.SyncEngine;
import dev.cloudsavesync.core.archive.SnapshotArchiver;
import dev.cloudsavesync.core.local.AtomicFiles;
import dev.cloudsavesync.core.local.HelperStatus;
import dev.cloudsavesync.core.local.HelperStatus.Phase;
import dev.cloudsavesync.core.local.LocalSyncLock;
import dev.cloudsavesync.core.local.SyncSettings;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.CloudLayout;
import dev.cloudsavesync.core.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Detached process started by the plugin at server shutdown. Waits for the server process to exit,
 * then zips the world straight from the save folder and uploads it. No time limit, no staging copy.
 * Shows desktop notifications when the upload starts and how it ended (see {@link DesktopNotifier}).
 *
 * <p>Holds the world's {@link LocalSyncLock} the whole time, so a quick re-open of the world waits
 * while the save is being read (the plugin checks {@link HelperStatus}). Logs a heartbeat to
 * helper.log every few seconds: after a kill, the last line shows how long it survived.
 *
 * <p>Must not reference any Hytale class: it runs with only the plugin jar on the classpath.
 */
public final class UploadHelper {

    private static final Duration SERVER_EXIT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LOCK_TIMEOUT = Duration.ofMinutes(2);

    private UploadHelper() {
    }

    public static void main(String[] args) {
        Map<String, String> a = parseArgs(args);
        Path saveDir = Path.of(a.get("--save-dir"));
        Path stateRoot = Path.of(a.get("--state-root"));
        long serverPid = Long.parseLong(a.get("--server-pid"));
        String sessionId = a.get("--session-id");
        String launchMode = a.getOrDefault("--launch-mode", "?");
        WorldPaths paths = WorldPaths.of(saveDir, stateRoot);

        int exit;
        try (FileSyncLog log = new FileSyncLog(paths.helperLogFile())) {
            exit = run(paths, serverPid, sessionId, launchMode, log);
        } catch (IOException e) {
            exit = 3;
        }
        System.exit(exit);
    }

    static int run(WorldPaths paths, long serverPid, String sessionId, String launchMode, FileSyncLog log) {
        ProcessHandle self = ProcessHandle.current();
        Instant started = Instant.now();
        HelperStatus[] status = {new HelperStatus(self.pid(), serverPid, Phase.STARTING, launchMode, started, started,
                "started")};
        log.info("helper started: world='" + paths.worldKey() + "' serverPid=" + serverPid + " launch=" + launchMode
                + " parent=" + self.parent().map(p -> Long.toString(p.pid())).orElse("none")
                + " java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name"));

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "helper-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long[] uploaded = {0, -1};
        heartbeat.scheduleAtFixedRate(() -> log.info("alive: phase=" + status[0].phase()
                + " serverAlive=" + ProcessHandle.of(serverPid).map(ProcessHandle::isAlive).orElse(false)
                + " parent=" + self.parent().map(p -> p.pid() + (p.isAlive() ? "" : "(dead)")).orElse("none")
                + (uploaded[1] > 0 ? " uploaded=" + uploaded[0] / (1024 * 1024) + "/" + uploaded[1] / (1024 * 1024) + " MB"
                : "")), 5, 5, TimeUnit.SECONDS);

        Optional<LocalSyncLock> lock = Optional.empty();
        Path zipPath = null;
        String world = "'" + paths.worldKey() + "'";
        DesktopNotifier notifier = new DesktopNotifier(SyncSettings.load(paths.stateRoot()).notifications(),
                CloudLayout.encode(paths.worldKey()), log);
        try {
            status[0] = update(paths, log, status[0], Phase.WAITING_FOR_SERVER, "waiting for the server to exit");
            if (!waitForExit(serverPid, log)) {
                status[0] = update(paths, log, status[0], Phase.FAILED, "server did not exit in time; upload retried next session");
                notifier.show("Couldn't upload " + world, "The game didn't close in time. It will be uploaded next time you play.");
                return 1;
            }

            lock = LocalSyncLock.acquire(paths.syncLockFile(), LOCK_TIMEOUT);
            if (lock.isEmpty()) {
                status[0] = update(paths, log, status[0], Phase.SKIPPED, "another sync of this world is running");
                return 0;
            }

            Optional<SyncServices> services = SyncServices.create(paths.stateRoot(), Runnable::run);
            if (services.isEmpty() || !services.get().auth().isSignedIn()) {
                status[0] = update(paths, log, status[0], Phase.SKIPPED, "not signed in; world stays marked unsynced");
                return 0;
            }
            SyncEngine engine = services.get().engine(paths, log);

            status[0] = update(paths, log, status[0], Phase.READING_SAVE, "zipping the save");
            OptionalLong generation = engine.marker().generation();
            if (generation.isEmpty()) {
                status[0] = update(paths, log, status[0], Phase.SKIPPED, "no unsynced changes");
                return 0;
            }
            String id = engine.newSnapshotId();
            Files.createDirectories(paths.tmpDir());
            zipPath = paths.tmpDir().resolve(CloudLayout.encode(paths.worldKey()) + "." + id + ".zip");
            long t0 = System.nanoTime();
            SnapshotArchiver.Result zip = SnapshotArchiver.createSnapshot(paths.saveDir(), zipPath);
            log.info("zipped " + zip.fileCount() + " files, " + zip.sizeBytes() / 1024 + " KiB in "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms");

            status[0] = update(paths, log, status[0], Phase.UPLOADING, "uploading " + zip.sizeBytes() / (1024 * 1024) + " MB");
            notifier.show("Uploading " + world + " to Google Drive",
                    megabytes(zip.sizeBytes()) + ". Keep this device on until it's done.");
            uploaded[1] = zip.sizeBytes();
            SyncEngine.UploadResult r = engine.uploadArchive(id, zip, SyncEngine.REASON_EXIT, generation,
                    (done, total) -> uploaded[0] = done);
            try {
                engine.releaseSessionLock(sessionId);
            } catch (IOException e) {
                log.warn("could not release the session lock (it goes stale on its own)", e);
            }
            String result = r.outcome() == SyncEngine.UploadOutcome.HEAD
                    ? "uploaded as cloud revision " + r.revision() + (r.markerCleared() ? "" : "; a new session started meanwhile")
                    : "kept as a conflict copy: the cloud has a newer version from another device";
            status[0] = update(paths, log, status[0], Phase.DONE, result);
            long seconds = Duration.between(started, Instant.now()).toSeconds();
            log.info("done in " + seconds + " s: " + result);
            if (r.outcome() == SyncEngine.UploadOutcome.HEAD) {
                notifier.show(world + " is saved to Google Drive",
                        "Uploaded " + megabytes(zip.sizeBytes()) + " in " + seconds + " s.");
            } else {
                notifier.show(world + " was also changed on another device",
                        "Both versions are kept in Google Drive. Open the world to choose which one to keep.");
            }
            return 0;
        } catch (StorageException e) {
            String why = switch (e.kind()) {
                case OFFLINE -> "offline; upload retried next session";
                case AUTH_REQUIRED -> "signed out of Google; sign in again to sync";
                case QUOTA -> "Google Drive is full";
                default -> e.getMessage();
            };
            log.warn("upload failed: " + why, e);
            status[0] = update(paths, log, status[0], Phase.FAILED, why);
            notifier.show("Couldn't upload " + world, switch (e.kind()) {
                case OFFLINE -> "No internet connection. It will be uploaded next time you play.";
                case AUTH_REQUIRED -> "You're signed out of Google. Sign in again with /cloudsync login.";
                case QUOTA -> "Your Google Drive is full. Free up space, then play the world to retry.";
                default -> e.getMessage() + ". It will be uploaded next time you play.";
            });
            return 2;
        } catch (IOException | RuntimeException e) {
            log.warn("upload failed", e);
            status[0] = update(paths, log, status[0], Phase.FAILED, String.valueOf(e.getMessage()));
            notifier.show("Couldn't upload " + world, e.getMessage() + ". It will be uploaded next time you play.");
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status[0] = update(paths, log, status[0], Phase.FAILED, "interrupted");
            return 2;
        } finally {
            notifier.close();
            heartbeat.shutdownNow();
            if (zipPath != null) {
                AtomicFiles.deleteQuietly(zipPath);
            }
            lock.ifPresent(l -> {
                try {
                    l.close();
                } catch (IOException ignored) {
                    // released by the OS on exit anyway
                }
            });
        }
    }

    private static String megabytes(long bytes) {
        return Math.max(1, Math.round(bytes / (1024.0 * 1024))) + " MB";
    }

    private static boolean waitForExit(long serverPid, FileSyncLog log) throws InterruptedException {
        Optional<ProcessHandle> server = ProcessHandle.of(serverPid);
        if (server.isEmpty() || !server.get().isAlive()) {
            log.info("server " + serverPid + " already exited");
            return true;
        }
        long t0 = System.nanoTime();
        try {
            server.get().onExit().get(SERVER_EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            log.info("server exited after " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            return true;
        } catch (TimeoutException e) {
            log.warn("server " + serverPid + " still running after " + SERVER_EXIT_TIMEOUT.toMinutes() + " min");
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return !server.get().isAlive();
        }
    }

    private static HelperStatus update(WorldPaths paths, FileSyncLog log, HelperStatus s, Phase phase, String message) {
        HelperStatus next = s.at(phase, Instant.now(), message);
        log.info(phase + ": " + message);
        try {
            next.write(paths.helperStatusFile());
        } catch (IOException ignored) {
            // the log still records it
        }
        return next;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            m.put(args[i], args[i + 1]);
        }
        for (String required : new String[]{"--save-dir", "--state-root", "--server-pid", "--session-id"}) {
            if (!m.containsKey(required)) {
                throw new IllegalArgumentException("missing " + required);
            }
        }
        return m;
    }
}
