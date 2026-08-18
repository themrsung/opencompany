package com.coreintra.documents.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.blob.BlobStore;
import com.coreintra.documents.entity.BlobEntity;
import com.coreintra.documents.repository.BlobRepository;

/**
 * Keeps the blob index and the blob volume telling the same story.
 *
 * <p>The store owns the bytes and the table owns the description of them. Writing through
 * one service is what stops the two diverging: a file with no row is invisible to the
 * reaper and never reclaimed, and a row with no file is a document that opens as an error.
 *
 * <p>Storing is idempotent because the address is the content. Forty people attaching the
 * same receipt produce one file, one row, and forty references.
 */
@Service
public class BlobService {

    private final BlobStore store;

    private final BlobRepository blobs;

    public BlobService(BlobStore store, BlobRepository blobs) {
        this.store = store;
        this.blobs = blobs;
    }

    /**
     * Stores content and indexes it.
     *
     * <p>Returns the same reference for the same bytes, without a second copy and without
     * touching the existing row: the original filename recorded is the first one seen,
     * because the bytes are what is stored and a later upload's name describes the same
     * thing under a different label.
     */
    @Transactional
    public BlobRef store(byte[] content, String contentType, String originalFilename) {
        BlobRef ref = store.put(content, contentType, originalFilename);
        if (!blobs.existsById(ref.sha256())) {
            blobs.save(new BlobEntity(ref.sha256(), ref.sizeBytes(), contentType, originalFilename));
        }
        return ref;
    }

    /** The bytes. Callers that only need to know a blob exists should ask {@link #describe}. */
    @Transactional(readOnly = true)
    public byte[] contentOf(String sha256) {
        return store.get(sha256);
    }

    @Transactional(readOnly = true)
    public Optional<BlobEntity> describe(String sha256) {
        return blobs.findById(sha256);
    }

    @Transactional(readOnly = true)
    public boolean exists(String sha256) {
        return blobs.existsById(sha256);
    }

    /** Reaping candidates: bytes on the volume that nothing points at any more. */
    @Transactional(readOnly = true)
    public List<BlobEntity> unreferenced() {
        return blobs.findUnreferenced();
    }

    /**
     * Reclaims the file and leaves the row as a tombstone.
     *
     * <p>The row is never deleted. A hash in an approval trail, in a render's metadata or
     * in a client's export must always resolve to something; resolving to nothing is
     * indistinguishable from a corrupted audit trail, and the operator investigating would
     * have no way to tell which they were looking at.
     *
     * @throws IllegalStateException if anything still references the blob. Re-checked here
     *         rather than trusted from the candidate list, because a document approved in
     *         the meantime would otherwise lose its bytes.
     */
    @Transactional
    public void reap(String sha256, OffsetDateTime at) {
        Optional<BlobEntity> found = blobs.findById(sha256);
        if (!found.isPresent()) {
            throw new IllegalArgumentException("no blob indexed under " + sha256);
        }
        BlobEntity blob = found.get();
        if (blob.isReaped()) {
            return;
        }
        if (blobs.isReferenced(sha256)) {
            throw new IllegalStateException(
                    "blob " + sha256 + " is still referenced and will not be reaped. An approved "
                    + "document's bytes must remain retrievable forever.");
        }
        store.delete(sha256);
        blob.markReaped(at);
        blobs.save(blob);
    }
}
