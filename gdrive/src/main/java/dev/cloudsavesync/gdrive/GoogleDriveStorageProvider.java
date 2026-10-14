package dev.cloudsavesync.gdrive;

import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.storage.ProgressListener;
import dev.cloudsavesync.core.storage.RemoteFile;
import dev.cloudsavesync.core.storage.StorageException;
import dev.cloudsavesync.core.storage.StorageProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link StorageProvider} on Google Drive with the {@code drive.file} scope: the mod can only see
 * files it created itself. They live in a visible "Hytale Cloud Saves" folder. Plain REST over
 * {@link java.net.http.HttpClient}; no Google client libraries.
 *
 * <p>Files are found by a tag in their appProperties, not by folder, so a second folder (two devices
 * creating it at the same moment) or a file the user moved inside Drive is still found. Files in
 * the trash count as deleted.
 */
public final class GoogleDriveStorageProvider implements StorageProvider {

    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FIELDS = "id,name,size,md5Checksum,modifiedTime,appProperties";
    /** Resumable upload chunk; must be a multiple of 256 KiB. */
    static final int CHUNK = 8 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 5;
    private static final Pattern RANGE = Pattern.compile("bytes=0-(\\d+)");

    static final String FOLDER_NAME = "Hytale Cloud Saves";
    static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    /** appProperties key on everything this mod creates. */
    static final String TAG = "cloudsavesync";
    static final String TAG_FILE = "1";
    static final String TAG_FOLDER = "root";

    private final GoogleAuth auth;
    private String folderId;
    /** {@link GoogleAuth#accountGeneration()} when {@link #folderId} was looked up. */
    private long folderAccount = -1;

    public GoogleDriveStorageProvider(GoogleAuth auth) {
        this.auth = auth;
    }

