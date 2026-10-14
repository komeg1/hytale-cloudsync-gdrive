package dev.cloudsavesync.gdrive;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * The two OAuth clients the mod ships with, read from a resource generated at build time from
 * secrets.properties. For installed apps Google treats these "secrets" as public, so embedding them
 * is expected; they only identify the app, the user's consent is what grants access.
 *
 * @param desktop "Desktop app" client: browser sign-in with a loopback redirect; null if this build
 *                has none, in which case every sign-in uses the code flow
 * @param tv      "TVs and Limited Input devices" client: show-a-code sign-in; always present
 */
public record GoogleClients(Client desktop, Client tv) {

    public static final String RESOURCE = "/cloudsavesync/google-oauth.properties";

    public record Client(String id, String secret) {
    }

    public enum Kind {
        DESKTOP, TV
    }

    /** Null if this build has no client of that kind. */
    public Client get(Kind kind) {
        return kind == Kind.DESKTOP ? desktop : tv;
    }

    public boolean hasDesktop() {
        return desktop != null;
    }

    /** Empty if this build was made without secrets.properties. */
    public static Optional<GoogleClients> load() {
        try (InputStream in = GoogleClients.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            Properties p = new Properties();
            p.load(in);
            Client desktop = client(p, "desktop");
            Client tv = client(p, "tv");
            if (tv == null) {
                return Optional.empty();
            }
            return Optional.of(new GoogleClients(desktop, tv));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Client client(Properties p, String prefix) {
        String id = p.getProperty(prefix + ".client_id", "").trim();
        String secret = p.getProperty(prefix + ".client_secret", "").trim();
        return id.isEmpty() || secret.isEmpty() ? null : new Client(id, secret);
    }
}
