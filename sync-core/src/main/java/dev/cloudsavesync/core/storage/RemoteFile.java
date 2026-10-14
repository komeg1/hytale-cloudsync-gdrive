package dev.cloudsavesync.core.storage;

import java.time.Instant;
import java.util.Map;

/**
 * @param md5Hex lowercase hex MD5 of the content as computed by the provider, or null if the
 *               provider doesn't report one
 */
public record RemoteFile(String id, String name, long size, String md5Hex, Instant modified,
                         Map<String, String> properties) {

    public RemoteFile {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
