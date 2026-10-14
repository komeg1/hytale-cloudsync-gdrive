package dev.cloudsavesync.plugin;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Opens a URL in the user's default browser. Best effort: the URL is always shown in chat too. */
final class BrowserOpener {

    private BrowserOpener() {
    }

    static boolean open(String url) {
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception | LinkageError ignored) {
            // fall through to the OS command
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd = os.startsWith("windows") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : os.contains("mac") ? List.of("open", url)
                : List.of("xdg-open", url);
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
