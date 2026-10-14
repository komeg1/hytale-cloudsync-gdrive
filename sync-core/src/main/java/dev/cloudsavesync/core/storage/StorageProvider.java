package dev.cloudsavesync.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A private, app-only file space in some cloud service (Google Drive's appDataFolder, a OneDrive
 * app folder, a Dropbox app folder...). Flat: files are identified by provider-assigned ids and
 * carry a name plus small string properties. Implementations must be thread-safe.
 *
 * <p>All methods may block on the network and must never be called from the game thread. Failures
 * are reported as {@link StorageException} so callers can tell "offline" from "signed out".
 */
public interface StorageProvider {

    /** Every file in the app space. */
    List<RemoteFile> list() throws IOException;

    /** Uploads a (possibly large) local file as a new remote file. */
    RemoteFile create(String name, Map<String, String> properties, Path content, ProgressListener progress)
            throws IOException;

    /** Creates a small remote file from memory (manifests, locks). */
    RemoteFile create(String name, Map<String, String> properties, byte[] content) throws IOException;

    /** Replaces the content of an existing small file. */
    RemoteFile overwrite(String fileId, byte[] content) throws IOException;

    /** Downloads a file's content to {@code target}, replacing it. */
    void download(String fileId, Path target, ProgressListener progress) throws IOException;

    byte[] read(String fileId) throws IOException;

    /** Deleting a file that no longer exists is not an error. */
    void delete(String fileId) throws IOException;
}
