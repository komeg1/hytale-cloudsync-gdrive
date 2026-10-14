package dev.cloudsavesync.helper;

import dev.cloudsavesync.core.local.WorldPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts {@link UploadHelper} as a separate process that outlives the server.
 *
 * <p>The server halts itself ~3 s after shutdown (Runtime.halt in HytaleServer), far too soon to
 * upload, so the helper waits for the server process to exit and then zips and uploads on its own.
 * It uses the game's own Java runtime ({@code java.home}) and this plugin jar as its classpath, so
 * nothing extra has to be installed.
 *
 * <ul>
 *   <li>Windows: {@code javaw.exe}, so no console window appears.</li>
 *   <li>Linux: wrapped in {@code setsid -f}, putting the helper in a new session and process group,
 *       re-parented away from the game's process tree, so Steam's Gaming Mode doesn't take it down
 *       with the game. Whether it survives is logged by the helper and reported next launch.</li>
 *   <li>macOS: started directly; child processes survive their parent there.</li>
 * </ul>
 */
public final class HelperLauncher {

    public record Launch(String mode, List<String> command) {
    }

    private HelperLauncher() {
    }

    public static Launch command(Path pluginJar, WorldPaths paths, long serverPid, String sessionId) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows");
        boolean linux = os.contains("linux");
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = windows && Files.isRegularFile(bin.resolve("javaw.exe")) ? bin.resolve("javaw.exe")
                : bin.resolve(windows ? "java.exe" : "java");

        String mode = windows ? "javaw" : "direct";
        List<String> cmd = new ArrayList<>();
        if (linux) {
            Path setsid = findSetsid();
            if (setsid != null) {
                cmd.add(setsid.toString());
                cmd.add("-f");
                mode = "setsid";
            }
        }
        cmd.add(java.toString());
        cmd.add("-Xmx256m");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(pluginJar.toAbsolutePath().toString());
        cmd.add(UploadHelper.class.getName());
        cmd.add("--save-dir");
        cmd.add(paths.saveDir().toString());
        cmd.add("--state-root");
        cmd.add(paths.stateRoot().toString());
        cmd.add("--server-pid");
        cmd.add(Long.toString(serverPid));
        cmd.add("--session-id");
        cmd.add(sessionId);
        cmd.add("--launch-mode");
        cmd.add(mode);
        return new Launch(mode, cmd);
    }

    /** Starts the helper and returns immediately. Its own output goes to helper-stdout.log. */
    public static Process start(Launch launch, WorldPaths paths) throws IOException {
        Files.createDirectories(paths.worldStateDir());
        File nullFile = new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "NUL" : "/dev/null");
        File stdout = paths.worldStateDir().resolve("helper-stdout.log").toFile();
        return new ProcessBuilder(launch.command())
                .directory(paths.stateRoot().toFile())
                .redirectInput(ProcessBuilder.Redirect.from(nullFile))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdout))
                .redirectErrorStream(true)
                .start();
    }

    private static Path findSetsid() {
        for (String dir : List.of("/usr/bin", "/bin", "/usr/sbin", "/sbin")) {
            Path p = Path.of(dir, "setsid");
            if (Files.isExecutable(p)) {
                return p;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                Path p = Path.of(dir, "setsid");
                if (Files.isExecutable(p)) {
                    return p;
                }
            }
        }
        return null;
    }
}
