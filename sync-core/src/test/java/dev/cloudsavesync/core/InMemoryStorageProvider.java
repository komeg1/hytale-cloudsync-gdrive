package dev.cloudsavesync.core;

import dev.cloudsavesync.core.storage.AccountInfo;
import dev.cloudsavesync.core.storage.ProgressListener;
import dev.cloudsavesync.core.storage.RemoteFile;
import dev.cloudsavesync.core.storage.StorageException;
import dev.cloudsavesync.core.storage.StorageProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double: a provider backed by a map, with switches for going offline and corrupting uploads. */
final class InMemoryStorageProvider implements StorageProvider {

    private record Entry(RemoteFile meta, byte[] content) {
    }

    private final Map<String, Entry> files = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Clock clock;
    volatile boolean offline;
    /** Report a wrong MD5 for the next large upload, as if bytes were corrupted in transit. */
    volatile boolean corruptNextUpload;
    /** Runs inside the next large upload, to simulate things happening while it is in flight. */
    volatile Runnable duringNextUpload;

    static final AccountInfo ACCOUNT = new AccountInfo("account-1", "player@example.com");

    InMemoryStorageProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AccountInfo account() {
        return ACCOUNT;
    }

    private void checkOnline() throws StorageException {
        if (offline) {
            throw new StorageException(StorageException.Kind.OFFLINE, "offline (test)");
        }
    }

    @Override
    public synchronized List<RemoteFile> list() throws IOException {
        checkOnline();
        return files.values().stream().map(Entry::meta).toList();
    }

    @Override
    public RemoteFile create(String name, Map<String, String> properties, Path content, ProgressListener progress)
            throws IOException {
        checkOnline();
        byte[] bytes = Files.readAllBytes(content);
        Runnable during = duringNextUpload;
        duringNextUpload = null;
        if (during != null) {
            during.run();
        }
        progress.onProgress(bytes.length, bytes.length);
        RemoteFile f = put(name, properties, bytes);
        if (corruptNextUpload) {
            corruptNextUpload = false;
            f = new RemoteFile(f.id(), f.name(), f.size(), "00000000000000000000000000000000", f.modified(),
                    f.properties());
        }
        return f;
    }

    @Override
    public synchronized RemoteFile create(String name, Map<String, String> properties, byte[] content)
            throws IOException {
        checkOnline();
        return put(name, properties, content);
    }

    private synchronized RemoteFile put(String name, Map<String, String> properties, byte[] content) {
        String id = "f" + ids.incrementAndGet();
        RemoteFile meta = new RemoteFile(id, name, content.length, md5(content), clock.instant(), properties);
        files.put(id, new Entry(meta, content.clone()));
        return meta;
    }

    @Override
    public synchronized RemoteFile overwrite(String fileId, byte[] content) throws IOException {
        checkOnline();
        Entry e = files.get(fileId);
        if (e == null) {
            throw new StorageException(StorageException.Kind.NOT_FOUND, fileId);
        }
        RemoteFile meta = new RemoteFile(fileId, e.meta().name(), content.length, md5(content), clock.instant(),
                e.meta().properties());
        files.put(fileId, new Entry(meta, content.clone()));
        return meta;
    }

    @Override
    public void download(String fileId, Path target, ProgressListener progress) throws IOException {
        byte[] bytes = read(fileId);
        Files.write(target, bytes);
        progress.onProgress(bytes.length, bytes.length);
    }

    @Override
    public synchronized byte[] read(String fileId) throws IOException {
        checkOnline();
        Entry e = files.get(fileId);
        if (e == null) {
            throw new StorageException(StorageException.Kind.NOT_FOUND, fileId);
        }
        return e.content().clone();
    }

    @Override
    public synchronized void delete(String fileId) throws IOException {
        checkOnline();
        files.remove(fileId);
    }

    synchronized List<String> names() {
        return new ArrayList<>(files.values().stream().map(e -> e.meta().name()).toList());
    }

    /** Overwrites a stored file's bytes without updating its checksum: bit rot in the cloud. */
    synchronized void damage(String fileId) {
        Entry e = files.get(fileId);
        byte[] bytes = e.content().clone();
        bytes[bytes.length / 2] ^= 0x55;
        files.put(fileId, new Entry(e.meta(), bytes));
    }

    private static String md5(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
