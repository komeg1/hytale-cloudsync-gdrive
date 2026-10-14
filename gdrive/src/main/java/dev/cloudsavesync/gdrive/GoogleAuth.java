package dev.cloudsavesync.gdrive;

import com.sun.net.httpserver.HttpServer;
import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.storage.StorageException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Google sign-in for the drive.appdata scope (the mod can only see its own hidden app folder, never
 * the rest of the user's Drive), plus access-token refresh.
 *
 * <ul>
 *   <li>{@link #startBrowserSignIn}: authorization code + PKCE with a 127.0.0.1 loopback redirect,
 *       using the Desktop client.</li>
 *   <li>{@link #startCodeSignIn}: device flow ("go to google.com/device and enter ABCD-EFGH"), using
 *       the TV client. For the Steam Deck and when no browser can be opened.</li>
 * </ul>
 */
public final class GoogleAuth {

    public static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";

    static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String DEVICE_URL = "https://oauth2.googleapis.com/device/code";
    static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    private static final Duration SIGN_IN_TIMEOUT = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoogleClients clients;
    private final GoogleAccountStore store;
    private final Clock clock;
    private final Executor executor;

    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    public GoogleAuth(GoogleClients clients, GoogleAccountStore store, Clock clock, Executor executor) {
        this.clients = clients;
        this.store = store;
        this.clock = clock;
        this.executor = executor;
    }

    public boolean isSignedIn() {
        return store.load().isPresent();
    }

    // ------------------------------------------------------------------ browser sign-in

    /** A running browser sign-in: send the player to {@code url}; {@code result} completes on success. */
    public record BrowserSignIn(String url, CompletableFuture<Void> result, Runnable cancel) {
    }

    public BrowserSignIn startBrowserSignIn() throws IOException {
        GoogleClients.Client client = clients.desktop();
        String verifier = randomUrlSafe(64);
        String state = randomUrlSafe(24);
        CompletableFuture<Void> result = new CompletableFuture<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            Map<String, String> q = query(exchange.getRequestURI());
            if (!state.equals(q.get("state")) || (q.get("code") == null && q.get("error") == null)) {
                // Browsers also ask for /favicon.ico and similar: ignore anything that isn't the callback.
                respond(exchange, 404, "Not found");
                return;
            }
            boolean cancelled = q.get("error") != null;
            // Respond before completing: completing stops this server.
            respond(exchange, 200, "<!doctype html><meta charset=utf-8><title>Cloud Save Sync</title>"
                    + "<body style=\"font-family:sans-serif;padding:3em\"><h2>Cloud Save Sync</h2><p>"
                    + (cancelled ? "Sign-in was cancelled. You can close this tab and try again in Hytale."
                    : "Sign-in received. You can close this tab and go back to Hytale."));
            if (cancelled) {
                result.completeExceptionally(new StorageException(StorageException.Kind.AUTH_REQUIRED,
                        "sign-in cancelled (" + q.get("error") + ")"));
                return;
            }
            String code = q.get("code");
            executor.execute(() -> {
                try {
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("code", code);
                    form.put("client_id", client.id());
                    form.put("client_secret", client.secret());
                    form.put("redirect_uri", redirect);
                    form.put("grant_type", "authorization_code");
                    form.put("code_verifier", verifier);
                    storeTokens(GoogleClients.Kind.DESKTOP, tokenRequest(form));
                    result.complete(null);
                } catch (IOException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        });
        server.start();
        // Stop off the server's own thread: stop() waits for in-flight exchanges to finish.
        result.orTimeout(SIGN_IN_TIMEOUT.toMinutes(), TimeUnit.MINUTES)
                .whenCompleteAsync((ok, err) -> server.stop(1), executor);

        String url = AUTH_URL
                + "?client_id=" + Http.enc(client.id())
                + "&redirect_uri=" + Http.enc(redirect)
                + "&response_type=code"
                + "&scope=" + Http.enc(SCOPE)
                + "&code_challenge=" + Http.enc(s256(verifier))
                + "&code_challenge_method=S256"
                + "&state=" + Http.enc(state)
                + "&access_type=offline"
                + "&prompt=consent";
        return new BrowserSignIn(url, result, () -> result.cancel(false));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String html)
            throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------------ code (device) sign-in

    /** Tell the player to open {@code verificationUrl} on any device and enter {@code userCode}. */
    public record CodeSignIn(String userCode, String verificationUrl, Duration expiresIn,
                             CompletableFuture<Void> result) {
    }

    public CodeSignIn startCodeSignIn() throws IOException {
        GoogleClients.Client client = clients.tv();
        HttpResponse<String> r = Http.send(Http.postForm(DEVICE_URL, Map.of("client_id", client.id(), "scope", SCOPE))
                .build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> m = Http.json(r.body());
        if (r.statusCode() != 200) {
            throw new StorageException(StorageException.Kind.OTHER, "Google refused the sign-in request: "
                    + Json.optString(m, "error"));
        }
        String deviceCode = Json.string(m, "device_code");
        String userCode = Json.string(m, "user_code");
        String verificationUrl = Optional.ofNullable(Json.optString(m, "verification_url"))
                .orElse(Json.optString(m, "verification_uri"));
        long expiresIn = Json.number(m, "expires_in");
        long interval = Optional.ofNullable(Json.optNumber(m, "interval")).orElse(5L);

        CompletableFuture<Void> result = new CompletableFuture<>();
        executor.execute(() -> pollDeviceCode(client, deviceCode, interval, clock.instant().plusSeconds(expiresIn),
                result));
        return new CodeSignIn(userCode, verificationUrl, Duration.ofSeconds(expiresIn), result);
    }

    private void pollDeviceCode(GoogleClients.Client client, String deviceCode, long intervalSeconds,
                                Instant expiry, CompletableFuture<Void> result) {
        long interval = intervalSeconds;
        while (!result.isDone()) {
            try {
                Thread.sleep(interval * 1000);
                if (clock.instant().isAfter(expiry)) {
                    result.completeExceptionally(new StorageException(StorageException.Kind.AUTH_REQUIRED,
                            "the sign-in code expired; run the sign-in again"));
                    return;
                }
                Map<String, String> form = new LinkedHashMap<>();
                form.put("client_id", client.id());
                form.put("client_secret", client.secret());
                form.put("device_code", deviceCode);
                form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                HttpResponse<String> r = Http.send(Http.postForm(TOKEN_URL, form).build(),
                        HttpResponse.BodyHandlers.ofString());
                Map<String, Object> m = Http.json(r.body());
                if (r.statusCode() == 200) {
                    storeTokens(GoogleClients.Kind.TV, m);
                    result.complete(null);
                    return;
                }
                String error = String.valueOf(Json.optString(m, "error"));
                switch (error) {
                    case "authorization_pending" -> {
                    }
                    case "slow_down" -> interval += 5;
                    case "access_denied" -> {
                        result.completeExceptionally(new StorageException(StorageException.Kind.AUTH_REQUIRED,
                                "sign-in was declined"));
                        return;
                    }
                    case "expired_token" -> {
                        result.completeExceptionally(new StorageException(StorageException.Kind.AUTH_REQUIRED,
                                "the sign-in code expired; run the sign-in again"));
                        return;
                    }
                    default -> {
                        result.completeExceptionally(new StorageException(StorageException.Kind.OTHER,
                                "sign-in failed: " + error));
                        return;
                    }
                }
            } catch (StorageException offline) {
                // Network blip while the player is typing the code: keep polling until expiry.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.cancel(false);
                return;
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ tokens

    private Map<String, Object> tokenRequest(Map<String, String> form) throws IOException {
        HttpResponse<String> r = Http.send(Http.postForm(TOKEN_URL, form).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> m = Http.json(r.body());
        if (r.statusCode() != 200) {
            String error = String.valueOf(Json.optString(m, "error"));
            StorageException.Kind kind = error.equals("invalid_grant") || error.equals("unauthorized_client")
                    || error.equals("invalid_client") ? StorageException.Kind.AUTH_REQUIRED : StorageException.Kind.OTHER;
            throw new StorageException(kind, "Google sign-in error: " + error);
        }
        return m;
    }

    private synchronized void storeTokens(GoogleClients.Kind kind, Map<String, Object> tokens) throws IOException {
        String refresh = Json.optString(tokens, "refresh_token");
        if (refresh == null) {
            throw new StorageException(StorageException.Kind.OTHER, "Google didn't return a refresh token");
        }
        store.save(new GoogleAccountStore.Account(kind, refresh, clock.instant()));
        cacheAccessToken(tokens);
    }

    private void cacheAccessToken(Map<String, Object> tokens) {
        accessToken = Json.optString(tokens, "access_token");
        long expiresIn = Optional.ofNullable(Json.optNumber(tokens, "expires_in")).orElse(3600L);
        accessTokenExpiry = clock.instant().plusSeconds(Math.max(0, expiresIn - 60));
    }

    /** A valid access token, refreshing it if needed. Throws AUTH_REQUIRED if not signed in. */
    public synchronized String accessToken() throws IOException {
        if (accessToken != null && clock.instant().isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        GoogleAccountStore.Account account = store.load().orElseThrow(() -> new StorageException(
                StorageException.Kind.AUTH_REQUIRED, "not signed in to Google"));
        GoogleClients.Client client = clients.get(account.client());
        Map<String, String> form = new HashMap<>();
        form.put("client_id", client.id());
        form.put("client_secret", client.secret());
        form.put("refresh_token", account.refreshToken());
        form.put("grant_type", "refresh_token");
        try {
            cacheAccessToken(tokenRequest(form));
        } catch (StorageException e) {
            if (e.kind() == StorageException.Kind.AUTH_REQUIRED) {
                // Revoked in the Google account settings, or expired: forget it so the player is asked again.
                store.delete();
            }
            throw e;
        }
        return accessToken;
    }

    /** Called when Drive answers 401 with a token we believed valid. */
    public synchronized void invalidateAccessToken() {
        accessToken = null;
        accessTokenExpiry = Instant.EPOCH;
    }

    /** Forgets the account on this device and asks Google to revoke the token (best effort). */
    public synchronized void signOut() throws IOException {
        Optional<GoogleAccountStore.Account> account = store.load();
        store.delete();
        invalidateAccessToken();
        if (account.isPresent()) {
            try {
                Http.send(Http.postForm(REVOKE_URL, Map.of("token", account.get().refreshToken())).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (StorageException offline) {
                // Already forgotten locally; the token will simply never be used again.
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) {
            return out;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }
}
