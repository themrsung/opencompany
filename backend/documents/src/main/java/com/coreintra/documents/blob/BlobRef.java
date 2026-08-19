package com.coreintra.documents.blob;

import java.io.Serializable;
import java.time.OffsetDateTime;

/** A stored blob: its hash, size, type, and where it came from. */
public final class BlobRef implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String sha256;
    private final long sizeBytes;
    private final String contentType;
    private final String originalFilename;
    private final OffsetDateTime storedAt;

    public BlobRef(String sha256, long sizeBytes, String contentType, String originalFilename,
            OffsetDateTime storedAt) {
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.originalFilename = originalFilename;
        this.storedAt = storedAt;
    }

    /** Lowercase hex SHA-256. Also the storage key and the approval-trail hash. */
    public String sha256() {
        return sha256;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String contentType() {
        return contentType;
    }

    /** The name the user uploaded it under. Never used as a path. */
    public String originalFilename() {
        return originalFilename;
    }

    public OffsetDateTime storedAt() {
        return storedAt;
    }

    @Override
    public String toString() {
        return sha256 + " (" + sizeBytes + " bytes, " + contentType + ")";
    }
}