    // ------------------------------------------------------------------ requests with auth + retry

    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest build(String accessToken) throws IOException;
    }

    /**
     * Sends with a bearer token, refreshing once on 401 and retrying rate limits / 5xx / network
     * errors with backoff. Returns the final response if it is 2xx or 308 (resume incomplete).
     */
    private <T> HttpResponse<T> call(String what, RequestFactory factory, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        return call(what, factory, handler, true);
    }

    /** @param retryOffline false for upload chunks: after a dropped connection we must ask how much arrived. */
    private <T> HttpResponse<T> call(String what, RequestFactory factory, HttpResponse.BodyHandler<T> handler,
                                     boolean retryOffline) throws IOException {
        boolean refreshed = false;
        for (int attempt = 0; ; attempt++) {
            HttpResponse<T> r;
            try {
                r = Http.send(factory.build(auth.accessToken()), handler);
            } catch (StorageException e) {
                if (retryOffline && e.kind() == StorageException.Kind.OFFLINE && attempt + 1 < MAX_ATTEMPTS) {
                    Http.sleepBackoff(attempt);
                    continue;
                }
                throw e;
            }
            int status = r.statusCode();
            if ((status >= 200 && status < 300) || status == 308) {
                return r;
            }
            String body = r.body() instanceof String s ? s : "";
            if (r.body() instanceof InputStream stream) {
                stream.close();
            }
            if (status == 401 && !refreshed) {
                auth.invalidateAccessToken();
                refreshed = true;
                attempt--;
                continue;
            }
            if (Http.retryable(status, Http.driveErrorReason(body)) && attempt + 1 < MAX_ATTEMPTS) {
                Http.sleepBackoff(attempt);
                continue;
            }
            throw Http.failure(what, status, body);
        }
    }

    private static HttpRequest.Builder authed(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token);
    }

    // ------------------------------------------------------------------ StorageProvider

    @Override
    public List<RemoteFile> list() throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        String pageToken = null;
        do {
            String q = "appProperties has { key='" + TAG + "' and value='" + TAG_FILE + "' } and trashed=false";
            String url = API + "?spaces=drive&pageSize=1000&q=" + Http.enc(q) + "&fields="
                    + Http.enc("nextPageToken,files(" + FIELDS + ")")
                    + (pageToken == null ? "" : "&pageToken=" + Http.enc(pageToken));
            HttpResponse<String> r = call("listing cloud saves",
                    t -> authed(url, t).timeout(Http.SHORT).GET().build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> m = Json.parseObject(r.body());
            for (Object f : Json.optArray(m, "files")) {
                out.add(toRemoteFile(asMap(f)));
            }
            pageToken = Json.optString(m, "nextPageToken");
        } while (pageToken != null);
        return out;
    }

    @Override
    public RemoteFile create(String name, Map<String, String> properties, byte[] content) throws IOException {
        return inSavesFolder(() -> createSmall(name, properties, content));
    }

    private RemoteFile createSmall(String name, Map<String, String> properties, byte[] content) throws IOException {
        String boundary = "cloudsavesync-" + UUID.randomUUID();
        String metadata = Json.write(metadata(name, properties));
        byte[] head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metadata
                + "\r\n--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = ByteBuffer.allocate(head.length + content.length + tail.length)
                .put(head).put(content).put(tail).array();
        String url = UPLOAD + "?uploadType=multipart&fields=" + Http.enc(FIELDS);
        HttpResponse<String> r = call("saving " + name, t -> authed(url, t).timeout(Http.SHORT)
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        return toRemoteFile(Json.parseObject(r.body()));
    }

    @Override
    public RemoteFile overwrite(String fileId, byte[] content) throws IOException {
        String url = UPLOAD + "/" + Http.enc(fileId) + "?uploadType=media&fields=" + Http.enc(FIELDS);
        HttpResponse<String> r = call("updating cloud file", t -> authed(url, t).timeout(Http.SHORT)
                .header("Content-Type", "application/octet-stream")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.ofString());
        return toRemoteFile(Json.parseObject(r.body()));
    }

    /** Resumable upload in {@link #CHUNK}-sized pieces; a dropped connection resumes, not restarts. */
    @Override
    public RemoteFile create(String name, Map<String, String> properties, Path content, ProgressListener progress)
            throws IOException {
        return inSavesFolder(() -> createLarge(name, properties, content, progress));
    }

    private RemoteFile createLarge(String name, Map<String, String> properties, Path content, ProgressListener progress)
            throws IOException {
        long total = Files.size(content);
        String startUrl = UPLOAD + "?uploadType=resumable&fields=" + Http.enc(FIELDS);
        byte[] meta = Json.write(metadata(name, properties)).getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> start = call("starting upload", t -> authed(startUrl, t).timeout(Http.SHORT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "application/zip")
                .header("X-Upload-Content-Length", Long.toString(total))
                .POST(HttpRequest.BodyPublishers.ofByteArray(meta)).build(), HttpResponse.BodyHandlers.ofString());
        String session = start.headers().firstValue("Location").orElseThrow(() ->
                new StorageException(StorageException.Kind.OTHER, "Google didn't start the upload session"));

        long offset = 0;
        int failures = 0;
        while (true) {
            int len = (int) Math.min(CHUNK, total - offset);
            long chunkStart = offset;
            String range = len == 0 ? "bytes */" + total : "bytes " + offset + "-" + (offset + len - 1) + "/" + total;
            // Streamed from the file through the client's small internal buffers: no 8 MB array
            // per chunk, so uploading during play doesn't trigger garbage-collection pauses.
            HttpRequest.BodyPublisher body = len == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream(() -> slice(content, chunkStart, len)), len);
            HttpResponse<String> r;
            try {
                // The session URL carries its own authorization, but sending the token is harmless.
                r = call("uploading", t -> authed(session, t).timeout(Http.LONG)
                        .header("Content-Range", range)
                        .PUT(body).build(),
                        HttpResponse.BodyHandlers.ofString(), false);
            } catch (StorageException e) {
                if (e.kind() != StorageException.Kind.OFFLINE || ++failures >= MAX_ATTEMPTS) {
                    throw e;
                }
                offset = queryUploadedBytes(session, total);
                continue;
            }
            if (r.statusCode() == 308) {
                offset = rangeEnd(r).map(end -> end + 1).orElse(0L);
                progress.onProgress(offset, total);
                continue;
            }
            progress.onProgress(total, total);
            return toRemoteFile(Json.parseObject(r.body()));
        }
    }

    /** {@code length} bytes of {@code file} starting at {@code offset}. */
    static InputStream slice(Path file, long offset, long length) {
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.READ).position(offset);
            InputStream in = java.nio.channels.Channels.newInputStream(ch);
            return new java.io.FilterInputStream(in) {
                private long left = length;

                @Override
                public int read() throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    int b = super.read();
                    if (b >= 0) {
                        left--;
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    int n = super.read(b, off, (int) Math.min(len, left));
                    if (n > 0) {
                        left -= n;
                    }
                    return n;
                }
            };
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Asks the session how many bytes Google already has (after a dropped connection). */
    private long queryUploadedBytes(String session, long total) throws IOException {
        HttpResponse<String> r = call("resuming upload", t -> authed(session, t).timeout(Http.SHORT)
                .header("Content-Range", "bytes */" + total)
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 308 ? rangeEnd(r).map(end -> end + 1).orElse(0L) : total;
    }

    private static Optional<Long> rangeEnd(HttpResponse<?> r) {
        return r.headers().firstValue("Range").map(RANGE::matcher).filter(Matcher::matches)
                .map(m -> Long.parseLong(m.group(1)));
    }

    @Override
    public void download(String fileId, Path target, ProgressListener progress) throws IOException {
        String url = API + "/" + Http.enc(fileId) + "?alt=media";
        HttpResponse<InputStream> r = call("downloading", t -> authed(url, t).timeout(Http.LONG).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            long lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport >= CHUNK) {
                    progress.onProgress(done, total);
                    lastReport = done;
                }
            }
            progress.onProgress(done, total);
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException(StorageException.Kind.OFFLINE, "download interrupted", e);
        }
    }

    @Override
    public byte[] read(String fileId) throws IOException {
        String url = API + "/" + Http.enc(fileId) + "?alt=media";
        return call("reading cloud file", t -> authed(url, t).timeout(Http.SHORT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    @Override
    public void delete(String fileId) throws IOException {
        String url = API + "/" + Http.enc(fileId);
        try {
            call("deleting old cloud file", t -> authed(url, t).timeout(Http.SHORT).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (StorageException e) {
            if (e.kind() != StorageException.Kind.NOT_FOUND) {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    private Map<String, Object> metadata(String name, Map<String, String> properties) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("parents", List.of(folderId()));
        Map<String, Object> props = new LinkedHashMap<>(properties);
        props.put(TAG, TAG_FILE);
        m.put("appProperties", props);
        return m;
    }

    private synchronized void forgetFolder() {
        folderId = null;
    }

    @FunctionalInterface
    private interface Creation {
        RemoteFile run() throws IOException;
    }

    /**
     * Creating a file in a folder that's gone (deleted or trashed in Drive, or belonging to a
     * previous account) fails with 404: look the folder up again and retry once.
     */
    private RemoteFile inSavesFolder(Creation create) throws IOException {
        try {
            return create.run();
        } catch (StorageException e) {
            if (e.kind() != StorageException.Kind.NOT_FOUND) {
                throw e;
            }
            forgetFolder();
            return create.run();
        }
    }

    /** The "Hytale Cloud Saves" folder: the oldest one this mod created, or a new one. */
    private synchronized String folderId() throws IOException {
        if (folderId != null && folderAccount == auth.accountGeneration()) {
            return folderId;
        }
        folderId = null;
        folderAccount = auth.accountGeneration();
        String q = "appProperties has { key='" + TAG + "' and value='" + TAG_FOLDER + "' } and mimeType='"
                + FOLDER_MIME + "' and trashed=false";
        String findUrl = API + "?spaces=drive&pageSize=10&orderBy=createdTime&q=" + Http.enc(q) + "&fields="
                + Http.enc("files(id)");
        HttpResponse<String> found = call("finding the saves folder",
                t -> authed(findUrl, t).timeout(Http.SHORT).GET().build(), HttpResponse.BodyHandlers.ofString());
        List<Object> folders = Json.optArray(Json.parseObject(found.body()), "files");
        if (!folders.isEmpty()) {
            folderId = Json.string(asMap(folders.getFirst()), "id");
            return folderId;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", FOLDER_NAME);
        meta.put("mimeType", FOLDER_MIME);
        meta.put("description", "Created by the Cloud Save Sync mod for Hytale. These are your synced worlds:"
                + " deleting files here deletes their cloud copies.");
        meta.put("appProperties", Map.of(TAG, TAG_FOLDER));
        byte[] body = Json.write(meta).getBytes(StandardCharsets.UTF_8);
        String createUrl = API + "?fields=id";
        HttpResponse<String> created = call("creating the saves folder", t -> authed(createUrl, t).timeout(Http.SHORT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        folderId = Json.string(Json.parseObject(created.body()), "id");
        return folderId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    static RemoteFile toRemoteFile(Map<String, Object> f) {
        Map<String, String> props = new LinkedHashMap<>();
        Map<String, Object> app = Json.optObject(f, "appProperties");
        if (app != null) {
            app.forEach((k, v) -> props.put(k, String.valueOf(v)));
        }
        String size = Json.optString(f, "size"); // Drive returns int64 values as strings
        String modified = Json.optString(f, "modifiedTime");
        return new RemoteFile(Json.string(f, "id"), Json.string(f, "name"), size == null ? 0 : Long.parseLong(size),
                Json.optString(f, "md5Checksum"), modified == null ? null : Instant.parse(modified), props);
    }
}
