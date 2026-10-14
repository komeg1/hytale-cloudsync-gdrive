package dev.cloudsavesync.core.storage;

import java.io.IOException;

/** A cloud failure, classified so the player gets a useful message. */
public class StorageException extends IOException {

    public enum Kind {
        /** No network, DNS failure, timeout: worth retrying later. */
        OFFLINE,
        /** Not signed in, or the sign-in was revoked: the player must sign in again. */
        AUTH_REQUIRED,
        /** The cloud account is out of space. */
        QUOTA,
        NOT_FOUND,
        /** Content didn't match what was expected (checksum mismatch, corrupt download). */
        INTEGRITY,
        OTHER
    }

    private final Kind kind;

    public StorageException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public StorageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
