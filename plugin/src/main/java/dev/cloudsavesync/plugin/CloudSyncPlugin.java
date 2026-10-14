package dev.cloudsavesync.plugin;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.cloudsavesync.core.CloudState;
import dev.cloudsavesync.core.StartPlan;
import dev.cloudsavesync.core.SyncEngine;
import dev.cloudsavesync.core.WorldImporter;
import dev.cloudsavesync.core.local.HelperStatus;
import dev.cloudsavesync.core.local.LocalState;
import dev.cloudsavesync.core.local.LocalSyncLock;
import dev.cloudsavesync.core.local.SyncSettings;
import dev.cloudsavesync.core.local.UserDataLocator;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.SessionLock;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.model.WorldManifest;
import dev.cloudsavesync.core.storage.AccountInfo;
import dev.cloudsavesync.core.storage.ProgressListener;
import dev.cloudsavesync.core.storage.RemoteFile;
import dev.cloudsavesync.core.storage.StorageException;
import dev.cloudsavesync.helper.HelperLauncher;
import dev.cloudsavesync.helper.SyncServices;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cloud Save Sync: the thin Hytale layer. All sync decisions live in sync-core; this class only
 * maps server lifecycle to them.
 *
 * <ul>
 *   <li>{@link #setup()}: runs before the world loads and before any save file is open, so a newer
 *       cloud version can replace the local world here, within a time budget, with progress on the
 *       loading screen. Then the session is marked as having unsynced changes.</li>
 *   <li>While playing: a periodic upload every few minutes and a session-lock heartbeat, all on
 *       background threads.</li>
 *   <li>{@link #shutdown()}: marks unsynced and starts the detached upload helper, which uploads
 *       after this process exits. No network I/O here.</li>
 * </ul>
 */
public class CloudSyncPlugin extends JavaPlugin {

    /** How long the loading screen may wait for the cloud check. */
    static final Duration CLOUD_CHECK_BUDGET = Duration.ofSeconds(10);
    /** Total time the loading screen may wait for check + download of a newer version. */
    static final Duration RESTORE_BUDGET = Duration.ofSeconds(60);
    /** How long a re-opened world waits for the previous session's helper to finish reading the save. */
    static final Duration HELPER_READ_WAIT = Duration.ofSeconds(90);
    static final Duration FIRST_PERIODIC = Duration.ofMinutes(2);
    static final Duration HEARTBEAT = Duration.ofSeconds(60);
    /** Periodic uploads wait until no chunk was saved for this long. */
    static final long QUIET_MILLIS = 2_000;
    /** An upload that is due never waits longer than this for a quiet moment. */
    static final Duration MAX_QUIET_WAIT = Duration.ofSeconds(15);
    /** How often a due upload looks for a quiet moment. */
    static final long PERIODIC_TICK_SECONDS = 5;

    enum Mode {
        /** Not a singleplayer save, or a build without Google credentials. */
        DISABLED,
        SIGNED_OUT,
        /** Signed in, but this world isn't synced on this device (worlds are opt-in). */
        NOT_SYNCED,
        NORMAL,
        /** Cloud has a newer version and this device has unsynced changes: waiting for the player. */
        CONFLICT,
        /** A cloud version will replace this world at the next load: no uploads this session. */
        SWITCH_PENDING
    }

    private final PluginSyncLog log;
    private final PlayerMessages messages;
    private final SaveActivitySystem saveActivity = new SaveActivitySystem();
    private final String sessionId = UUID.randomUUID().toString();

    /** All cloud work, one task at a time. */
    final ExecutorService io = Executors.newSingleThreadExecutor(lowPriority("cloudsync-io"));
    /** Sign-in waits (browser callback, code polling), so they never block uploads. */
    final ExecutorService authPool = Executors.newCachedThreadPool(daemon("cloudsync-auth"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("cloudsync-timer"));

    volatile Mode mode = Mode.DISABLED;
    String disabledReason = "starting";
    WorldPaths paths;
    SyncServices services;
    SyncEngine engine;

    private volatile RemoteFile sessionLockFile;
    private volatile SessionLock sessionLock;
    private volatile Instant lastUploadAttempt = Instant.EPOCH;
    volatile SyncSettings settings = SyncSettings.DEFAULTS;
    private volatile boolean announcedFirstBackup;
    private volatile StorageException.Kind lastReportedError;

    public CloudSyncPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        log = new PluginSyncLog(getLogger());
        messages = new PlayerMessages(log);
    }

    // =================================================================== lifecycle

    @Override
    protected void setup() {
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, (PlayerReadyEvent e) -> messages.playerReady());
        getEventRegistry().register(PlayerDisconnectEvent.class, (PlayerDisconnectEvent e) -> messages.playerLeft());
        getCommandRegistry().registerCommand(new CloudSyncCommand(this));
        try {
            getChunkStoreRegistry().registerSystem(saveActivity);
        } catch (RuntimeException e) {
            log.warn("save-activity tracking unavailable; periodic uploads won't wait for quiet moments", e);
        }

        try {
            Path saveDir = saveDir();
            if (UserDataLocator.fromSaveDir(saveDir).isEmpty()) {
                disable("this isn't a singleplayer save (" + saveDir + "); Cloud Save Sync only syncs singleplayer worlds");
                return;
            }
            paths = WorldPaths.of(saveDir, UserDataLocator.stateRootFor(saveDir));
            log.info("world '" + paths.worldKey() + "', save " + paths.saveDir() + ", state " + paths.stateRoot());

            Optional<SyncServices> s = SyncServices.create(paths.stateRoot(), authPool);
            if (s.isEmpty()) {
                disable("this build of the mod has no Google sign-in credentials");
                return;
            }
            services = s.get();
            engine = services.engine(paths, log);
            settings = SyncSettings.load(paths.stateRoot());
            log.info("uploads during play: " + (settings.autoUpload()
                    ? "every " + settings.autoUploadMinutes() + " min" : "off") + " (" + SyncSettings.file(paths.stateRoot()) + ")");

            reportPreviousHelper();
            waitForHelperToFinishReading();

            if (services.auth().isSignedIn()) {
                String account = accountIdWithin(CLOUD_CHECK_BUDGET);
                if (account != null && engine.isSyncEnabled(account)) {
                    mode = Mode.NORMAL;
                    loadTimeSync();
                } else {
                    mode = Mode.NOT_SYNCED;
                    log.info("world '" + paths.worldKey() + "' is not synced with the signed-in account"
                            + (account == null ? " (account unknown: Google unreachable)" : ""));
                    messages.info(account == null
                            ? "Couldn't reach Google Drive to check your account, so this world won't sync this session."
                            : "This world isn't synced. Type /cloudsync enable to back it up to your Google Drive.");
                }
                io.execute(this::announceCloudWorlds);
            } else {
                mode = Mode.SIGNED_OUT;
                messages.info("Not signed in. Type /cloudsync login to back this world up to your Google Drive"
                        + " and use it on your other devices.");
            }
            // Whatever happened above, this session is about to change the world.
            engine.marker().mark("session " + sessionId);
        } catch (IOException | RuntimeException e) {
            log.warn("Cloud Save Sync setup failed; the world loads normally without sync", e);
            messages.error("Cloud sync couldn't start (" + e.getMessage() + "). Your world is fine; it just won't sync"
                    + " this session.");
            mode = Mode.DISABLED;
            disabledReason = "setup failed: " + e.getMessage();
        }
    }

    @Override
    protected void start() {
        if (mode == Mode.DISABLED) {
            return;
        }
        lastUploadAttempt = Instant.now().minus(interval()).plus(FIRST_PERIODIC);
        timer.scheduleWithFixedDelay(() -> io.execute(this::periodicUpload), PERIODIC_TICK_SECONDS,
                PERIODIC_TICK_SECONDS, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> io.execute(this::heartbeat), HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * No network here: mark unsynced and hand the upload to the detached helper, which runs after
     * this process exits (the server halts itself ~3 s after shutdown).
     */
    @Override
    protected void shutdown() {
        timer.shutdownNow();
        try {
            if (mode == Mode.DISABLED || engine == null) {
                return;
            }
            engine.marker().mark("exit " + sessionId);
            switch (mode) {
                case SIGNED_OUT -> log.info("not signed in: world stays marked unsynced until you sign in");
                case NOT_SYNCED -> log.info("world isn't synced on this device; nothing to upload");
                case SWITCH_PENDING -> log.info("cloud version will be applied at next load; no upload");
                default -> launchHelper();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not hand the upload to the helper; it will be retried next session", e);
        } finally {
            io.shutdownNow();
            authPool.shutdownNow();
        }
    }

    private void launchHelper() {
        long pid = ProcessHandle.current().pid();
        HelperLauncher.Launch launch = HelperLauncher.command(getFile(), paths, pid, sessionId);
        try {
            Process p = HelperLauncher.start(launch, paths);
            log.info("upload helper started (pid " + p.pid() + ", " + launch.mode() + "); it uploads after the server exits."
                    + " Log: " + paths.helperLogFile());
        } catch (IOException e) {
            log.warn("could not start the upload helper (" + String.join(" ", launch.command()) + ")", e);
        }
    }

    // =================================================================== before the world loads

    private Path saveDir() {
        try {
            Universe u = Universe.get();
            if (u != null && u.getPath() != null) {
                return u.getPath().toAbsolutePath().normalize().getParent();
            }
        } catch (RuntimeException ignored) {
            // fall back to the working directory, which is the save folder in singleplayer
        }
        return Path.of("").toAbsolutePath();
    }

    private void disable(String reason) {
        mode = Mode.DISABLED;
        disabledReason = reason;
        log.info("Cloud Save Sync disabled: " + reason);
    }

    /** Tells the player (and the log) how the previous session's helper ended. */
    private void reportPreviousHelper() {
        Optional<HelperStatus> st = HelperStatus.read(paths.helperStatusFile());
        if (st.isEmpty()) {
            return;
        }
        HelperStatus s = st.get();
        String timing = "started " + s.startedAt() + ", last update " + s.updatedAt() + ", launch " + s.launchMode();
        if (s.phase().finished()) {
            log.info("previous upload helper: " + s.phase() + " - " + s.message() + " (" + timing + ")");
            if (s.phase() == HelperStatus.Phase.FAILED) {
                messages.warn("Last time, the cloud upload didn't finish: " + s.message()
                        + ". It will be retried while you play.");
            }
        } else if (!s.processAlive()) {
            log.warn("previous upload helper stopped unexpectedly during " + s.phase() + " (" + timing
                    + "); it was probably killed. See " + paths.helperLogFile());
            messages.warn("Last time, the cloud upload was interrupted. It will be retried while you play.");
        } else {
            log.info("previous upload helper still running: " + s.phase() + " (" + timing + ")");
        }
    }

    /** Waits (bounded) while the previous session's helper is still zipping this save. */
    private void waitForHelperToFinishReading() {
        long deadline = System.nanoTime() + HELPER_READ_WAIT.toNanos();
        boolean announced = false;
        while (System.nanoTime() < deadline) {
            Optional<HelperStatus> s = HelperStatus.read(paths.helperStatusFile());
            if (s.isEmpty() || !s.get().phase().readsSave() || !s.get().processAlive()) {
                return;
            }
            if (!announced) {
                loadingStatus("Finishing the last cloud upload...", 5);
                log.info("waiting for the previous upload helper to finish reading the save");
                announced = true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("previous upload helper is still reading the save after " + HELPER_READ_WAIT.toSeconds()
                + " s; loading anyway");
    }

    private void loadTimeSync() throws IOException {
        long deadline = System.nanoTime() + RESTORE_BUDGET.toNanos();
        Optional<LocalSyncLock> lock = LocalSyncLock.tryAcquire(paths.syncLockFile());
        if (lock.isEmpty()) {
            // The previous session's helper is still uploading. This copy is the newest anyway.
            log.info("previous upload still in progress; skipping the cloud check this time");
            messages.info("Your last session is still uploading in the background.");
            return;
        }
        try (LocalSyncLock held = lock.get()) {
            loadingStatus("Checking Google Drive for a newer save...", 10);
            CloudState cloud = null;
            StartPlan plan;
            try {
                cloud = io.submit(engine::fetch).get(CLOUD_CHECK_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
                plan = engine.planStart(cloud);
            } catch (TimeoutException e) {
                log.warn("cloud check took longer than " + CLOUD_CHECK_BUDGET.toSeconds() + " s; playing local copy");
                messages.warn("Google Drive is slow to respond; playing your local copy. It will sync later.");
                plan = engine.planStartOffline();
            } catch (ExecutionException e) {
                plan = cloudCheckFailed(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                plan = engine.planStartOffline();
            }
            log.info("load-time plan: " + plan.action() + " (" + plan.why() + ")");
            act(plan, deadline);
            if (cloud != null) {
                warnAboutOtherDevice(cloud);
                RemoteFile existingLock = cloud.lockFile();
                io.execute(() -> acquireSessionLock(existingLock));
            }
        }
    }

    private StartPlan cloudCheckFailed(Throwable cause) throws IOException {
        if (cause instanceof StorageException se && se.kind() == StorageException.Kind.AUTH_REQUIRED) {
            mode = Mode.SIGNED_OUT;
            messages.warn("You're signed out of Google Drive. Type /cloudsync login to sign in again.");
        } else if (cause instanceof StorageException se && se.kind() == StorageException.Kind.OFFLINE) {
            messages.warn("No connection to Google Drive; playing your local copy. It will sync when you're back online.");
        } else {
            log.warn("cloud check failed", cause);
            messages.warn("Couldn't check Google Drive (" + (cause == null ? "?" : cause.getMessage())
                    + "); playing your local copy.");
        }
        return engine.planStartOffline();
    }

    private void act(StartPlan plan, long deadline) throws IOException {
        Instant now = Instant.now();
        switch (plan.action()) {
            case OFFLINE -> {
            }
            case NOTHING_IN_CLOUD -> messages.info("This world will be backed up to your Google Drive while you play.");
            case UP_TO_DATE -> messages.info("Your cloud save is up to date.");
            case LOCAL_AHEAD -> messages.info("This device has a newer version than the cloud; it will be uploaded.");
            case APPLY_PENDING -> applyPending();
            case RESTORE -> restore(plan, deadline);
            case CONFLICT -> {
                LocalState.Conflict known = engine.localState().conflict();
                if (known == null || known.cloudRevision() != plan.cloudRevision()) {
                    engine.recordConflict(plan);
                }
                mode = Mode.CONFLICT;
                SnapshotInfo cloud = plan.snapshot();
                String other = "a version from " + cloud.deviceName() + " (" + PlayerMessages.ago(cloud.createdAt(), now) + ")";
                if (engine.localState().lastSyncedRevision() == null) {
                    messages.warn("Your Google Drive already has a world with this name: " + other
                            + ". This copy was never synced, so nothing was changed.");
                } else {
                    messages.warn("Your Google Drive has a newer version of this world, " + other
                            + ", and this device has changes that were never uploaded. Nothing was overwritten.");
                }
                messages.warn("Type /cloudsync usecloud to switch to the cloud version (this one is backed up),"
                        + " or /cloudsync keeplocal to keep this one (the cloud one stays in the history).");
            }
        }
    }

    private void applyPending() throws IOException {
        LocalState.Pending p = engine.localState().pendingRestore();
        Optional<Path> zip = engine.pendingZip();
        if (p == null || zip.isEmpty()) {
            engine.discardPending();
            log.warn("pending cloud version was missing; keeping the local copy");
            return;
        }
        loadingStatus("Switching to the cloud save from " + p.deviceName() + "...", 80);
        Path backup = engine.applyRestore(zip.get(), p.sha256Hex(), p.revision(), p.snapshotId());
        messages.info("Switched to the save from " + p.deviceName() + " that was downloaded last time."
                + " Your previous local copy is backed up in " + backup.getParent() + ".");
    }

    private void restore(StartPlan plan, long deadline) throws IOException {
        SnapshotInfo snap = plan.snapshot();
        String from = snap.deviceName() + " (" + PlayerMessages.ago(snap.createdAt(), Instant.now()) + ")";
        log.info("downloading newer cloud version " + snap.id() + " from " + from);
        CompletableFuture<Path> download = CompletableFuture.supplyAsync(() -> {
            try {
                return engine.download(snap, (done, total) -> loadingStatus("Downloading the newer save from "
                        + snap.deviceName() + "... " + PlayerMessages.megabytes(done) + " / "
                        + PlayerMessages.megabytes(total), 15 + 60.0 * done / Math.max(1, total)));
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, io);

        long remaining = Math.max(0, deadline - System.nanoTime());
        try {
            Path zip = download.get(remaining, TimeUnit.NANOSECONDS);
            loadingStatus("Applying the newer save...", 80);
            Path backup = engine.applyRestore(zip, snap.sha256Hex(), plan.cloudRevision(), snap.id());
            messages.info("Loaded the newer save from " + from + ". Your previous local copy is backed up in "
                    + backup.getParent() + ".");
        } catch (TimeoutException e) {
            // Too slow for the loading screen: finish in the background and apply at the next load.
            mode = Mode.SWITCH_PENDING;
            log.info("download didn't finish within the load budget; continuing in the background");
            messages.warn("A newer save from " + from + " is downloading in the background. Playing your local copy"
                    + " for now; anything you do now is only kept as a local backup.");
            download.whenComplete((zip, err) -> {
                if (err != null) {
                    log.warn("background download failed", err);
                    messages.error("Downloading the newer save failed (" + rootMessage(err)
                            + "). Next time you open this world you'll be asked which version to keep.");
                    return;
                }
                try {
                    engine.stagePending(snap, plan.cloudRevision(), zip);
                    messages.info("Newer save downloaded. Quit to the menu and reopen this world to switch to it.");
                } catch (IOException ex) {
                    log.warn("could not record the downloaded version", ex);
                }
            });
        } catch (ExecutionException e) {
            mode = Mode.SWITCH_PENDING;
            log.warn("download of the newer cloud version failed", e.getCause());
            messages.error("Couldn't download the newer save from " + from + " (" + rootMessage(e.getCause())
                    + "). Playing your local copy; you'll be asked which version to keep next time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mode = Mode.SWITCH_PENDING;
        }
    }

    private void warnAboutOtherDevice(CloudState cloud) {
        engine.foreignLock(cloud).ifPresent(l -> messages.warn("This world was last seen open on " + l.deviceName()
                + " " + PlayerMessages.ago(l.heartbeatAt(), Instant.now()) + ". If it's still open there,"
                + " close it there first, or your changes there and here will conflict."));
    }

    // =================================================================== while playing (io thread)

    private void acquireSessionLock(RemoteFile existing) {
        try {
            SessionLock lock = engine.newSessionLock(sessionId);
            sessionLockFile = engine.writeSessionLock(lock, existing);
            sessionLock = lock;
        } catch (IOException e) {
            log.warn("could not write the session lock (" + e.getMessage() + ")", null);
        }
    }

    private void heartbeat() {
        SessionLock lock = sessionLock;
        if (lock == null || mode == Mode.DISABLED || mode == Mode.SIGNED_OUT) {
            return;
        }
        try {
            SessionLock beat = lock.heartbeat(Instant.now());
            sessionLockFile = engine.writeSessionLock(beat, sessionLockFile);
            sessionLock = beat;
        } catch (IOException e) {
            log.info("session heartbeat failed (" + e.getMessage() + ")");
        }
    }

    private void periodicUpload() {
        if (mode != Mode.NORMAL || !settings.autoUpload() || !services.auth().isSignedIn()) {
            return;
        }
        Duration sinceLast = Duration.between(lastUploadAttempt, Instant.now());
        if (sinceLast.compareTo(interval()) < 0) {
            return;
        }
        long quietFor = saveActivity.millisSinceLastSave();
        boolean overdue = sinceLast.compareTo(interval().plus(MAX_QUIET_WAIT)) >= 0;
        if (quietFor < QUIET_MILLIS && !overdue) {
            // While exploring, new chunks are saved almost constantly: retry at the next tick.
            return;
        }
        log.info("periodic upload starting (" + (quietFor >= QUIET_MILLIS
                ? "no chunk saved for " + quietFor + " ms"
                : "no quiet moment for " + MAX_QUIET_WAIT.toSeconds() + " s, uploading anyway") + ")");
        upload(SyncEngine.REASON_PERIODIC, ProgressListener.NONE, false);
    }

    /**
     * Uploads the running world. Never clears the unsynced marker (the world keeps changing); the
     * helper's upload after exit does that. Runs on the io thread.
     */
    boolean upload(String reason, ProgressListener progress, boolean manual) {
        lastUploadAttempt = Instant.now();
        Optional<LocalSyncLock> lock;
        try {
            lock = LocalSyncLock.tryAcquire(paths.syncLockFile());
        } catch (IOException e) {
            lock = Optional.empty();
        }
        if (lock.isEmpty()) {
            if (manual) {
                messages.info("Another upload of this world is still running; try again in a minute.");
            }
            return false;
        }
        try (LocalSyncLock held = lock.get()) {
            SyncEngine.UploadResult r = engine.uploadSave(reason, false, progress);
            lastReportedError = null;
            if (r.outcome() == SyncEngine.UploadOutcome.CONFLICT_COPY) {
                mode = Mode.CONFLICT;
                LocalState.Conflict c = engine.localState().conflict();
                messages.warn("Another device uploaded a newer version of this world"
                        + (c == null ? "" : " (" + c.cloudDeviceName() + ")")
                        + ". Your version was saved to the cloud as a separate copy; nothing was overwritten."
                        + " Type /cloudsync usecloud or /cloudsync keeplocal to choose.");
                return true;
            }
            if (manual || !announcedFirstBackup) {
                messages.info("Backed up to Google Drive (" + PlayerMessages.megabytes(r.snapshot().sizeBytes()) + ").");
                announcedFirstBackup = true;
            }
            return true;
        } catch (StorageException e) {
            reportUploadError(e, manual);
            return false;
        } catch (IOException | RuntimeException e) {
            log.warn("upload failed", e);
            if (manual) {
                messages.error("Upload failed: " + e.getMessage());
            }
            return false;
        }
    }

    private void reportUploadError(StorageException e, boolean manual) {
        log.warn("upload failed (" + e.kind() + "): " + e.getMessage(), null);
        if (!manual && e.kind() == lastReportedError) {
            return; // don't repeat the same problem every few minutes
        }
        lastReportedError = e.kind();
        switch (e.kind()) {
            case OFFLINE -> messages.warn("Can't reach Google Drive right now. Your world is safe on this device;"
                    + " it will upload when the connection is back.");
            case AUTH_REQUIRED -> {
                mode = Mode.SIGNED_OUT;
                messages.warn("You've been signed out of Google Drive. Type /cloudsync login to sign in again.");
            }
            case QUOTA -> messages.error("Your Google Drive is full, so this world can't be backed up."
                    + " Free some space in Google Drive.");
            default -> messages.error("Cloud upload failed: " + e.getMessage() + ". It will be retried.");
        }
    }

    // =================================================================== used by commands

    void afterSignIn() {
        if (mode == Mode.DISABLED) {
            return;
        }
        io.execute(() -> {
            try {
                AccountInfo account = services.storage().account();
                messages.info("Signed in as " + (account.email() == null ? "your Google account" : account.email()) + ".");
                // Signing in never starts syncing a world: only worlds enabled for this account sync.
                if (engine.isSyncEnabled(account.id())) {
                    mode = Mode.NORMAL;
                    startSyncingRunningWorld("after sign-in");
                } else {
                    mode = Mode.NOT_SYNCED;
                    messages.info("This world isn't synced. Type /cloudsync enable to back it up to your Google Drive.");
                }
            } catch (IOException e) {
                log.warn("post sign-in check failed", e);
            }
            announceCloudWorlds();
        });
    }

    /**
     * Cloud check for a world that is already running (after sign-in or /cloudsync enable). The world
     * is loaded and this session has changed it, so a newer cloud version is never applied here: it
     * becomes a conflict for the player to resolve. Otherwise this world is uploaded right away.
     * Runs on the io thread.
     */
    private void startSyncingRunningWorld(String why) throws IOException {
        CloudState cloud = engine.fetch();
        StartPlan plan = engine.planStart(cloud);
        log.info(why + ": " + plan.action() + " (" + plan.why() + ")");
        warnAboutOtherDevice(cloud);
        acquireSessionLock(cloud.lockFile());
        if (plan.action() == StartPlan.Action.CONFLICT || plan.action() == StartPlan.Action.RESTORE) {
            act(new StartPlan(StartPlan.Action.CONFLICT, plan.snapshot(), plan.cloudRevision(), plan.why()), 0);
            return;
        }
        messages.info("This world is now synced with your Google Drive. Uploading it now...");
        upload(SyncEngine.REASON_MANUAL, ProgressListener.NONE, true);
    }

    /** {@code /cloudsync enable}: start syncing this world. Runs on the io thread. */
    void enableSync() {
        try {
            String account = services.storage().account().id();
            if (mode != Mode.NOT_SYNCED && engine.isSyncEnabled(account)) {
                messages.info("This world is already synced. /cloudsync status shows the details.");
                return;
            }
            engine.setSyncEnabled(true, account);
            mode = Mode.NORMAL;
            startSyncingRunningWorld("enable");
        } catch (StorageException e) {
            reportUploadError(e, true);
        } catch (IOException e) {
            log.warn("enable failed", e);
            messages.error("Couldn't turn on sync for this world: " + e.getMessage());
        }
    }

    /** {@code /cloudsync disable}: stop syncing this world on this device. Runs on the io thread. */
    void disableSync() {
        try {
            if (mode == Mode.NOT_SYNCED) {
                messages.info("This world isn't synced.");
                return;
            }
            engine.setSyncEnabled(false, services.auth().knownAccount().map(AccountInfo::id).orElse(null));
            mode = Mode.NOT_SYNCED;
            sessionLock = null;
            try {
                engine.releaseSessionLock(sessionId);
            } catch (IOException e) {
                log.info("could not release the session lock (" + e.getMessage() + "); it goes stale on its own");
            }
            sessionLockFile = null;
            messages.info("'" + paths.worldKey() + "' won't sync on this device any more. Its copy in your Google Drive"
                    + " stays there. Type /cloudsync enable to sync it again.");
        } catch (IOException e) {
            messages.error("Couldn't turn off sync for this world: " + e.getMessage());
        }
    }

    /**
     * The signed-in account's id, waiting at most {@code budget} (it is remembered after the first
     * time, so this is only slow once per sign-in). Null if Google can't be reached.
     */
    private String accountIdWithin(Duration budget) {
        Optional<AccountInfo> known = services.auth().knownAccount();
        if (known.isPresent()) {
            return known.get().id();
        }
        try {
            return io.submit(() -> services.storage().account()).get(budget.toMillis(), TimeUnit.MILLISECONDS).id();
        } catch (TimeoutException | ExecutionException e) {
            log.info("could not read the signed-in account (" + rootMessage(e) + ")");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private WorldImporter importer() {
        return services.importer(paths.saveDir().getParent(), paths.stateRoot(), log);
    }

    /** The numbering shown by the last {@code /cloudsync list}, so {@code download 2} means what the player saw. */
    private volatile List<String> listedWorlds;

    /** On join: tell the player about their cloud worlds. Runs on the io thread. */
    void announceCloudWorlds() {
        if (!services.auth().isSignedIn()) {
            return;
        }
        try {
            List<WorldImporter.CloudWorld> all = importer().cloudWorlds();
            if (all.isEmpty()) {
                return;
            }
            long notHere = all.stream().filter(w -> !onThisDevice(w.worldKey())).count();
            messages.info("You have " + all.size() + (all.size() == 1 ? " world" : " worlds") + " in your Google Drive"
                    + (notHere == 0 ? "" : ", " + notHere + " not on this device") + ". Type /cloudsync list to see them.");
        } catch (IOException e) {
            log.info("could not list cloud worlds (" + e.getMessage() + ")");
        }
    }

    /** {@code /cloudsync list}. Runs on the io thread. */
    void listCloudWorlds() {
        List<WorldImporter.CloudWorld> all;
        try {
            all = importer().cloudWorlds();
        } catch (IOException e) {
            messages.warn("Couldn't reach Google Drive: " + e.getMessage());
            return;
        }
        listedWorlds = all.stream().map(WorldImporter.CloudWorld::worldKey).toList();
        if (all.isEmpty()) {
            messages.info("There are no worlds in your Google Drive yet. Type /cloudsync enable in a world to sync it.");
            return;
        }
        messages.info("Your worlds in Google Drive:");
        Instant now = Instant.now();
        boolean anyMissing = false;
        for (int i = 0; i < all.size(); i++) {
            WorldImporter.CloudWorld w = all.get(i);
            String where;
            if (w.worldKey().equals(paths.worldKey())) {
                where = "this world";
            } else if (onThisDevice(w.worldKey())) {
                where = "on this device";
            } else {
                where = "not on this device";
                anyMissing = true;
            }
            messages.info("[" + (i + 1) + "] " + w.worldKey() + " - " + where + ", saved "
                    + PlayerMessages.ago(w.head().createdAt(), now) + " on " + w.head().deviceName() + ", "
                    + PlayerMessages.megabytes(w.head().sizeBytes()));
        }
        if (anyMissing) {
            messages.info("To download, type /cloudsync download and the numbers, e.g. /cloudsync download 2"
                    + " or /cloudsync download 1,3");
        }
    }

    /** {@code /cloudsync download 1,3} or {@code all}. Runs on the io thread. */
    void downloadCloudWorlds(String spec) {
        List<String> numbered = listedWorlds;
        try {
            if (numbered == null) {
                numbered = importer().cloudWorlds().stream().map(WorldImporter.CloudWorld::worldKey).toList();
                listedWorlds = numbered;
            }
        } catch (IOException e) {
            messages.warn("Couldn't reach Google Drive: " + e.getMessage());
            return;
        }
        List<String> wanted = new java.util.ArrayList<>();
        String trimmed = spec.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.equals("all")) {
            numbered.stream().filter(k -> !onThisDevice(k)).forEach(wanted::add);
        } else {
            for (String part : trimmed.split("[,;\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                int n;
                try {
                    n = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    messages.warn("'" + part + "' isn't a number. Type /cloudsync list, then e.g. /cloudsync download 2");
                    return;
                }
                if (n < 1 || n > numbered.size()) {
                    messages.warn("There's no world number " + n + ". Type /cloudsync list to see the numbers.");
                    return;
                }
                if (!wanted.contains(numbered.get(n - 1))) {
                    wanted.add(numbered.get(n - 1));
                }
            }
        }
        if (wanted.isEmpty()) {
            messages.info(trimmed.equals("all") ? "All your cloud worlds are already on this device."
                    : "Type /cloudsync list, then e.g. /cloudsync download 2");
            return;
        }

        WorldImporter importer = importer();
        java.util.Map<String, WorldImporter.CloudWorld> current = new java.util.HashMap<>();
        try {
            // Fresh manifests: the list may be minutes old and its snapshots pruned since.
            importer.cloudWorlds().forEach(w -> current.put(w.worldKey(), w));
        } catch (IOException e) {
            messages.warn("Couldn't reach Google Drive: " + e.getMessage());
            return;
        }
        int added = 0;
        for (String key : wanted) {
            WorldImporter.CloudWorld w = current.get(key);
            if (w == null) {
                messages.warn("'" + key + "' isn't in your Google Drive any more.");
                continue;
            }
            if (onThisDevice(key)) {
                messages.info("'" + key + "' is already on this device.");
                continue;
            }
            if (!WorldImporter.isSafeFolderName(key)) {
                messages.warn("'" + key + "' can't be downloaded here: its name isn't a valid folder name on this system.");
                continue;
            }
            messages.info("Downloading '" + key + "' (" + PlayerMessages.megabytes(w.head().sizeBytes()) + ")...");
            try {
                importer.importWorld(w, ProgressListener.NONE, SyncEngine.IN_SESSION_READ_BYTES_PER_SECOND);
                added++;
                messages.info("Added '" + key + "' (saved " + PlayerMessages.ago(w.head().createdAt(), Instant.now())
                        + " on " + w.head().deviceName() + ").");
            } catch (IOException e) {
                log.warn("could not download cloud world '" + key + "'", e);
                messages.warn("Couldn't download '" + key + "' (" + e.getMessage() + "). Try again later.");
            }
        }
        if (added > 0) {
            messages.info((added == 1 ? "It" : "They") + " will be in your world list when you go back to the main"
                    + " menu. If not, restart the game.");
        }
    }

    /** {@code /cloudsync keep [copies]}: show or set how many copies the cloud keeps. Runs on the io thread. */
    void keepCopies(Integer copies) {
        try {
            if (copies == null) {
                CloudState cloud = engine.fetch();
                if (cloud.manifest() == null || cloud.manifest().head().isEmpty()) {
                    messages.info("This world isn't in your Google Drive yet. Type /cloudsync enable to sync it.");
                    return;
                }
                var m = cloud.manifest();
                long bytes = m.snapshots().stream().mapToLong(SnapshotInfo::sizeBytes).sum();
                long pinned = m.snapshots().stream().filter(SnapshotInfo::pinned).count();
                messages.info("Google Drive keeps the " + m.keep() + " newest " + (m.keep() == 1 ? "copy" : "copies")
                        + " of this world. Right now: " + m.snapshots().size() + " ("
                        + PlayerMessages.megabytes(bytes) + ")" + (pinned == 0 ? "" : ", including " + pinned
                        + " set aside in a conflict") + ". Change it with /cloudsync keep <1-"
                        + WorldManifest.MAX_KEEP + ">.");
                return;
            }
            SyncEngine.KeepResult r = engine.setKeep(copies);
            messages.info("Google Drive will keep the " + r.keep() + " newest " + (r.keep() == 1 ? "copy" : "copies")
                    + " of '" + paths.worldKey() + "' (for all your devices)."
                    + (r.removed() == 0 ? "" : " Removed " + r.removed() + " older " + (r.removed() == 1 ? "copy" : "copies")
                    + " (" + PlayerMessages.megabytes(r.freedBytes()) + ")."));
            if (r.keep() == 1) {
                messages.warn("With 1 copy there's no older version to go back to if a save goes wrong.");
            }
        } catch (StorageException e) {
            if (e.kind() == StorageException.Kind.NOT_FOUND) {
                messages.info("This world isn't in your Google Drive yet. Type /cloudsync enable to sync it.");
            } else {
                messages.warn("Couldn't reach Google Drive: " + e.getMessage());
            }
        } catch (IOException e) {
            messages.error("Couldn't change the setting: " + e.getMessage());
        }
    }

    private boolean onThisDevice(String worldKey) {
        return java.nio.file.Files.exists(paths.saveDir().getParent().resolve(worldKey));
    }

    PlayerMessages messages() {
        return messages;
    }

    void loadingStatus(String text, double percent) {
        try {
            HytaleServer server = HytaleServer.get();
            if (server != null) {
                server.reportSingleplayerStatus(Message.raw(text), Math.max(0, Math.min(100, percent)));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // purely cosmetic
        }
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof ExecutionException || c instanceof java.util.concurrent.CompletionException)) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    Duration interval() {
        return Duration.ofMinutes(settings.autoUploadMinutes());
    }

    /** Changes and saves the settings; a new interval counts from now. */
    void updateSettings(SyncSettings next) throws IOException {
        next.save(paths.stateRoot());
        settings = next;
        lastUploadAttempt = Instant.now();
        log.info("uploads during play: " + (next.autoUpload() ? "every " + next.autoUploadMinutes() + " min" : "off"));
    }

    /**
     * The sync thread runs at minimum priority so the game's threads always get the CPU first
     * (the OS honours this on Windows; on Linux it's a hint).
     */
    private static java.util.concurrent.ThreadFactory lowPriority(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
