package dev.cloudsavesync.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort desktop notifications for the upload after the game closes, using each OS's own
 * notification system: a Windows toast (through PowerShell), {@code osascript} on macOS, and
 * {@code notify-send} or D-Bus ({@code gdbus}) on Linux. Each notification replaces the previous
 * one where the OS allows it, so "uploading" turns into "uploaded" instead of piling up.
 *
 * <p>Notifications are shown one at a time on a background thread, so they never hold up the
 * upload; {@link #close()} waits briefly for the last one. A failure is logged and otherwise
 * ignored. Steam Deck Gaming Mode shows no desktop notifications at all.
 */
final class DesktopNotifier implements AutoCloseable {

    static final String APP_NAME = "Cloud Save Sync";
    /** Windows: the app id toasts are sent under, registered per user so they show {@link #APP_NAME}. */
    private static final String WINDOWS_APP_ID = "CloudSaveSync.Hytale";
    private static final long COMMAND_TIMEOUT_SECONDS = 15;

    private final boolean enabled;
    private final String tag;
    private final FileSyncLog log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "helper-notify");
        t.setDaemon(true);
        return t;
    });
    /** Linux: id of our last notification, so the next one replaces it. */
    private String linuxId = "0";

    /** @param tag identifies the world: notifications with the same tag replace each other */
    DesktopNotifier(boolean enabled, String tag, FileSyncLog log) {
        this.enabled = enabled;
        this.tag = tag;
        this.log = log;
    }

    void show(String title, String body) {
        if (!enabled) {
            return;
        }
        worker.execute(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                boolean shown = os.contains("win") ? windows(title, body)
                        : os.contains("mac") ? mac(title, body)
                        : linux(title, body);
                if (!shown) {
                    log.info("desktop notification not shown (no notification service found): " + title);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("desktop notification failed: " + title, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            worker.awaitTermination(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // The text goes in through environment variables, so no quoting or escaping is needed.

    private boolean windows(String title, String body) throws IOException, InterruptedException {
        String script = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$key = 'HKCU:\\Software\\Classes\\AppUserModelId\\" + WINDOWS_APP_ID + "'",
                "if (-not (Test-Path $key)) { New-Item -Path $key -Force | Out-Null }",
                "Set-ItemProperty -Path $key -Name DisplayName -Value '" + APP_NAME + "'",
                "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null",
                "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime] | Out-Null",
                "$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)",
                "$text = $xml.GetElementsByTagName('text')",
                "$text.Item(0).AppendChild($xml.CreateTextNode($env:CLOUDSYNC_TITLE)) | Out-Null",
                "$text.Item(1).AppendChild($xml.CreateTextNode($env:CLOUDSYNC_BODY)) | Out-Null",
                "$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)",
                "$toast.Tag = $env:CLOUDSYNC_TAG",
                "$toast.Group = 'cloudsync'",
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('" + WINDOWS_APP_ID + "').Show($toast)");
        String windir = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path powershell = Path.of(windir, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        return run(List.of(Files.exists(powershell) ? powershell.toString() : "powershell.exe",
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-Command", script), title, body) != null;
    }

    private boolean mac(String title, String body) throws IOException, InterruptedException {
        return run(List.of("/usr/bin/osascript", "-e",
                "display notification (system attribute \"CLOUDSYNC_BODY\") with title \"" + APP_NAME
                        + "\" subtitle (system attribute \"CLOUDSYNC_TITLE\")"), title, body) != null;
    }

    private boolean linux(String title, String body) throws IOException, InterruptedException {
        // notify-send -p prints the id and -r replaces (libnotify 0.7.9+); older versions just show it.
        List<String> send = new ArrayList<>(List.of("notify-send", "-a", APP_NAME, "-i", "folder-cloud", "-p"));
        if (!linuxId.equals("0")) {
            send.addAll(List.of("-r", linuxId));
        }
        send.addAll(List.of(title, body));
        String out = run(send, title, body);
        if (out == null) {
            out = run(List.of("notify-send", "-a", APP_NAME, title, body), title, body);
        }
        if (out == null) {
            out = run(List.of("gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications",
                    "--object-path", "/org/freedesktop/Notifications", "--method", "org.freedesktop.Notifications.Notify",
                    gvariant(APP_NAME), linuxId, "'folder-cloud'", gvariant(title), gvariant(body), "[]", "{}",
                    "8000"), title, body);
        }
        if (out == null) {
            return false;
        }
        String digits = out.replaceAll("[^0-9]", "");
        if (!digits.isEmpty() && digits.length() < 10) {
            linuxId = digits;
        }
        return true;
    }

    private static String gvariant(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** Runs a command; its output if it exited with 0, or null if it failed or isn't installed. */
    private String run(List<String> command, String title, String body) throws InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true);
        pb.environment().put("CLOUDSYNC_TITLE", title);
        pb.environment().put("CLOUDSYNC_BODY", body);
        pb.environment().put("CLOUDSYNC_TAG", tag);
        Process p;
        try {
            p = pb.start();
        } catch (IOException notInstalled) {
            return null;
        }
        try {
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String text = new String(out, StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0) {
                log.info(command.get(0) + " exited with " + p.exitValue() + (text.isEmpty() ? "" : ": " + text));
                return null;
            }
            return text;
        } catch (IOException e) {
            p.destroyForcibly();
            return null;
        }
    }
}
