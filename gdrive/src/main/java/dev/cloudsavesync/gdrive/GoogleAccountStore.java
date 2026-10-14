package dev.cloudsavesync.gdrive;

import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.local.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The refresh token, in {@code <state root>/google-account.json}: per user, outside every save
 * folder, so it is never zipped or uploaded. Readable only by the user on macOS and Linux.
 *
 * <p>{@code accountId} and {@code email} identify the Google account; they're filled in after
 * sign-in and kept so offline sessions still know which account is signed in.
 */
public record GoogleAccountStore(Path file) {

    /** @param client which OAuth client issued the token; refreshes must use the same one */
    public record Account(GoogleClients.Kind client, String refreshToken, Instant signedInAt, String accountId,
                          String email) {

        public Account withIdentity(String id, String mail) {
            return new Account(client, refreshToken, signedInAt, id, mail);
        }
    }

    public static GoogleAccountStore in(Path stateRoot) {
        return new GoogleAccountStore(stateRoot.resolve("google-account.json"));
    }

    public Optional<Account> load() {
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            Map<String, Object> m = Json.parseObject(AtomicFiles.readString(file));
            return Optional.of(new Account(GoogleClients.Kind.valueOf(Json.string(m, "client")),
                    Json.string(m, "refreshToken"), Instant.parse(Json.string(m, "signedInAt")),
                    Json.optString(m, "accountId"), Json.optString(m, "email")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void save(Account a) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client", a.client().name());
        m.put("refreshToken", a.refreshToken());
        m.put("signedInAt", a.signedInAt().toString());
        m.put("accountId", a.accountId());
        m.put("email", a.email());
        AtomicFiles.writeString(file, Json.write(m));
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException windows) {
            // Windows: the file lives in the user's own profile, which other users can't read.
        }
    }

    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }
}
