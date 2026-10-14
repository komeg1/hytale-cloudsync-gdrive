package dev.cloudsavesync.core.storage;

@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = (done, total) -> {
    };

    /** Called from the I/O thread; {@code total} is -1 when unknown. */
    void onProgress(long bytesDone, long bytesTotal);
}
