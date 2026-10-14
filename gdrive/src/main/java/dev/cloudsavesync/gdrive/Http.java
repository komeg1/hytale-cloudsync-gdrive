package dev.cloudsavesync.gdrive;

import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.storage.StorageException;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared HTTP plumbing and Google error classification. */
final class Http {

    /**
     * Never follows redirects: resumable uploads answer each chunk with "308 Resume Incomplete",
     * which isn't a redirect (no Location header) and must reach our code. Following redirects made
     * the client fail every multi-chunk upload with "Invalid redirection". No Google endpoint we use
     * redirects.
     */
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    static final Duration SHORT = Duration.ofSeconds(30);
    static final Duration LONG = Duration.ofMinutes(5);

    private Http() {
    }

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static HttpRequest.Builder postForm(String url, Map<String, String> fields) {
        return HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(SHORT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields)));
    }

    static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return CLIENT.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException(StorageException.Kind.OFFLINE, "interrupted", e);
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException(StorageException.Kind.OFFLINE,
                    "can't reach Google (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Parses a JSON object body; returns an empty map if the body isn't JSON. */
    static Map<String, Object> json(String body) {
        try {
            return Json.parseObject(body);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Drive's machine-readable error reason (e.g. storageQuotaExceeded), or "". */
    @SuppressWarnings("unchecked")
    static String driveErrorReason(String body) {
        Map<String, Object> err = Json.optObject(json(body), "error");
        if (err == null) {
            return "";
        }
        List<Object> errors = Json.optArray(err, "errors");
        if (!errors.isEmpty() && errors.getFirst() instanceof Map<?, ?> first) {
            String reason = Json.optString((Map<String, Object>) first, "reason");
            return reason == null ? "" : reason;
        }
        return "";
    }

    static boolean retryable(int status, String reason) {
        return status == 429 || status >= 500
                || (status == 403 && (reason.equals("rateLimitExceeded") || reason.equals("userRateLimitExceeded")));
    }

    static StorageException failure(String what, int status, String body) {
        String reason = driveErrorReason(body);
        StorageException.Kind kind = switch (status) {
            case 401 -> StorageException.Kind.AUTH_REQUIRED;
            case 404 -> StorageException.Kind.NOT_FOUND;
            case 403 -> reason.equals("storageQuotaExceeded") || reason.equals("quotaExceeded")
                    ? StorageException.Kind.QUOTA : StorageException.Kind.OTHER;
            default -> StorageException.Kind.OTHER;
        };
        return new StorageException(kind, what + " failed: HTTP " + status + (reason.isEmpty() ? "" : " " + reason));
    }

    static void sleepBackoff(int attempt) throws StorageException {
        try {
            Thread.sleep(Math.min(30_000L, 1000L << attempt) + (long) (Math.random() * 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException(StorageException.Kind.OFFLINE, "interrupted", e);
        }
    }
}
