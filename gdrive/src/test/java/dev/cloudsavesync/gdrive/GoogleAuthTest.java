package dev.cloudsavesync.gdrive;

import dev.cloudsavesync.core.storage.RemoteFile;
import dev.cloudsavesync.core.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleAuthTest {

    @TempDir
    Path tmp;

    private GoogleAuth auth() {
        GoogleClients clients = new GoogleClients(new GoogleClients.Client("desktop-id", "desktop-secret"),
                new GoogleClients.Client("tv-id", "tv-secret"));
        return new GoogleAuth(clients, GoogleAccountStore.in(tmp), Clock.systemUTC(),
                Executors.newCachedThreadPool());
    }

    @Test
    void pkceChallengeMatchesAnIndependentImplementation() {
        // Expected value computed with Python's hashlib + base64.urlsafe_b64encode.
        assertEquals("YMBpI44SBx9xiDblWMb3A_CdPoKhJOpJufYI9z5BEhI",
                GoogleAuth.s256("cloudsavesync-pkce-test-verifier-0123456789abcdef"));
    }

    @Test
    void browserSignInUrlRequestsOnlyTheDriveFileScopeWithPkce() throws Exception {
        GoogleAuth.BrowserSignIn s = auth().startBrowserSignIn();
        try {
            Map<String, String> q = GoogleAuth.query(URI.create(s.url()));
            assertEquals(GoogleAuth.SCOPE, q.get("scope"));
            assertEquals("https://www.googleapis.com/auth/drive.file", q.get("scope"));
            assertEquals("S256", q.get("code_challenge_method"));
            assertEquals("desktop-id", q.get("client_id"));
            assertEquals("offline", q.get("access_type"));
            assertTrue(q.get("redirect_uri").matches("http://127\\.0\\.0\\.1:\\d+"), q.get("redirect_uri"));
            assertFalse(s.url().contains("desktop-secret"), "the secret never goes in the browser URL");
        } finally {
            s.cancel().run();
        }
    }

    @Test
    void loopbackIgnoresForgedCallbacksAndReportsCancellation() throws Exception {
        GoogleAuth.BrowserSignIn s = auth().startBrowserSignIn();
        Map<String, String> q = GoogleAuth.query(URI.create(s.url()));
        String redirect = q.get("redirect_uri");
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        HttpResponse<String> forged = http.send(HttpRequest.newBuilder(URI.create(redirect + "/?code=x&state=wrong"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, forged.statusCode());
        assertFalse(s.result().isDone(), "a callback with the wrong state must be ignored");

        HttpResponse<String> denied = http.send(HttpRequest.newBuilder(URI.create(
                redirect + "/?error=access_denied&state=" + Http.enc(q.get("state")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, denied.statusCode());
        ExecutionException e = assertThrows(ExecutionException.class, () -> s.result().get(5, TimeUnit.SECONDS));
        StorageException cause = assertInstanceOf(StorageException.class, e.getCause());
        assertEquals(StorageException.Kind.AUTH_REQUIRED, cause.kind());
    }

    @Test
    void notSignedInMeansAuthRequired() {
        StorageException e = assertThrows(StorageException.class, () -> auth().accessToken());
        assertEquals(StorageException.Kind.AUTH_REQUIRED, e.kind());
    }

    @Test
    void accountStoreRoundTrip() throws Exception {
        GoogleAccountStore store = GoogleAccountStore.in(tmp);
        GoogleAccountStore.Account a = new GoogleAccountStore.Account(GoogleClients.Kind.TV, "1//refresh",
                Instant.parse("2026-10-14T12:00:00Z"));
        store.save(a);
        assertEquals(a, store.load().orElseThrow());
        store.delete();
        assertTrue(store.load().isEmpty());
    }

    @Test
    void driveFileMetadataMapping() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "abc");
        json.put("name", "world.x.manifest.json");
        json.put("size", "12345");
        json.put("md5Checksum", "d41d8cd98f00b204e9800998ecf8427e");
        json.put("modifiedTime", "2026-10-14T12:00:00.123Z");
        json.put("appProperties", Map.of("world", "x", "kind", "manifest"));

        RemoteFile f = GoogleDriveStorageProvider.toRemoteFile(json);

        assertEquals(12345, f.size());
        assertEquals(Instant.parse("2026-10-14T12:00:00.123Z"), f.modified());
        assertEquals("manifest", f.properties().get("kind"));
    }

    @Test
    void uploadChunksAreExactFileSlices() throws Exception {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        Path file = tmp.resolve("data.bin");
        java.nio.file.Files.write(file, data);

        try (java.io.InputStream in = GoogleDriveStorageProvider.slice(file, 300, 250)) {
            byte[] got = in.readAllBytes();
            assertEquals(250, got.length);
            assertEquals(java.util.Arrays.toString(java.util.Arrays.copyOfRange(data, 300, 550)),
                    java.util.Arrays.toString(got));
        }
        try (java.io.InputStream in = GoogleDriveStorageProvider.slice(file, 900, 500)) {
            assertEquals(100, in.readAllBytes().length, "a slice past the end stops at the end of the file");
        }
    }

    @Test
    void driveErrorsAreClassified() {
        String quota = "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"storageQuotaExceeded\"}]}}";
        assertEquals(StorageException.Kind.QUOTA, Http.failure("x", 403, quota).kind());
        String rate = "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}";
        assertTrue(Http.retryable(403, Http.driveErrorReason(rate)));
        assertTrue(Http.retryable(503, ""));
        assertFalse(Http.retryable(400, ""));
        assertEquals(StorageException.Kind.NOT_FOUND, Http.failure("x", 404, "").kind());
        assertEquals(StorageException.Kind.AUTH_REQUIRED, Http.failure("x", 401, "not json").kind());
    }
}
