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
 * @param desktop "Desktop app" client: browser sign-in with a loopback redirect
 * @param tv      "TVs and Limited Input devices" client: show-a-code sign-in
 */
public record GoogleClients(Client desktop, Client tv) {

    public static final String RESOURCE = "/cloudsavesync/google-oauth.properties";

    public record Client(String id, String secret) {
    }

    public enum Kind {
        DESKTOP, TV
    }

    public Client get(Kind kind) {
        return kind == Kind.DESKTOP ? desktop : tv;
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
            if (desktop == null || tv == null) {
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
