package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The index over the blob store. The bytes are not here.
 *
 * <p>The primary key <em>is</em> the content hash, so this row is a statement about
 * a byte string rather than about a file. Two documents that share an attachment
 * share this row, which is what keeps a database backup small enough to take hourly
 * while the volume is backed up on its own schedule.
 *
 * <p>{@code bytesReapedAt} is how deletion works here given "no hard delete": the
 * row survives as a tombstone that still answers "these bytes existed, they were
 * this long, they were reclaimed on this date", and only the file on the volume goes.
 */
@Entity
@Table(name = "blob")
public class BlobEntity {

    @Id
    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    /** What the uploader called it. Never used as a path: the store addresses by hash. */
    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "bytes_reaped_at")
    private OffsetDateTime bytesReapedAt;

    protected BlobEntity() {
    }

    public BlobEntity(String sha256, long sizeBytes, String contentType, String originalFilename) {
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.originalFilename = originalFilename;
        this.createdAt = OffsetDateTime.now();
    }

    /** Records that the file was reclaimed. The row stays: an audit trail needs the tombstone. */
    public void markReaped(OffsetDateTime at) {
        this.bytesReapedAt = at;
    }

    public String sha256() {
        return sha256;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String contentType() {
        return contentType;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime bytesReapedAt() {
        return bytesReapedAt;
    }

    /** True when the bytes are gone from the volume and only this description remains. */
    public boolean isReaped() {
        return bytesReapedAt != null;
    }
}
